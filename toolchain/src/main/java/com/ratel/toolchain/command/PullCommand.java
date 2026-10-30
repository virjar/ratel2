package com.ratel.toolchain.command;

import com.ratel.toolchain.decompile.JadxBridge;
import com.ratel.toolchain.dex.DexStore;
import com.ratel.toolchain.dex.SmaliPuller;
import com.ratel.toolchain.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * ratel pull com.foo.Bar --java|--smali —— 按需反编译单个类到工程工作区。
 * 内部类提示：dex 二进制名为 Outer$Inner。
 */
@Command(name = "pull", description = "按需反编译单个类（java 或 smali）到工程")
public class PullCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "ratel 工程目录")
    Path project;

    @Parameters(index = "1", description = "类的二进制名，如 com.foo.Bar / com.foo.Bar$Inner")
    String className;

    @Option(names = {"--java"}, description = "反编译为 java（jadx）")
    boolean java;

    @Option(names = {"--smali"}, description = "反编译为 smali（baksmali）")
    boolean smali;

    @Option(names = {"--stdout"}, description = "输出到终端而不写文件")
    boolean stdout;

    @Override
    public Integer call() throws Exception {
        if (java == smali) {
            System.err.println("必须二选一：--java 或 --smali");
            return 2;
        }
        if (java && hasJavaInexpressibleName(className)) {
            // R8 混淆的关键字包/非法标识符（if.f 等）java 语言无法表达，只能走 smali
            // （smali 与字节码完全对齐）——直接拦截，避免产出必然编译不过的源码
            System.err.println("该类名含 java 无法表达的标识符段（关键字/非法字符），java 混编不可用: " + className);
            System.err.println("请改用 --smali");
            return 3;
        }
        ProjectLayout layout = ProjectLayout.load(project);

        try (DexStore store = new DexStore(layout.originApk)) {
            DexStore.LocatedClass located = store.findClass(className);
            if (located == null) {
                System.err.println("类不存在: " + className + "（内部类用 Outer$Inner 写法）");
                return 1;
            }
            if (smali) {
                if (stdout) {
                    System.out.println(SmaliPuller.disassembleToString(located));
                } else {
                    Path out = SmaliPuller.pullToProject(located, layout.smaliDir);
                    System.out.println("smali 已拉取: " + out + "  (来源 dex: " + located.dexEntryName + ")");
                }
            } else {
                Path out = layout.patchSourceDir.resolve(className.replace('.', '/') + ".java");
                if (stdout) {
                    System.out.println(JadxBridge.decompileClass(layout.originApk, className));
                } else {
                    JadxBridge.decompileClassToFile(layout.originApk, className, out);
                    System.out.println("java 已拉取: " + out);
                    System.out.println("注意：jadx 输出未必可直接编译。编辑后 ratel build 会在编译期报错并定位问题。");
                }
            }
        }
        return 0;
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "record", "sealed", "permits", "var", "yield");

    /** 类二进制名的任一 '.' 段是关键字/非法标识符（如 if.f、a.new），java 语言无法表达。 */
    static boolean hasJavaInexpressibleName(String binaryName) {
        for (String seg : binaryName.split("\\.", -1)) {
            if (seg.isEmpty() || !Character.isJavaIdentifierStart(seg.charAt(0))) return true;
            for (int i = 1; i < seg.length(); i++) {
                if (!Character.isJavaIdentifierPart(seg.charAt(i))) return true;
            }
            if (JAVA_KEYWORDS.contains(seg)) return true;
        }
        return false;
    }
}
