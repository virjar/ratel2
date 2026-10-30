import java.util.Properties

plugins {
    base
}

// native 模块示例：NDK clang 编 so（gradle 化，无 sh）→ 打包 module apk
// （lib/arm64-v8a/libnativemodule.so + assets/native_init，无 java 入口）

fun sdkRoot(): File {
    val lp = rootProject.file("local.properties")
    if (lp.isFile) {
        val dir = Properties().apply { lp.inputStream().use { load(it) } }.getProperty("sdk.dir")
        if (!dir.isNullOrBlank()) return File(dir)
    }
    System.getenv("ANDROID_HOME")?.let { return File(it) }
    return File(System.getProperty("user.home"), "Library/Android/sdk")
}

fun latestDir(parent: File, prefix: String): File? =
    parent.listFiles { f: File -> f.isDirectory && f.name.startsWith(prefix) }
        ?.maxByOrNull { it.name.removePrefix(prefix).takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

val ndkDir = latestDir(File(File(sdkRoot().absolutePath), "ndk"), "") ?: throw GradleException("NDK 未找到")
val prebuilt = File(ndkDir, "toolchains/llvm/prebuilt").listFiles { f: File -> f.isDirectory }?.first()
    ?: throw GradleException("NDK prebuilt 目录未找到")
val clang = File(prebuilt, "bin/aarch64-linux-android29-clang")

val ratelHeader = rootProject.file("module-api/native/ratel/native/ratel_native.h")

val soFile = layout.buildDirectory.file("lib/arm64-v8a/libnativemodule.so")

tasks.register("compileNativeSo", Exec::class) {
    group = "ratel"
    inputs.file("native/nativemodule.c")
    inputs.file(ratelHeader)
    outputs.file(soFile)
    doFirst {
        soFile.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        clang.absolutePath,
        "-shared", "-fPIC", "-O2",
        "-I", "native",
        "-o", soFile.get().asFile.absolutePath,
        "native/nativemodule.c",
        "-llog",
    )
}

// 纯 native 模块 apk（无 dex）：so + assets/native_init
tasks.register("buildModuleApk", Zip::class) {
    group = "ratel"
    dependsOn("compileNativeSo")
    archiveFileName.set("native-module.apk")
    destinationDirectory.set(layout.buildDirectory)
    from(soFile.get().asFile.parentFile) {
        include("*.so")
        into("lib/arm64-v8a")
    }
    from("module-assets")
}
