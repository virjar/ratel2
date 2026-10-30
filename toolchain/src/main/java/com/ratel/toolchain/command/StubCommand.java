package com.ratel.toolchain.command;

import com.ratel.toolchain.compile.JavaPatchCompiler;
import com.ratel.toolchain.dex.DexStore;
import com.ratel.toolchain.dex.StubBuilder;
import com.ratel.toolchain.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ratel stub [-i glob...] —— 从 dex 生成 compileOnly stub jar（ASM 直出）。
 * 种子优先级：-i 参数 &gt; project.yaml stubs.includes &gt; 工作区源码所在包（兜底）。
 * 生成核心（引用闭包）在 {@link StubBuilder}。
 */
@Command(name = "stub", description = "从 APK dex 生成 compileOnly stub jar（java 混编的编译依赖）")
public class StubCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "ratel 工程目录")
    Path project;

    @Option(names = {"-i", "--include"}, arity = "1..*",
            description = "包/类名 glob，如 com.foo.** 或 com.foo.Bar（** 匹配任意后续段）")
    List<String> includes;

    @Override
    public Integer call() throws Exception {
        ProjectLayout layout = ProjectLayout.load(project);

        List<String> scope = includes;
        boolean fromArg = scope != null;
        if (scope == null) {
            Object saved = layout.config().get("stubs");
            if (saved instanceof Map<?, ?> stubsCfg
                    && stubsCfg.get("includes") instanceof List<?> list && !list.isEmpty()) {
                scope = list.stream().map(String::valueOf).collect(Collectors.toList());
            }
        }
        List<Pattern> patterns;
        if (scope != null && !scope.isEmpty()) {
            patterns = scope.stream().map(StubBuilder::globToPattern).collect(Collectors.toList());
        } else {
            patterns = new ArrayList<>(JavaPatchCompiler.sourcePackagePatterns(layout.patchSourceDir));
            if (patterns.isEmpty()) {
                patterns = new ArrayList<>(JavaPatchCompiler.sourcePackagePatterns(layout.javaDir));
            }
            if (patterns.isEmpty()) {
                System.err.println("请传 -i 包名范围（如 -i com.foo.**），或先 pull/放置源码");
                return 2;
            }
        }

        // import 种子：方法体引用的类（Intrinsics 等）不在签名闭包内，必须显式补种——
        // gradle 工作区编译发生在 stub 之后，stub 必须一次到位
        java.util.Set<String> importSeeds = new java.util.HashSet<>();
        JavaPatchCompiler.collectImportsFrom(layout.patchSourceDir, importSeeds);
        JavaPatchCompiler.collectImportsFrom(layout.javaDir, importSeeds);
        java.util.Set<String> extra = new java.util.HashSet<>();
        for (String fqn : importSeeds) {
            extra.add('L' + fqn.replace('.', '/') + ';');
        }

        try (DexStore store = new DexStore(layout.originApk)) {
            long t0 = System.currentTimeMillis();
            Path tmp = java.nio.file.Files.createTempFile("ratel-stub-", ".jar");
            StubBuilder.Stats stats = StubBuilder.build(store, tmp, patterns, extra);
            Path stubJar = layout.writeStubJar(java.nio.file.Files.readAllBytes(tmp));
            java.nio.file.Files.deleteIfExists(tmp);
            if (fromArg) {
                Map<String, Object> config = layout.config();
                @SuppressWarnings("unchecked")
                Map<String, Object> stubs = (Map<String, Object>) config.computeIfAbsent(
                        "stubs", k -> new LinkedHashMap<String, Object>());
                stubs.put("includes", includes);
                layout.writeConfig(config);
            }
            System.out.println("stub jar 已生成(ASM直出): " + stubJar + " (" + (System.currentTimeMillis() - t0) + "ms)");
            System.out.println("  命中类: " + stats.seedCount + "，闭包总数: " + stats.total
                    + "，跳过: " + stats.skipped);
        }
        return 0;
    }
}
