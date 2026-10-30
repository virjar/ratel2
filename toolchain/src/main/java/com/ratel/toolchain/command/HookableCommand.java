package com.ratel.toolchain.command;

import com.ratel.toolchain.dex.HookableInjector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * ratel hookable <project> <Class#method> —— 在目标方法字节码开头注入显式 hook 分发点。
 *
 * <p>解决短函数被内联导致 LSPlant hook 不生效的问题（重打包模式下无法控制
 * dex2oat/JIT 行为，不同于 LSPosed root 模式）。</p>
 */
@Command(name = "hookable", description = "在目标方法注入静态 hook 点（解决短函数内联不可 hook）")
public class HookableCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "ratel 工程目录")
    String project;

    @Parameters(index = "1", description = "目标方法，格式 ClassName#methodName")
    String methodKey;

    @Override
    public Integer call() throws Exception {
        var layout = com.ratel.toolchain.project.ProjectLayout.load(java.nio.file.Path.of(project));
        byte[] dex = HookableInjector.inject(layout.originApk, methodKey);
        // 落到工作区 smali 目录（走 build 管线的遮蔽合并）
        java.nio.file.Path out = layout.smaliDir.resolve(
                methodKey.substring(0, methodKey.indexOf('#')).replace('.', '/') + ".hookable.dex");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.write(out, dex);
        System.out.println("hook 点已注入: " + methodKey);
        System.out.println("  产物: " + out + " (" + dex.length + " bytes)");
        System.out.println("  机制: 方法开头插入 invoke-static g.a(String)（静态分发点）");
        System.out.println("  下一步: ratel build → 在模块中 Ratel.hookableDispatch（plugin-base 门面） 实现拦截");
        return 0;
    }
}
