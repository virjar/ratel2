plugins {
    id("com.android.application")
}

// split 样本宿主：install-time 动态特性 + jniLib → bundletool 产真实 .apks（base + feature + config splits）

android {
    namespace = "com.ratel.splitapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ratel.splitapp"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

}

// bundletool（lib 封装模块）：aab + device-spec → .apks（config splits）
fun aapt2Path(): String {
    val home = System.getenv("ANDROID_HOME")
        ?: File(System.getProperty("user.home"), "Library/Android/sdk").absolutePath
    val bt = File(home, "build-tools").listFiles { f: File -> f.isDirectory }
        ?.maxByOrNull { it.name.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        ?: throw GradleException("build-tools 未找到")
    return File(bt, "aapt2").absolutePath
}

val bundletoolCp = configurations.create("bundletool")
dependencies {
    bundletoolCp("com.android.tools.build:bundletool:1.17.0")
}

tasks.register("buildApks", JavaExec::class) {
    group = "ratel"
    dependsOn("bundleRelease", ":examples:split-apks-tool:jar")
    classpath = bundletoolCp
    classpath(project(":examples:split-apks-tool").layout.buildDirectory.file("libs/split-apks-tool.jar"))
    mainClass.set("ratel.tool.SplitApksBuilder")
    val aab = layout.buildDirectory.file("outputs/bundle/release/split-app-release.aab")
    val out = layout.buildDirectory.file("split-app.apks")
    doFirst {
        setArgs(listOf(
            aab.get().asFile.absolutePath,
            out.get().asFile.absolutePath,
            project.file("device-spec.json").absolutePath,
            aapt2Path(),
        ))
    }
}
