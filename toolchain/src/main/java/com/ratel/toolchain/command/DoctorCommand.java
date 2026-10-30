package com.ratel.toolchain.command;

import com.ratel.toolchain.compile.AndroidJarLocator;
import picocli.CommandLine.Command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** 环境自检：JDK 编译器、android.jar、keytool。 */
@Command(name = "doctor", description = "环境自检")
public class DoctorCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("java.home        : " + System.getProperty("java.home"));
        System.out.println("java.version     : " + System.getProperty("java.version"));

        System.out.print("javax.compiler   : ");
        System.out.println(javax.tools.ToolProvider.getSystemJavaCompiler() != null
                ? "OK" : "缺失（需要完整 JDK）");

        System.out.print("android.jar      : ");
        try {
            System.out.println(AndroidJarLocator.locate());
        } catch (Exception e) {
            System.out.println("未找到（设置 ANDROID_HOME；stub/混编编译需要）");
        }

        System.out.print("keytool          : ");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        System.out.println(java.nio.file.Files.isRegularFile(keytool) ? keytool : "未找到");

        System.out.print("jadx             : ");
        try {
            Class.forName("jadx.api.JadxDecompiler", false, getClass().getClassLoader());
            System.out.println("内嵌 OK");
        } catch (ClassNotFoundException e) {
            System.out.println("classpath 中不可见（打包发布时内嵌）");
        }
        return 0;
    }
}
