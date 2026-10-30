package com.ratel.toolchain.compile;

import com.ratel.toolchain.dex.DexStore;
import com.ratel.toolchain.dex.StubBuilder;
import com.ratel.toolchain.project.ProjectLayout;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.D8;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * java 混编编译器：工作区 java/ 源码 → (按需再生 stub jar) → javac → d8 → patch dex 字节。
 *
 * <p>源码预处理：剥离 kotlin 元注解（@Metadata/@DebugMetadata）——注解类不在签名闭包内
 * （stub 的 @interface 无成员，无法承载其参数），且混编产物不依赖 kotlin 反射。</p>
 *
 * <p>stub 覆盖：方法体引用的类（如 Intrinsics）不在签名闭包内，编译前扫描全部源码 import
 * 作为额外种子再生 stub jar（1~2s），保证「拉下来的类直接可编译」。</p>
 */
public final class JavaPatchCompiler {

    public static final class Result {
        public final java.util.List<byte[]> patchDexes;
        /** 被替换/新增的全部类描述符（含编译产生的内部类/合成类）。 */
        public final Set<String> patchClassDescriptors;
        public final int sourceCount;

        Result(java.util.List<byte[]> patchDexes, Set<String> patchClassDescriptors, int sourceCount) {
            this.patchDexes = patchDexes;
            this.patchClassDescriptors = patchClassDescriptors;
            this.sourceCount = sourceCount;
        }
    }

