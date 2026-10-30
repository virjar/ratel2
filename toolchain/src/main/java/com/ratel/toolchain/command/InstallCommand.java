package com.ratel.toolchain.command;

import com.ratel.toolchain.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * ratel install <project> —— 安装 build 产物（adb install-multiple，支持 split 集合）。
 * adb 定位：ANDROID_HOME → 默认 SDK 路径 → PATH。
 */
@Command(name = "install", description = "安装 build 产物（split 集合走 install-multiple）")
public class InstallCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "ratel 工程目录")
    Path project;

    @Option(names = {"-s", "--serial"}, description = "设备序列号（多设备时）")
    String serial;

    @Override
    public Integer call() throws Exception {
        ProjectLayout layout = ProjectLayout.load(project);
        File adb = locateAdb();
        if (adb == null) {
            System.err.println("adb 未找到：设置 ANDROID_HOME 或将 platform-tools 加入 PATH");
            return 1;
        }

        List<String> apks = new ArrayList<>();
        try (var stream = Files.list(layout.outDir)) {
            // base 产物（*-ratel.apk）优先，splits（原名）随后
            stream.filter(p -> p.getFileName().toString().endsWith(".apk"))
                    .filter(p -> !p.getFileName().toString().contains("-unsigned"))
                    .sorted((a, b) -> Boolean.compare(
                            !a.getFileName().toString().contains("-ratel"),
                            !b.getFileName().toString().contains("-ratel")))
                    .forEach(p -> apks.add(p.toAbsolutePath().toString()));
        }
        if (apks.isEmpty()) {
            System.err.println("out/ 无产物，先 ./gradlew ratelBuild");
            return 1;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(adb.getAbsolutePath());
        if (serial != null && !serial.isBlank()) {
            cmd.add("-s");
            cmd.add(serial);
        }
        cmd.add("install-multiple");
        cmd.add("-r");
        cmd.addAll(apks);
        System.out.println(String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        return p.waitFor() == 0 ? 0 : 1;
    }

    private static File locateAdb() {
        String home = System.getenv("ANDROID_HOME");
        if (home == null || home.isBlank()) {
            File mac = new File(System.getProperty("user.home"), "Library/Android/sdk");
            if (mac.isDirectory()) home = mac.getAbsolutePath();
        }
        if (home != null) {
            File adb = new File(home, "platform-tools/adb");
            if (adb.isFile()) return adb;
        }
        // PATH 兜底
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File adb = new File(dir, System.getProperty("os.name").toLowerCase().contains("win")
                        ? "adb.exe" : "adb");
                if (adb.isFile()) return adb;
            }
        }
        return null;
    }
}
