package com.ratel.toolchain.command;

import com.ratel.toolchain.apk.ApkInfo;
import com.ratel.toolchain.dex.DexStore;
import com.ratel.toolchain.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * ratel init app.apk -o myproj/ —— 把 apk 展开为开发者工程。
 * 默认按需反编译（pull），smali/ 与 java/ 初始为空。
 */
@Command(name = "init", description = "把 APK 展开为可移交的 ratel 开发者工程")
public class InitCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "原始 APK 路径（支持 .apk）")
    Path apk;

    @Option(names = {"-o", "--output"}, required = true, description = "工程输出目录")
    Path output;

    @Option(names = {"--entry"}, defaultValue = "clinit",
            description = "入口注入方案：clinit | appComponentFactory | applicationProxy")
    String entryScheme;

    @Option(names = {"--full"}, description = "全量反编译 smali 到工程（默认按需 pull）")
    boolean full;

    @Option(names = {"--unpack"}, description = "检出已处理 APK 时剥离 ratel 注入层，用还原的原始基准 init")
    boolean unpack;

    @Override
    public Integer call() throws Exception {
        if (!Files.isRegularFile(apk)) {
            System.err.println("APK 不存在: " + apk);
            return 1;
        }
        String patchedReason = com.ratel.toolchain.inject.PatchedApkDetector.detect(apk);
        java.util.List<String> carriedCerts = null;
        if (patchedReason != null) {
            if (!unpack) {
                System.err.println(com.ratel.toolchain.inject.PatchedApkDetector.rejectMessage(patchedReason));
                System.err.println("  如确要从产物恢复：ratel init <apk> --unpack -o <dir>");
                return 1;
            }
            Path recovered = Files.createTempFile("ratel-recovered-", ".apk");
            com.ratel.toolchain.inject.ApkOriginRecoverer.Recovered rec =
                    com.ratel.toolchain.inject.ApkOriginRecoverer.recover(apk, recovered);
            System.out.println("检出已处理 APK（" + patchedReason + "），--unpack 还原：");
            System.out.println(com.ratel.toolchain.inject.ApkOriginRecoverer.summary(rec));
            apk = recovered;
            carriedCerts = rec.originCerts;
        }
        if (Files.exists(output) && Files.list(output).findAny().isPresent()) {
            System.err.println("输出目录非空: " + output);
            return 1;
        }

        // origin 输入规范化（单 apk / split 目录 / .xapk / .apks）→ origin-splits/（base + splits）
        java.util.List<Path> originSet =
                com.ratel.toolchain.project.OriginInput.normalize(apk, output.toAbsolutePath().normalize().resolve("origin-splits"));
        apk = originSet.get(0);
        if (originSet.size() > 1) {
            System.out.println("split APK 集合: " + originSet.size() + " 个（base + "
                    + (originSet.size() - 1) + " splits，构建时仅手术 base、其余原样重签）");
        }

        ApkInfo info = ApkInfo.parse(apk.toFile());
        System.out.println("目标 APK: " + info.packageName + " (" + info.label
                + ") v" + info.versionName + " code=" + info.versionCode);

        ProjectLayout layout = new ProjectLayout(output.toAbsolutePath().normalize());
        layout.createDirectories();
        Files.copy(apk, layout.originApk, StandardCopyOption.REPLACE_EXISTING);

        Map<String, Object> config = ProjectLayout.newDefaultConfig(
                info.packageName, info.versionName, info.versionCode);
        ((Map<String, Object>) ((Map<String, Object>) config.get("entry"))).put("scheme", entryScheme);
        layout.writeConfig(config);

        try (DexStore store = new DexStore(layout.originApk)) {
            System.out.println("dex 条目: " + store.dexEntryNames() + "，类总数: " + store.allClassNames().size());
            if (full) {
                System.err.println("提示：--full 全量展开暂未实现（当前请使用按需 ratel pull）");
            }
        }

        // gradle 工作区工程文件（标准 gradle 项目，AS 可打开导航）
        com.ratel.toolchain.project.GradleWorkspaceGenerator.generate(
                layout, info.packageName,
                com.ratel.toolchain.project.GradleWorkspaceGenerator.distLibDir());

        // --unpack 携带的原始证书（还原基准未签名，build 时签名代理从此取原始证书）
        if (carriedCerts != null && !carriedCerts.isEmpty()) {
            Files.write(layout.root.resolve("origin-certs.txt"), carriedCerts);
            System.out.println("原始证书已携带: origin-certs.txt（" + carriedCerts.size() + " 张）");
        }

        // 自动生成开发证书（写回 project.yaml）
        new CertCommand() {{
            this.project = layout.root;
        }}.call();

        System.out.println();
        System.out.println("工程已创建: " + layout.root);
        System.out.println("  下一步（IDE 打开工程即可开发，全程 gradle 管理）：");
        System.out.println("    1. IDEA/Android Studio 打开工程目录");
        System.out.println("    2. 写模块 hook：module/src/main/java/ratel/module/Main.java（模板已可运行）");
        System.out.println("    3. java 混编宿主类：./gradlew ratelPull -Pcls=" + guessMainEntry(info.packageName));
        System.out.println("    4. 出包：./gradlew ratelBuild   （产物 out/，配置改 project.yaml）");
        return 0;
    }

    private static String guessMainEntry(String pkg) {
        return pkg + ".<某个类>";
    }
}
