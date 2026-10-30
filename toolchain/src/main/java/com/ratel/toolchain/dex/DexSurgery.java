package com.ratel.toolchain.dex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dex 手术（改名遮蔽架构，2026-10-29 定版）：
 *
 * <p>patch dex 直接命名为 {@code classes.dex} 并物理排在 zip 最前；原 apk 全部 dex 条目
 * 顺序 +1 改名（classes.dex→classes2.dex…），字节原样 raw-copy 不做任何重序列化。</p>
 *
 * <p>类遮蔽语义：BaseDexClassLoader 按 dex 物理顺序解析，patched 类先命中即遮蔽原 dex 中的
 * 同名旧类（旧类字节仍保留在包内，可审计可回滚）。相比「删除重写」路线：</p>
 * <ul>
 *   <li>零全量重序列化——微信 12MB×16 个 dex 的重写从 10 分钟级降到 0（改名而已）；</li>
 *   <li>无 65535 原位溢出问题（patch dex 由 d8 产出，天然合规）；</li>
 *   <li>原 dex 字节完全不动，dex2oat 重新校验面最小。</li>
 * </ul>
 *
 * <p>已知代价：产物 dex 数量 +patchDexCount；baseline profile 的 per-dex 索引失效（ART 容忍，
 * 触发重新校验）；极少数 app 硬编码自身 classesN.dex 路径的行为受影响（登记观察）。</p>
 */
public final class DexSurgery {

    public static final class Result {
        /** dex 条目名 -> 新 dex 字节（覆写或新增条目）。 */
        public final Map<String, byte[]> overrides;
        /** 原 dex 条目改名映射（raw-copy 时改名，零字节重写）。 */
        public final Map<String, String> renames;
        public final int replacedCount;
        public final int addedCount;

        Result(Map<String, byte[]> overrides, Map<String, String> renames,
               int replacedCount, int addedCount) {
            this.overrides = overrides;
            this.renames = renames;
            this.replacedCount = replacedCount;
            this.addedCount = addedCount;
        }
    }

    /** 从 dex 字节收集全部类描述符（front dex 组装统计用）。 */
    public static Set<String> collectDescriptors(byte[] dexBytes) throws IOException {
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("ratel-cd-", ".dex");
        try {
            java.nio.file.Files.write(temp, dexBytes);
            var dex = org.jf.dexlib2.DexFileFactory.loadDexFile(
                    temp.toFile(), org.jf.dexlib2.Opcodes.forApi(29));
            Set<String> out = new java.util.HashSet<>();
            for (var classDef : dex.getClasses()) {
                out.add(classDef.getType());
            }
            return out;
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    private static final Pattern DEX_NAME = Pattern.compile("classes(\\d*)\\.dex");

    /** gradle ratelPatchDex 产物加载：目录内 classes*.dex → 字节列表 + 全部类描述符。 */
    public static final class LoadedPatch {
        public final List<byte[]> dexes;
        public final Set<String> classDescriptors;

        LoadedPatch(List<byte[]> dexes, Set<String> classDescriptors) {
            this.dexes = dexes;
            this.classDescriptors = classDescriptors;
        }
    }

    public static LoadedPatch loadPatchDexes(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = java.nio.file.Files.list(dir)) {
            stream.filter(p -> DEX_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted() // classes.dex < classes2.dex < ...（'.'(46) < '2'(50)）
                    .forEach(files::add);
        }
        if (files.isEmpty()) return null;
        List<byte[]> dexes = new ArrayList<>();
        Set<String> descriptors = new java.util.HashSet<>();
        for (Path f : files) {
            dexes.add(java.nio.file.Files.readAllBytes(f));
            var dex = org.jf.dexlib2.DexFileFactory.loadDexFile(
                    f.toFile(), org.jf.dexlib2.Opcodes.forApi(29));
            for (var classDef : dex.getClasses()) {
                descriptors.add(classDef.getType());
            }
        }
        return new LoadedPatch(dexes, descriptors);
    }

    /**
     * @param patchDexFiles patch dex 字节列表（d8 可能产出多个，按序号排好）
     * @param patchClassDescriptors patch 中全部类描述符（用于区分替换/新增统计）
     */
    public static Result merge(Path originApk, List<byte[]> patchDexFiles,
                               Set<String> patchClassDescriptors) throws IOException {
        if (patchDexFiles.isEmpty()) {
            throw new IOException("patch dex 为空");
        }

        // 原 apk 全部 dex 条目，按「序数」排序：classes.dex=1, classes2.dex=2, classes3.dex=3…
        // （注意不存在 classes1.dex，序数与后缀数字只在前两个错位，不能用裸数字做加减）
        TreeMap<Long, String> originDexes = new TreeMap<>(); // ordinal -> entryName
        try (DexStore store = new DexStore(originApk)) {
            for (String entryName : store.dexEntryNames()) {
                Matcher m = DEX_NAME.matcher(entryName);
                if (!m.matches()) continue;
                String idx = m.group(1);
                long ordinal = idx.isEmpty() ? 1L : Long.parseLong(idx);
                originDexes.put(ordinal, entryName);
            }
        }

        // patch 类在原 dex 中是否存在（读一次全量类索引）
        int replaced = 0;
        try (DexStore store = new DexStore(originApk)) {
            java.util.Set<String> originTypes = new java.util.HashSet<>();
            for (String entryName : store.dexEntryNames()) {
                var entry = store.findEntry(entryName);
                if (entry == null) continue;
                for (var classDef : entry.getDexFile().getClasses()) {
                    originTypes.add(classDef.getType());
                }
            }
            for (String desc : patchClassDescriptors) {
                if (originTypes.contains(desc)) replaced++;
            }
        }

        // 改名：全部 dex 序数 +patchDexFiles.size()（原 classes.dex→classes2.dex…），
        // 新序数 1 对应 classes.dex（无后缀），其余 classes{N}.dex
        int shift = patchDexFiles.size();
        Map<String, String> renames = new HashMap<>();
        for (Map.Entry<Long, String> e : originDexes.entrySet()) {
            long newOrdinal = e.getKey() + shift;
            renames.put(e.getValue(), newOrdinal == 1 ? "classes.dex" : "classes" + newOrdinal + ".dex");
        }

        // patch dex 占据 classes.dex 起始序列号，物理序由 packer 保证在最前
        Map<String, byte[]> overrides = new LinkedHashMap<>();
        for (int i = 0; i < patchDexFiles.size(); i++) {
            overrides.put(i == 0 ? "classes.dex" : "classes" + (i + 1) + ".dex", patchDexFiles.get(i));
        }

        return new Result(overrides, renames, replaced,
                patchClassDescriptors.size() - replaced);
    }
}
