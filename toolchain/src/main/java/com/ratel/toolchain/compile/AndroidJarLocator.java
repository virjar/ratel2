package com.ratel.toolchain.compile;

import java.nio.file.Files;
import java.nio.file.Path;

/** 定位 android.jar（stub 编译与后续 java 混编的 classpath 基座）。 */
public final class AndroidJarLocator {

    public static Path locate() {
        // 1. 环境变量显式指定
        String env = System.getenv("ANDROID_HOME");
        if (env != null) {
            Path p = pick(new Path[]{Path.of(env, "platforms")});
            if (p != null) return p;
        }
        // 2. macOS / Linux / Windows 常规 SDK 位置
        String home = System.getProperty("user.home");
        Path[] candidates = new Path[]{
                Path.of(home, "Library/Android/sdk/platforms"),
                Path.of(home, "/Android/Sdk/platforms"),
                Path.of(home, "/opt/android-sdk/platforms"),
        };
        Path p = pick(candidates);
        if (p != null) return p;
        throw new IllegalStateException("android.jar 未找到：请设置 ANDROID_HOME 或安装 Android SDK（platforms/android-35+）");
    }

    private static Path pick(Path[] platformRoots) {
        Path best = null;
        int bestApi = 0;
        for (Path root : platformRoots) {
            if (!Files.isDirectory(root)) continue;
            try (var stream = Files.list(root)) {
                for (Path dir : (Iterable<Path>) stream::iterator) {
                    String name = dir.getFileName().toString();
                    if (!name.startsWith("android-")) continue;
                    // android-36.1 这类带小数版本取主版本号
                    String numPart = name.substring("android-".length());
                    int dot = numPart.indexOf('.');
                    int api;
                    try {
                        api = Integer.parseInt(dot < 0 ? numPart : numPart.substring(0, dot));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    Path jar = dir.resolve("android.jar");
                    if (Files.isRegularFile(jar) && api > bestApi) {
                        best = jar;
                        bestApi = api;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }
}