    public static Result compile(ProjectLayout layout, Path androidJar) throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(layout.javaDir)) {
            walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .forEach(sources::add);
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("java/ 目录没有 .java 源码");
        }

        // 1. 预处理：剥离 kotlin 元注解，落临时目录（保持相对路径）
        Path processedDir = Files.createTempDirectory("ratel-java-src-");
        Set<String> importSeeds = new HashSet<>();
        for (Path src : sources) {
            String content = Files.readString(src, StandardCharsets.UTF_8);
            collectImports(content, importSeeds);
            content = stripKotlinMetadataAnnotations(content);
            Path out = processedDir.resolve(layout.javaDir.relativize(src).toString());
            Files.createDirectories(out.getParent());
            Files.writeString(out, content, StandardCharsets.UTF_8);
        }

        // 2. 再生 stub jar（配置范围 ∪ 源码包名 + import 种子；与 patchdex 命令共享）
        ensureStub(layout, layout.javaDir);
        Path stubJar = layout.stubsDir.resolve("ratel-stubs.jar");

        // 3. javac
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path classesDir = Files.createTempDirectory("ratel-java-classes-");
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(
                            Files.walk(processedDir)
                                    .filter(p -> p.toString().endsWith(".java"))
                                    .toList());
            List<String> options = List.of(
                    "-source", "8", "-target", "8",
                    "-encoding", "UTF-8",
                    "-nowarn",
                    "-classpath", stubJar + ":" + androidJar,
                    "-d", classesDir.toString());
            boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            if (!ok) {
                StringBuilder sb = new StringBuilder("java 混编源码编译失败（前 30 个错误）：\n");
                int n = 0;
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR && n++ < 30) {
                        sb.append("  ").append(d.getSource() == null ? "?" : d.getSource().getName())
                                .append(':').append(d.getLineNumber()).append(' ')
                                .append(d.getMessage(null)).append('\n');
                    }
                }
                throw new IOException(sb.toString());
            }
        }

        // 4. 产物类清单（含内部类）+ 打 jar
        Set<String> patchClassDescriptors = new HashSet<>();
        try (Stream<Path> walk = Files.walk(classesDir)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String rel = classesDir.relativize(p).toString().replace('\\', '/');
                patchClassDescriptors.add('L' + rel.substring(0, rel.length() - ".class".length()) + ';');
            });
        }
        Path patchJar = Files.createTempFile("ratel-patch-classes", ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(patchJar));
             Stream<Path> walk = Files.walk(classesDir)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try {
                    String rel = classesDir.relativize(p).toString().replace('\\', '/');
                    jos.putNextEntry(new JarEntry(rel));
                    jos.write(Files.readAllBytes(p));
                    jos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        // 5. d8 → patch dex
        Path dexOut = Files.createTempDirectory("ratel-patch-dex-");
        try {
            D8Command cmd = D8Command.builder()
                    .addProgramFiles(patchJar)
                    .addLibraryFiles(androidJar)
                    .addClasspathFiles(stubJar)
                    .setMinApiLevel(29)
                    .setMode(CompilationMode.RELEASE)
                    .setOutput(dexOut, OutputMode.DexIndexed)
                    .build();
            D8.run(cmd);
        } catch (Exception e) {
            throw new IOException("d8 编译 patch 失败: " + e.getMessage(), e);
        }
        java.util.List<byte[]> patchDexes = new ArrayList<>();
        try (var stream = Files.list(dexOut)) {
            stream.filter(f -> f.getFileName().toString().matches("classes\\d*\\.dex"))
                    .sorted()
                    .forEach(f -> {
                        try {
                            patchDexes.add(Files.readAllBytes(f));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        if (patchDexes.isEmpty()) {
            throw new IOException("d8 未产出 classes.dex");
        }
        return new Result(patchDexes, patchClassDescriptors, sources.size());
    }

    /** 收集 import 的完整类 FQN（普通 + static），作为 stub 补种。static 要剥掉最后的成员段。 */
    private static void collectImports(String content, Set<String> out) {
        Matcher m = IMPORT.matcher(content);
        while (m.find()) {
            String target = m.group(2);
            if (m.group(1) != null) {
                int dot = target.lastIndexOf('.');
                if (dot > 0) target = target.substring(0, dot);
            }
            out.add(target);
        }
    }

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    /** 平衡括号剥离 @Metadata(...) / @DebugMetadata(...)。 */
    public static String stripKotlinMetadataAnnotations(String source) {
        StringBuilder sb = new StringBuilder(source);
        for (String name : new String[]{"@Metadata", "@DebugMetadata"}) {
            int idx;
            while ((idx = sb.indexOf(name + "(")) >= 0) {
                int start = idx;
                int depth = 0;
                int i = idx + name.length(); // 指向 '('
                boolean inString = false;
                while (i < sb.length()) {
                    char c = sb.charAt(i);
                    if (inString) {
                        if (c == '\\') {
                            i++; // 跳过转义
                        } else if (c == '"') {
                            inString = false;
                        }
                    } else {
                        if (c == '"') inString = true;
                        else if (c == '(') depth++;
                        else if (c == ')') {
                            depth--;
                            if (depth == 0) break;
                        }
                    }
                    i++;
                }
                // 吃掉前导空白与后续行尾
                int end = Math.min(i + 1, sb.length());
                while (end < sb.length() && (sb.charAt(end) == ' ' || sb.charAt(end) == '\t')) end++;
                if (end < sb.length() && sb.charAt(end) == '\n') end++;
                // 前导空白
                int begin = start;
                while (begin > 0 && (sb.charAt(begin - 1) == ' ' || sb.charAt(begin - 1) == '\t')) begin--;
                if (begin > 0 && sb.charAt(begin - 1) == '\n') begin--; // 让行整体消失
                sb.delete(begin, end);
            }
        }
        return sb.toString();
    }

    /**
     * 确保 stub jar 覆盖当前源码（种子 = 配置 stubs.includes ∪ 源码所在包 glob + 源码 import 精确类）。
     * 供本编译器（legacy java/ 路径）与 PatchDexCommand（gradle app-patch 路径）共用。
     */
    public static void ensureStub(ProjectLayout layout, Path sourceRoot) throws IOException {
        Path stubJar = layout.findStubJar();
        if (stubJar != null) {
            return; // 已有 hash jar（ratelStub 任务或前次生成）——避免每次构建重复生成
        }
        List<Pattern> seedPatterns = new ArrayList<>();
        Object saved = layout.config().get("stubs");
        if (saved instanceof java.util.Map<?, ?> stubsCfg
                && stubsCfg.get("includes") instanceof List<?> includes) {
            for (Object inc : includes) {
                seedPatterns.add(StubBuilder.globToPattern(String.valueOf(inc)));
            }
        }
        if (seedPatterns.isEmpty()) {
            seedPatterns.addAll(sourcePackagePatterns(sourceRoot));
        }
        Set<String> importSeeds = new HashSet<>();
        collectImportsFrom(sourceRoot, importSeeds);
        Set<String> extraSeedDescriptors = new HashSet<>();
        for (String fqn : importSeeds) {
            extraSeedDescriptors.add('L' + fqn.replace('.', '/') + ';');
        }
        try (DexStore store = new DexStore(layout.originApk)) {
            Path tmp = java.nio.file.Files.createTempFile("ratel-stub-", ".jar");
            StubBuilder.Stats stats = StubBuilder.build(store, tmp, seedPatterns, extraSeedDescriptors);
            stubJar = layout.writeStubJar(java.nio.file.Files.readAllBytes(tmp));
            java.nio.file.Files.deleteIfExists(tmp);
            if (Boolean.parseBoolean(System.getenv("RATEL_DEBUG"))) {
                System.err.println("[debug] stub: seed=" + stats.seedCount + " total=" + stats.total
                        + " skipped=" + stats.skipped);
            }
        }
    }

    /** 源码根下出现 .java 的目录推导包名 glob（com/foo -&gt; com.foo.**）。 */
    public static List<Pattern> sourcePackagePatterns(Path sourceRoot) throws IOException {
        List<Pattern> patterns = new ArrayList<>();
        if (!Files.isDirectory(sourceRoot)) return patterns;
        try (var walk = Files.walk(sourceRoot)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                Path dir = sourceRoot.relativize(p).getParent();
                if (dir == null || dir.getNameCount() == 0) return;
                StringBuilder pkg = new StringBuilder();
                for (int i = 0; i < dir.getNameCount(); i++) {
                    if (i > 0) pkg.append('.');
                    pkg.append(dir.getName(i));
                }
                patterns.add(StubBuilder.globToPattern(pkg + ".**"));
            });
        }
        return patterns;
    }

    /** 遍历源码根收集全部 import FQN。 */
    public static void collectImportsFrom(Path sourceRoot, Set<String> out) throws IOException {
        if (!Files.isDirectory(sourceRoot)) return;
        try (var walk = Files.walk(sourceRoot)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    collectImports(Files.readString(p, StandardCharsets.UTF_8), out);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
