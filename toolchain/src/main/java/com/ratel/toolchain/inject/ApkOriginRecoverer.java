package com.ratel.toolchain.inject;

import com.ratel.toolchain.dex.DexStore;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 从 ratel 产物剥离注入层，得到可 re-init 的原始基准 apk。
 *
 * <p>只剥 ratel 自身痕迹，不做完美还原：front dexes（runtime/入口注入/patch 混编）全部
 * 丢弃——上一轮的 patch 与模块不迁移，需要在新工程重建；原 dex 改名归位；
 * assets/ratel/* 删除；v1 签名文件失效剔除（产物为未签名包）。
 * queries 残留无害不处理（AddQueriesForPackage 有 contains 防重）。唯一携带物是
 * 原始证书（ratel-config.json originCerts），re-init 后签名代理依赖它。
 *
 * <p>front dex 边界判定（无需构建期记录，精确）：从 ordinal 1 起连续判定——
 * dex 含 com.ratel.** 类（runtime），或其任一类出现在更后 ordinal 的 dex 中
 * （替换语义下被替换类必然 shadowed；正常 apk 各 dex 间无重复类）。
 */
public final class ApkOriginRecoverer {

    private static final Pattern DEX_NAME = Pattern.compile("classes(\\d*)\\.dex");

    /** 还原结果。 */
    public static final class Recovered {
        public final Path apk;
        public final List<String> originCerts;
        public final int dexShift;

        Recovered(Path apk, List<String> originCerts, int dexShift) {
            this.apk = apk;
            this.originCerts = originCerts;
            this.dexShift = dexShift;
        }
    }

    private ApkOriginRecoverer() {
    }

    public static Recovered recover(Path patchedApk, Path outApk) throws Exception {
        String config = readEntry(patchedApk, "assets/ratel/ratel-config.json");
        if (config == null) {
            throw new IllegalStateException("产物缺少 assets/ratel/ratel-config.json，无法还原");
        }
        List<String> originCerts = parseStringArray(config, "originCerts");

        int shift = detectDexShift(patchedApk);
        if (shift <= 0) {
            throw new IllegalStateException("未检出 ratel front dex，无法还原");
        }

        Files.createDirectories(outApk.getParent());
        try (ZipFile zip = new ZipFile(patchedApk.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outApk))) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("assets/ratel/")) {
                    continue; // ratel 运行时配置/模块/so 全部剥离
                }
                Matcher m = DEX_NAME.matcher(name);
                if (m.matches()) {
                    long ordinal = parseOrdinal(m.group(1));
                    if (ordinal <= shift) {
                        continue; // front dex 丢弃（runtime/注入/patch）
                    }
                    long back = ordinal - shift;
                    String backName = back == 1 ? "classes.dex" : "classes" + back + ".dex";
                    copyEntry(zip, entry, zos, backName);
                    continue;
                }
                if (name.startsWith("META-INF/")
                        && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
                    continue; // v1 签名对还原后内容必然失效，剔除
                }
                copyEntry(zip, entry, zos, name);
            }
        }
        return new Recovered(outApk, originCerts, shift);
    }

    /** front dex 个数：从 ordinal 1 起连续命中（ratel 类 ∨ 类被更后 dex 遮蔽）。 */
    static int detectDexShift(Path apk) throws Exception {
        try (DexStore store = new DexStore(apk)) {
            TreeMap<Long, Set<String>> byOrdinal = new TreeMap<>();
            for (String entryName : store.dexEntryNames()) {
                Matcher m = DEX_NAME.matcher(entryName);
                if (!m.matches()) continue;
                Set<String> classes = new HashSet<>();
                var entry = store.findEntry(entryName);
                if (entry == null) continue;
                for (var cd : entry.getDexFile().getClasses()) {
                    String type = cd.getType();
                    classes.add(type.substring(1, type.length() - 1));
                }
                byOrdinal.put(parseOrdinal(m.group(1)), classes);
            }
            int shift = 0;
            for (Map.Entry<Long, Set<String>> e : byOrdinal.entrySet()) {
                Set<String> tail = new HashSet<>();
                byOrdinal.tailMap(e.getKey() + 1).values().forEach(tail::addAll);
                boolean front = false;
                for (String cls : e.getValue()) {
                    if (cls.startsWith("com/ratel/") || tail.contains(cls)) {
                        front = true;
                        break;
                    }
                }
                if (!front) break;
                shift++;
            }
            return shift;
        }
    }

    private static long parseOrdinal(String suffix) {
        return suffix == null || suffix.isEmpty() ? 1 : Long.parseLong(suffix);
    }

    private static void copyEntry(ZipFile zip, ZipEntry entry, ZipOutputStream zos, String name)
            throws Exception {
        ZipEntry out = new ZipEntry(name);
        out.setMethod(entry.getMethod());
        if (entry.getMethod() == ZipEntry.STORED) {
            out.setSize(entry.getSize());
            out.setCrc(entry.getCrc());
        }
        zos.putNextEntry(out);
        try (InputStream in = zip.getInputStream(entry)) {
            in.transferTo(zos);
        }
        zos.closeEntry();
    }

    private static String readEntry(Path apk, String name) throws Exception {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return null;
            return new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 手写解析 json 字符串数组字段（config 格式固定，不引解析依赖）。 */
    private static List<String> parseStringArray(String json, String field) {
        List<String> out = new ArrayList<>();
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return out;
        int start = json.indexOf('[', idx);
        int end = json.indexOf(']', start);
        if (start < 0 || end < 0) return out;
        for (String piece : json.substring(start + 1, end).split(",")) {
            String v = piece.trim().replaceAll("^\"|\"$", "").trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** 还原说明（命令行输出用）。 */
    public static String summary(Recovered r) {
        return "已剥离 ratel 注入层: front dex ×" + r.dexShift + " 丢弃, assets/ratel/* 删除, "
                + "v1 签名剔除（未签名基准）\n"
                + "  原始证书携带: " + r.originCerts.size() + " 张（签名代理还原用）\n"
                + "  注意: 上一轮的 patch dex/内嵌模块不迁移——需要请在新建工程里重建";
    }
}
