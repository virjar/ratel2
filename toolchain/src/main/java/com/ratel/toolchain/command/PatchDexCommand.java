package com.ratel.toolchain.command;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.ratel.toolchain.compile.AndroidJarLocator;
import com.ratel.toolchain.compile.JavaPatchCompiler;
import com.ratel.toolchain.dex.DepRelocator;
import com.ratel.toolchain.dex.DexStore;
import com.ratel.toolchain.dex.StubBuilder;
import com.ratel.toolchain.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * ratel patchdex —— gradle 工作区的编译产物落 dex（供 app-patch:ratelPatchDex 任务调用）。
 *
 * <p>职责（virjar 定版的分工：gradle 管依赖/编译/导航，ratel 管 stub/relocate/dex/apk 手术）：</p>
 * <ol>
 *   <li>确保 stub jar（种子 = 配置范围 ∪ 源码包名 ∪ 源码 import）；</li>
 *   <li>own classes 打 jar（keep：按原名替换/新增，走遮蔽）；</li>
 *   <li>依赖 jar 收集顶层包 → {@code ratel/repack/<pkg>} 前缀表 → ASM ClassRemapper 统一重定位
 *       （own classes 也过 remapper：类名保留，但其对依赖的引用同步改名）；</li>
 *   <li>d8（min-api 29）→ patch dexes 写入 build/ratel/patch-dexes/。</li>
 * </ol>
 */
@Command(name = "patchdex", description = "gradle 编译产物 + maven 依赖 → 重定位 → patch dex")
public class PatchDexCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "ratel 工程目录")
    Path project;

    @Option(names = {"--classes-dir"}, required = true, description = "gradle compileJava 输出目录")
    Path classesDir;

    @Option(names = {"--dep-jars"}, split = ",", description = "runtimeClasspath 解析出的依赖 jar（逗号分隔）")
    List<Path> depJars;

    @Option(names = {"-o", "--output"}, description = "patch dex 输出目录（默认 build/ratel/patch-dexes）")
    Path output;

    @Override
    public Integer call() throws Exception {
        ProjectLayout layout = ProjectLayout.load(project);
        if (!Files.isDirectory(classesDir)) {
            System.err.println("classes-dir 不存在: " + classesDir);
            return 1;
        }
        Path outDir = output != null ? output : layout.patchDexDir;
        Path androidJar = AndroidJarLocator.locate();

        // 1. stub（种子：源码包名 + import）
        JavaPatchCompiler.ensureStub(layout, layout.patchSourceDir);
        Path stubJar = layout.findStubJar();

        // 2. keep 集合：own classes 的内部名（不 relocate，按原名替换/新增）
        Set<String> keep = new HashSet<>();
        try (Stream<Path> walk = Files.walk(classesDir)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String rel = classesDir.relativize(p).toString().replace('\\', '/');
                keep.add(rel.substring(0, rel.length() - ".class".length()));
            });
        }
        if (keep.isEmpty()) {
            System.err.println("classes-dir 没有 .class 产物");
            return 1;
        }

        // 3. 依赖顶层包 → ratel/repack/&lt;pkg&gt; 前缀表
        Map<String, String> prefixMap = new HashMap<>();
        if (depJars != null) {
            for (Path jar : depJars) {
                if (!Files.isRegularFile(jar)) continue;
                for (String top : DepRelocator.topLevelPackages(jar)) {
                    if (isLegalIdentifier(top)) {
                        prefixMap.putIfAbsent(top, "ratel/repack/" + top);
                    }
                }
            }
        }

        // 4. 重定位：own（keep）+ 各依赖 jar
        List<Path> programJars = new ArrayList<>();
        Path ownJar = DepRelocator.jarClasses(classesDir);
        programJars.add(DepRelocator.relocateJar(ownJar, prefixMap, keep));
        int relocatedJars = 0;
        if (depJars != null) {
            for (Path jar : depJars) {
                if (!Files.isRegularFile(jar)) continue;
                programJars.add(DepRelocator.relocateJar(jar, prefixMap, keep));
                relocatedJars++;
            }
        }

        // 5. d8
        Files.createDirectories(outDir);
        try (Stream<Path> old = Files.list(outDir)) {
            old.filter(p -> p.getFileName().toString().matches("classes\\d*\\.dex"))
                    .forEach(p -> p.toFile().delete());
        }
        try {
            D8Command cmd = D8Command.builder()
                    .addProgramFiles(programJars)
                    .addLibraryFiles(androidJar)
                    .addClasspathFiles(stubJar)
                    .setMinApiLevel(29)
                    .setMode(CompilationMode.RELEASE)
                    .setOutput(outDir, OutputMode.DexIndexed)
                    .build();
            D8.run(cmd);
        } catch (Exception e) {
            System.err.println("d8 失败: " + e.getMessage());
            return 1;
        }

        int dexCount;
        try (Stream<Path> list = Files.list(outDir)) {
            dexCount = (int) list.filter(p -> p.getFileName().toString().endsWith(".dex")).count();
        }
        System.out.println("patch dex 已生成: " + outDir + " (" + dexCount + " 个, own="
                + keep.size() + " 类, 依赖=" + relocatedJars + " jar → relocate " + prefixMap.size() + " 个顶层包)");
        return 0;
    }

    private static boolean isLegalIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }
}
