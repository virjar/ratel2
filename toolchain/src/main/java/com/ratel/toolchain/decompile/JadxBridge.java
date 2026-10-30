package com.ratel.toolchain.decompile;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * jadx 程序化调用（内嵌 jadx-core，不走 CLI 子进程，规避版本/入口兼容问题）。
 * 单类反编译：加载 apk 的 dex 索引后按类名取源码。
 */
public final class JadxBridge {

    public static String decompileClass(Path apk, String binaryName) throws IOException {
        JadxArgs args = new JadxArgs();
        args.setInputFile(apk.toFile());
        args.setSkipResources(true);
        args.setSkipSources(false);
        // 混编场景必须与字节码严格同名（整类替换后 R8 调用方按原名访问字段/方法，
        // jadx 任何改名都会导致运行时 NoSuchField/MethodError）——禁用全部重命名
        // （case/valid/printable）。名字无法用 java 表达的类由 PullCommand 拦截引导走 smali
        args.setDeobfuscationOn(false);
        args.setRenameFlags(java.util.EnumSet.noneOf(JadxArgs.RenameEnum.class));
        args.setShowInconsistentCode(true);

        try (JadxDecompiler jadx = new JadxDecompiler(args)) {
            jadx.load();
            JavaClass cls = findClass(jadx, binaryName);
            if (cls == null) {
                throw new IllegalArgumentException("class not found by jadx: " + binaryName
                        + "（内部类写 Outer$Inner；若 jadx 反编译失败可用 --smali 走 smali 路线）");
            }
            cls.decompile();
            String code = cls.getCode();
            if (code == null || code.isEmpty()) {
                throw new IOException("jadx 未产出代码: " + binaryName + "（该类可能反编译失败，请用 --smali）");
            }
            return code;
        }
    }

    public static void decompileClassToFile(Path apk, String binaryName, Path outFile) throws IOException {
        String source = decompileClass(apk, binaryName);
        // 拉取即剥离 kotlin 元注解（@Metadata/@DebugMetadata）——工作区源码从源头干净，
        // gradle/CLI 两条编译路径都不再需要源码预处理
        source = com.ratel.toolchain.compile.JavaPatchCompiler.stripKotlinMetadataAnnotations(source);
        Files.createDirectories(outFile.getParent());
        Files.write(outFile, source.getBytes(StandardCharsets.UTF_8));
    }

    /** jadx 内部类命名是 Outer.Inner，dex 是 Outer$Inner，两种都试。 */
    private static JavaClass findClass(JadxDecompiler jadx, String binaryName) {
        String dotted = binaryName.replace('$', '.');
        for (JavaClass jc : jadx.getClasses()) {
            if (binaryName.equals(jc.getRawName())
                    || dotted.equals(jc.getFullName())
                    || binaryName.equals(jc.getFullName())) {
                return jc;
            }
        }
        return null;
    }
}
