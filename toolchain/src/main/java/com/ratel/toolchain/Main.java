package com.ratel.toolchain;

import com.ratel.toolchain.command.BuildCommand;
import com.ratel.toolchain.command.CertCommand;
import com.ratel.toolchain.command.DoctorCommand;
import com.ratel.toolchain.command.InitCommand;
import com.ratel.toolchain.command.InstallCommand;
import com.ratel.toolchain.command.HookableCommand;
import com.ratel.toolchain.command.UnpackCommand;
import com.ratel.toolchain.command.PatchDexCommand;
import com.ratel.toolchain.command.PullCommand;
import com.ratel.toolchain.command.StubCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * ratel 2.0 工具链入口（新一代 ratel：PC 端 APK 工程化二开工具链）。
 *
 * 产品形态对标 apktool：apk 展开为可移交的开发者工程，编辑后增量重建、注入、重签名。
 * 设计文档见仓库根 AGENTS.md / docs/。
 */
@Command(
        name = "ratel",
        mixinStandardHelpOptions = true,
        version = "ratel 2.0",
        description = "PC 端 APK 二次开发工具链：反编译 / java 混编 / smali 重编 / 重打包 / 注入 / 签名",
        subcommands = {
                InitCommand.class,
                BuildCommand.class,
                PullCommand.class,
                StubCommand.class,
                PatchDexCommand.class,
                CertCommand.class,
                HookableCommand.class,
                UnpackCommand.class,
                InstallCommand.class,
                DoctorCommand.class,
        })
public class Main implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }
}
