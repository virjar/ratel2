package com.ratel.toolchain.command;

import com.ratel.toolchain.apk.IncrementalZipPacker;
import com.ratel.toolchain.project.ProjectLayout;
import com.ratel.toolchain.project.OriginInput;
import com.ratel.toolchain.sign.Signer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * ratel build —— 工程重建为 APK。
 *
 * <p>构建管线（origin.apk 全条目 raw-copy + V2/V3 重签名为基础）：</p>
 * <ol>
 *   <li>java 混编 patch dex + smali 补丁 dex（gradle 任务链产出，见 app-patch/）</li>
 *   <li>dex 手术（改名遮蔽：patch dex 占位 classes.dex，原 dex 序数后移）</li>
 *   <li>入口注入（clinit / ACF）与 runtime dex/so、模块、配置 assets 注入</li>
 *   <li>crcBypass（可选）：origin.apk 内嵌</li>
 *   <li>split 集合重签（install-multiple 安装）</li>
 * </ol>
 */
@Command(name = "build", description = "重建工程为 APK（增量 zip + 签名）")
public class BuildCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "ratel 工程目录")
    Path project;

    @Option(names = {"-o", "--output"}, description = "输出 apk 路径（默认 out/&lt;pkg&gt;-ratel.apk）")
    Path output;

    @Option(names = {"--no-sign"}, description = "跳过签名，产出未签名包")
    boolean noSign;

    @Override
    public Integer call() throws Exception {
        ProjectLayout layout = ProjectLayout.load(project);

        String patchedReason = com.ratel.toolchain.inject.PatchedApkDetector.detect(layout.originApk);
        if (patchedReason != null) {
            System.err.println("origin.apk 异常：\n"
                    + com.ratel.toolchain.inject.PatchedApkDetector.rejectMessage(patchedReason));
            return 1;
        }

        Map<String, Object> config = layout.config();
        String pkg = String.valueOf(config.get("package"));
        String versionName = String.valueOf(config.get("versionName"));

        Path unsigned = layout.outDir.resolve(pkg + "-ratel-unsigned.apk");
        Files.createDirectories(layout.outDir);

        long start = System.currentTimeMillis();

        // zip 条目覆盖集（入口注入/runtime/模块等构建期注入使用）。
        // 注：不做用户级文件替换（files/）——dex 有混编/smali 两条正路（直塞 dex 会绕过
        // 遮蔽管线），so 的正确姿势是 native hook，axml/资源不开放，assets 影响面小。
        Map<String, byte[]> overrides = new java.util.LinkedHashMap<>();

        // ---- front dexes 组装（改名遮蔽）：用户 patch + 入口注入 + loader ----
        java.util.List<byte[]> frontDexes = new java.util.ArrayList<>();
        java.util.Set<String> frontDescriptors = new java.util.HashSet<>();
        String patchSource = null;

        // 1. 用户 patch（A. gradle 工作区产物 B. legacy java/ 内部编译）
        com.ratel.toolchain.dex.DexSurgery.LoadedPatch gradlePatch =
                java.nio.file.Files.isDirectory(layout.patchDexDir)
                        ? com.ratel.toolchain.dex.DexSurgery.loadPatchDexes(layout.patchDexDir) : null;
        if (gradlePatch != null) {
            frontDexes.addAll(gradlePatch.dexes);
            frontDescriptors.addAll(gradlePatch.classDescriptors);
            patchSource = "gradle";
        } else {
            boolean hasJava = false;
            if (java.nio.file.Files.isDirectory(layout.javaDir)) {
                try (var walk = java.nio.file.Files.walk(layout.javaDir)) {
                    hasJava = walk.anyMatch(p -> p.toString().endsWith(".java"));
                }
            }
            if (hasJava) {
                Path androidJar = com.ratel.toolchain.compile.AndroidJarLocator.locate();
                com.ratel.toolchain.compile.JavaPatchCompiler.Result patch =
                        com.ratel.toolchain.compile.JavaPatchCompiler.compile(layout, androidJar);
                frontDexes.addAll(patch.patchDexes);
                frontDescriptors.addAll(patch.patchClassDescriptors);
                patchSource = "legacy/" + patch.sourceCount + "源文件";
            }
        }

        // 1.5 smali 工作区回编（独立于 java 路径，两者可共存）
        if (java.nio.file.Files.isDirectory(layout.smaliDir)) {
            com.ratel.toolchain.dex.SmaliPatchAssembler.Result smali =
                    com.ratel.toolchain.dex.SmaliPatchAssembler.assemble(layout.smaliDir);
            if (smali != null) {
                frontDexes.add(smali.dexBytes);
                frontDescriptors.addAll(smali.classDescriptors);
                patchSource = (patchSource != null ? patchSource + " + " : "") + "smali/" + smali.fileCount + "文件";
            }
        }

        // 2. 入口注入（clinit/ACF）+ loader dex + 运行时配置
        String entryScheme = layout.configString("entry.scheme", "clinit");
        boolean injected = false;

        // dev 插件包名（单模块模型，公共作用域：clinit 与 ACF 共用）
        String devPackage = null;
        boolean nativeHook = true;
        {
            Object nv = ((Map<String, Object>) layout.config()
                    .computeIfAbsent("modules", k -> new java.util.LinkedHashMap<String, Object>()))
                    .get("native");
            if (nv instanceof Boolean b) {
                nativeHook = b;
            }
        }
        {
            Object dev = ((Map<String, Object>) layout.config()
                    .computeIfAbsent("modules", k -> new java.util.LinkedHashMap<String, Object>()))
                    .get("dev");
            if (dev != null && !String.valueOf(dev).isBlank()) {
                devPackage = String.valueOf(dev);
            }
        }
        if ("acf".equals(entryScheme)) {
            // ACF 方案：manifest 改写 appComponentFactory → RatelAppComponentFactory（loader 内已含）
            byte[] loaderDex = readResource("/ratel/runtime.dex");
            if (loaderDex == null) {
                System.err.println("[entry] runtime.dex 资源缺失，跳过注入");
            } else {
                String originAcf = com.ratel.toolchain.inject.ManifestParser
                        .appComponentFactoryName(layout.originApk);
                byte[] edited = editManifestForAcf(layout.originApk);
                if (edited != null) {
                    overrides.put("AndroidManifest.xml", edited);
                    frontDexes.add(0, loaderDex);
                    frontDescriptors.addAll(com.ratel.toolchain.dex.DexSurgery.collectDescriptors(loaderDex));
                    java.util.List<String> certs = extractOriginCerts(layout);
                    overrides.put("assets/ratel/ratel-config.json",
                            buildRuntimeConfigJson(certs, entryScheme, devPackage, nativeHook).getBytes());
                    injected = true;
                    System.out.println("入口注入: ACF 方案"
                            + (originAcf != null ? "（原 ACF: " + originAcf + " → 链式委托）" : "（无原 ACF）"));
                }
            }
        } else if ("clinit".equals(entryScheme)) {
            String appName = com.ratel.toolchain.inject.ManifestParser
                    .applicationClassName(layout.originApk, pkg);
            String targetKind = "application";
            if (appName == null) {
                // Compose 新项目常态：无自定义 application → 注入 launcher activity 的 clinit
                appName = com.ratel.toolchain.inject.ManifestParser
                        .launcherActivityName(layout.originApk, pkg);
                targetKind = "launcher-activity";
            }
            if (appName == null) {
                System.err.println("[entry] 找不到 application/launcher activity，本次跳过注入");
            } else {
                byte[] loaderDex = readResource("/ratel/runtime.dex");
                if (loaderDex == null) {
                    System.err.println("[entry] runtime.dex 资源缺失（先跑 script/build-loader.sh），跳过注入");
                } else {
                    byte[] injectedDex = com.ratel.toolchain.inject.EntryInjector
                            .injectClinit(layout.originApk, appName);
                    frontDexes.add(0, loaderDex);   // loader 物理最前
                    frontDexes.add(injectedDex);
                    frontDescriptors.addAll(com.ratel.toolchain.dex.DexSurgery.collectDescriptors(loaderDex));
                    frontDescriptors.addAll(com.ratel.toolchain.dex.DexSurgery.collectDescriptors(injectedDex));

                    // 开发态插件（按包名直查已装 apk）：注入 QUERY_ALL_PACKAGES（包可见性），
                    // 包名列表下发到 ratel-config.json；定向 <queries> 待 manifest editor 扩展
                    if (devPackage != null) {
                        byte[] edited = editManifestForPackageVisibility(layout.originApk,
                                java.util.List.of(devPackage));
                        if (edited != null) {
                            overrides.put("AndroidManifest.xml", edited);
                            System.out.println("manifest 已定向注入 <queries>（dev 模块: " + devPackage + "）");
                        }
                    }

                    java.util.List<String> certs = extractOriginCerts(layout);
                    String configJson = buildRuntimeConfigJson(certs, entryScheme, devPackage, nativeHook);
                    overrides.put("assets/ratel/ratel-config.json",
                            configJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                    // native hook 运行时 so（按 abi 注入 assets，运行时释放到 filesDir 再 System.load）
                    for (String abi : new String[]{"arm64-v8a", "x86_64"}) {
                        byte[] so = readResource("/ratel/lib/" + abi + "/libratel.so");
                        if (so != null) {
                            overrides.put("assets/ratel/lib/" + abi + "/libratel.so", so);
                        } else {
                            System.err.println("[entry] libratel.so[" + abi + "] 资源缺失，hook 运行时不可用");
                        }
                    }

                    // crcBypass：原始 apk 全量内嵌（运行时释放 + fd 重定向，包体积接近翻倍）
                    boolean crcBypass = Boolean.parseBoolean(
                            String.valueOf(layout.config().getOrDefault("crcBypass", "false")));
                    if (crcBypass) {
                        overrides.put("assets/ratel/origin.apk",
                                Files.readAllBytes(layout.originApk));
                        System.out.println("crcBypass: origin.apk 已内嵌（"
                                + Files.size(layout.originApk) / 1024 + "KB）");
                    }

                    // 内嵌模块（project.yaml modules.embed，单模块模型：固定 asset 名）
                    Object embed = ((Map<String, Object>) layout.config()
                            .computeIfAbsent("modules", k -> new java.util.LinkedHashMap<String, Object>()))
                            .get("embed");
                    if (embed != null && !String.valueOf(embed).isBlank()) {
                        Path mp = layout.root.resolve(String.valueOf(embed));
                        if (!Files.isRegularFile(mp)) {
                            System.err.println("[modules] 内嵌模块缺失: " + embed);
                        } else {
                            overrides.put("assets/ratel/module.apk", Files.readAllBytes(mp));
                            System.out.println("内嵌模块: " + mp.getFileName());
                        }
                    }
                    injected = true;
                    System.out.println("入口注入: " + appName + " (clinit/" + targetKind + ") + runtime.dex + 签名代理(" + certs.size() + " 证书)");
                }
            }
        }

        // 3. 统一遮蔽合并
        java.util.Map<String, String> dexRenames = java.util.Map.of();
        if (!frontDexes.isEmpty()) {
            com.ratel.toolchain.dex.DexSurgery.Result surgery =
                    com.ratel.toolchain.dex.DexSurgery.merge(layout.originApk, frontDexes, frontDescriptors);
            overrides.putAll(surgery.overrides);
            dexRenames = surgery.renames;
            System.out.println("dex 遮蔽: front dex " + frontDexes.size() + " 个"
                    + (patchSource != null ? "（patch: " + patchSource + "）" : "")
                    + (injected ? "（含入口注入）" : "")
                    + " → 替换 " + surgery.replacedCount + " 类, 新增 " + surgery.addedCount
                    + " 类, 原 dex 改名 " + surgery.renames.size() + " 个");
        }

        new IncrementalZipPacker(overrides, java.util.Set.of(), dexRenames).rebuild(layout.originApk, unsigned);
        System.out.println("zip 重建完成: " + unsigned + " (" + (System.currentTimeMillis() - start) + "ms)");

        if (noSign) {
            System.out.println("按 --no-sign 跳过签名");
            return 0;
        }

        String keystoreRel = layout.configString("signing.keystore", "");
        if (keystoreRel.isEmpty() || !Files.isRegularFile(layout.root.resolve(keystoreRel))) {
            System.out.println("证书缺失，自动生成...");
            new CertCommand() {{
                this.project = layout.root;
            }}.call();
            keystoreRel = layout.configString("signing.keystore", "");
        }
        String storePassword = layout.configString("signing.storePassword", "");
        String alias = layout.configString("signing.alias", "ratel");

        Path signed = output != null ? output
                : layout.outDir.resolve(pkg + "-v" + versionName + "-ratel.apk");
        Signer.sign(unsigned, signed, layout.root.resolve(keystoreRel), storePassword, alias);
        Signer.verify(signed.toFile());
        System.out.println("签名完成并通过校验: " + signed);

        // split 集合：其余 split 原样重签（同一密钥，install-multiple 要求全集合一致签名）
        int splitCount = 0;
        try (var stream = Files.isDirectory(layout.splitsDir)
                ? Files.list(layout.splitsDir) : java.util.stream.Stream.<Path>empty()) {
            for (Path split : stream.filter(p -> !p.getFileName().toString().equals(OriginInput.BASE_NAME))
                    .filter(p -> p.getFileName().toString().endsWith(".apk")).toList()) {
                Path signedSplit = layout.outDir.resolve(split.getFileName().toString());
                Signer.sign(split, signedSplit, layout.root.resolve(keystoreRel), storePassword, alias);
                splitCount++;
            }
        }
        if (splitCount > 0) {
            System.out.println("splits 已重签: " + splitCount + " 个 → " + layout.outDir);
            System.out.println("安装: adb install-multiple -r " + signed.getFileName()
                    + " " + "<splits...>   （或 ratel install " + layout.root + "）");
        }

        // 构建后非阻塞校验（advisory——错误不中止构建但打印提示）
        if (!frontDexes.isEmpty()) {
            try {
                var issues = frontDexes.size() > 2 ? com.ratel.toolchain.dex.DexVerifier.verify(frontDexes.get(frontDexes.size()-1), layout.originApk) : java.util.List.of();
                int errors = (int) issues.stream()
                        .filter(i -> i.toString().startsWith("ERROR")).count();
                if (errors > 0) {
                    System.out.println("[post-verify] " + errors + " 个潜在错误（不阻塞，运行时可能 crash）：");
                    issues.stream().filter(i -> i.toString().startsWith("ERROR"))
                            .limit(5).forEach(i -> System.out.println("  " + i));
                }
            } catch (Throwable t) {
                // 静默：post-verify 是 advisory，不影响构建
            }
        }
        return 0;
    }

    private static byte[] readResource(String path) throws java.io.IOException {
        try (var in = BuildCommand.class.getResourceAsStream(path)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** 原始证书：优先从 origin.apk 签名提取；--unpack 还原基准未签名，回退携带文件。 */
    private static java.util.List<String> extractOriginCerts(ProjectLayout layout) throws Exception {
        try {
            return com.ratel.toolchain.inject.OriginSignatureExtractor.extractBase64(layout.originApk);
        } catch (Exception e) {
            java.nio.file.Path carried = layout.root.resolve("origin-certs.txt");
            if (java.nio.file.Files.isRegularFile(carried)) {
                java.util.List<String> certs = java.nio.file.Files.readAllLines(carried)
                        .stream().map(String::trim).filter(l -> !l.isEmpty()).toList();
                System.out.println("origin.apk 未签名，使用携带证书: origin-certs.txt（" + certs.size() + " 张）");
                return certs;
            }
            // bundle 直出的未签名 origin（无"原始证书"概念）：跳过签名代理
            System.out.println("origin.apk 未签名（bundle 直出场景），跳过签名代理");
            return java.util.List.of();
        }
    }

    /** 运行时配置 json（loader 读取；SignatureProxy 手写解析，保持简单格式）。 */
    private static String buildRuntimeConfigJson(java.util.List<String> certs, String scheme,
                                                 String devPackage, boolean nativeHook) {
        if (certs.isEmpty()) {
            // 未签名 origin：无证书可代理，runtime 跳过签名还原
            System.out.println("[entry] 无原始证书，签名代理不启用");
        }
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"scheme\": \"").append(scheme).append("\",\n");
        sb.append("  \"nativeHook\": ").append(nativeHook).append(",\n");
        if (devPackage != null) {
            sb.append("  \"devPackage\": \"").append(devPackage).append("\",\n");
        }
        sb.append("  \"originCerts\": [");
        for (int i = 0; i < certs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\n    \"").append(certs.get(i)).append("\"");
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    /** ACF 方案 manifest 改写：appComponentFactory → RatelAppComponentFactory。 */
    private static byte[] editManifestForAcf(Path originApk) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(originApk.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) return null;
            byte[] manifest;
            try (java.io.InputStream in = zip.getInputStream(entry);
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                manifest = bos.toByteArray();
            }
            org.jf.pxb.android.axml.AxmlReader rd = new org.jf.pxb.android.axml.AxmlReader(manifest);
            org.jf.pxb.android.axml.AxmlWriter wr = new org.jf.pxb.android.axml.AxmlWriter();
            org.jf.pxb.android.axml.AxmlVisitor visitor =
                    new com.ratel.toolchain.inject.ReplaceAppComponentFactory(wr);
            rd.accept(visitor);
            return wr.toByteArray();
        } catch (Exception e) {
            System.err.println("ACF manifest edit failed: " + e);
            return null;
        }
    }

    /**
     * manifest 定向注入 &lt;queries&gt;（dev 插件包可见性，ratel 1.x AddQueriesForPackage 直系实现，
     * 比 QUERY_ALL_PACKAGES 更小化——每个 dev 插件包名一条，已存在 queries 标签则合并）。
     */
    private static byte[] editManifestForPackageVisibility(Path originApk,
                                                           java.util.List<String> devPackages) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(originApk.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) {
                return null;
            }
            byte[] manifest;
            try (java.io.InputStream in = zip.getInputStream(entry);
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                manifest = bos.toByteArray();
            }
            org.jf.pxb.android.axml.AxmlReader rd = new org.jf.pxb.android.axml.AxmlReader(manifest);
            org.jf.pxb.android.axml.AxmlWriter wr = new org.jf.pxb.android.axml.AxmlWriter();
            org.jf.pxb.android.axml.AxmlVisitor visitor =
                    new com.ratel.toolchain.inject.AddQueriesForPackage(wr, new java.util.HashSet<>(devPackages));
            rd.accept(visitor);
            return wr.toByteArray();
        } catch (Exception e) {
            System.err.println("manifest 编辑失败: " + e);
            return null;
        }
    }
}
