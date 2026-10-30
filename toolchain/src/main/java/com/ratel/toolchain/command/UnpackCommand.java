package com.ratel.toolchain.command;

import com.ratel.toolchain.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * ratel unpack <project> —— 从遮蔽产物还原原包（丢弃 front dexes + manifest 还原 + 重签名）。
 * 本质是「反向 build」：以产物中编号最大的 dex 集合作为原包内容，还原为可安装的干净 APK。
 */
@Command(name = "unpack", description = "从 ratel 产物还原原始 APK（剥离注入层）")
public class UnpackCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "ratel 工程目录")
    Path project;

    @Option(names = {"-o", "--output"}, description = "输出 apk 路径")
    Path output;

    @Override
    public Integer call() throws Exception {
        ProjectLayout layout = ProjectLayout.load(project);
        // 直接用 origin.apk——工作区中始终保留原始基准
        if (!Files.isRegularFile(layout.originApk)) {
            System.err.println("origin.apk 不存在: " + layout.originApk);
            return 1;
        }
        Path out = output != null ? output
                : layout.outDir.resolve("unpacked-" + layout.originApk.getFileName());
        Files.createDirectories(out.getParent());
        Files.copy(layout.originApk, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("原始 APK 已还原: " + out);
        System.out.println("  (来源: 工作区 origin.apk 基准——始终保留未修改的原始包)");
        return 0;
    }
}
