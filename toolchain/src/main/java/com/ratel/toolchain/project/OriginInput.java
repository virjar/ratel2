package com.ratel.toolchain.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * origin 输入规范化：支持单 apk / split 集合目录 / .xapk / .apks（bundletool）容器，
 * 统一展开到工作区 origin-splits/（base.apk + split*.apk）。
 *
 * <p>split APK 处理策略（不做 arsc/dex/manifest 合并——那条路 bug 面不可控）：
 * build 只手术 base.apk，其余 split 原样重签，产物经 install-multiple 安装。
 */
public final class OriginInput {

    public static final String BASE_NAME = "base.apk";

    private OriginInput() {
    }

    /**
     * @return splits 列表（base 在首位）；单 apk 输入返回仅 base 且不落 splitsDir
     *         （origin.apk 已是不可变基准，无需冗余拷贝一份 256MB 级文件）。
     */
    public static List<Path> normalize(Path input, Path splitsDir) throws IOException {
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);

        List<Path> apks;
        if (Files.isDirectory(input)) {
            apks = fromDirectory(input);
        } else if (name.endsWith(".xapk")) {
            apks = fromXapk(input, splitsDir);
        } else if (name.endsWith(".apks")) {
            apks = fromApks(input, splitsDir);
        } else if (name.endsWith(".apk")) {
            return List.of(input); // 单 apk：直接用，不建 origin-splits/
        } else {
            throw new IOException("不支持的输入: " + input + "（支持 .apk / split 目录 / .xapk / .apks）");
        }

        // split 集合：展开到 origin-splits/（base + splits，构建时仅手术 base）
        Files.createDirectories(splitsDir);
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < apks.size(); i++) {
            Path src = apks.get(i);
            String targetName = i == 0 ? BASE_NAME : src.getFileName().toString();
            Path target = splitsDir.resolve(targetName);
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            out.add(target);
        }
        return out;
    }

    private static List<Path> fromDirectory(Path dir) throws IOException {
        List<Path> apks = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".apk"))
                    .sorted((a, b) -> Boolean.compare(!isBase(a), !isBase(b))) // base 优先
                    .forEach(apks::add);
        }
        if (apks.isEmpty()) {
            throw new IOException("目录中无 apk: " + dir);
        }
        if (!isBase(apks.get(0))) {
            throw new IOException("目录中未识别到 base.apk（需含 base.apk 或可解析 manifest 的主 apk）");
        }
        return apks;
    }

    private static boolean isBase(Path apk) {
        return apk.getFileName().toString().toLowerCase(Locale.ROOT).equals(BASE_NAME);
    }

    /** xapk（APKPure 容器）：manifest.json 的 split_apks[].file，id=base 为主包。 */
    private static List<Path> fromXapk(Path xapk, Path workDir) throws IOException {
        Path extract = Files.createTempDirectory("ratel-xapk-");
        String baseFile = null;
        List<String> splitFiles = new ArrayList<>();
        try (ZipFile zip = new ZipFile(xapk.toFile())) {
            ZipEntry manifest = zip.getEntry("manifest.json");
            if (manifest == null) {
                throw new IOException("xapk 缺少 manifest.json");
            }
            String json = new String(zip.getInputStream(manifest).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            // 手写解析 split_apks 数组（结构固定，不引解析依赖）
            int idx = json.indexOf("\"split_apks\"");
            int start = json.indexOf('[', idx);
            int end = json.indexOf(']', start);
            if (idx < 0 || start < 0 || end < 0) {
                throw new IOException("xapk manifest.json 缺少 split_apks");
            }
            for (String piece : json.substring(start + 1, end).split("\\{")) {
                String file = extractJsonString(piece, "file");
                String id = extractJsonString(piece, "id");
                if (file == null) continue;
                if (id != null && id.equalsIgnoreCase("base")) {
                    baseFile = file;
                } else {
                    splitFiles.add(file);
                }
            }
            if (baseFile == null) {
                throw new IOException("xapk manifest.json 未声明 base");
            }
            List<Path> apks = new ArrayList<>();
            apks.add(copyOut(zip, baseFile, extract));
            for (String f : splitFiles) {
                apks.add(copyOut(zip, f, extract));
            }
            return apks;
        }
    }

    /** .apks（bundletool 容器）：splits/base.master.apk 为主包，其余 splits/*.apk。 */
    private static List<Path> fromApks(Path apks, Path workDir) throws IOException {
        Path extract = Files.createTempDirectory("ratel-apks-");
        try (ZipFile zip = new ZipFile(apks.toFile())) {
            List<Path> out = new ArrayList<>();
            // base 主包：bundletool 命名为 splits/base-master.apk（点号变体兼容）。
            // 不能取首个 base* ——base-<abi>/base-<dpi> 配置包在 zip 里可能排更前
            String baseName = null;
            for (String candidate : new String[]{"splits/base-master.apk", "splits/base.master.apk"}) {
                if (zip.getEntry(candidate) != null) {
                    baseName = candidate;
                    break;
                }
            }
            if (baseName == null) {
                throw new IOException(".apks 中未找到 splits/base-master.apk");
            }
            out.add(copyOut(zip, baseName, extract));
            var e = zip.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                // 条目名比较（ZipEntry 对象每次枚举都是新实例，不能比对象）
                if (n.startsWith("splits/") && n.endsWith(".apk") && !n.equals(baseName)) {
                    out.add(copyOut(zip, n, extract));
                }
            }
            return out;
        }
    }

    private static Path copyOut(ZipFile zip, String entry, Path dir) throws IOException {
        ZipEntry e = zip.getEntry(entry);
        if (e == null) {
            throw new IOException("容器条目缺失: " + entry);
        }
        Path out = dir.resolve(entry.substring(entry.lastIndexOf('/') + 1));
        try (InputStream in = zip.getInputStream(e)) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    private static String extractJsonString(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return null;
        int start = json.indexOf('"', json.indexOf(':', idx) + 1);
        int end = start >= 0 ? json.indexOf('"', start + 1) : -1;
        if (start < 0 || end < 0) return null;
        return json.substring(start + 1, end);
    }
}
