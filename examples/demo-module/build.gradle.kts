import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.Properties

plugins {
    java
}

// demo 内嵌模块（zip 容器：classes.dex + assets/xposed_init）
// 产物 build/demo-module.apk → 放入工作区根 + project.yaml modules.embed 引用

val isWindows = System.getProperty("os.name").lowercase().contains("win")

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

val androidJar = latestDir(File(sdkRoot(), "platforms"), "android-")?.resolve("android.jar")
    ?: throw GradleException("android.jar 未找到")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    compileOnly(files(androidJar))
    // 剔除 h 桥的 SDK jar（桥在运行时由 runtime dex 提供，开发侧不可见）
    compileOnly(project(mapOf("path" to ":module-api:api", "configuration" to "sdk")))
    // 语义门面随模块 dex 分发（参与模块混淆）
    implementation(project(":module-api:plugin-base"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

val d8Classpath = configurations.create("d8tool")
dependencies {
    d8Classpath("com.android.tools:r8:8.13.25")
}

val pluginBaseJar = project(":module-api:plugin-base").layout.buildDirectory
    .file("dist-sdk/ratel/sdk/plugin-base.jar")

val moduleDex = tasks.register<JavaExec>("moduleDex") {
    group = "ratel"
    dependsOn("classes")
    dependsOn(":module-api:plugin-base:jar")
    classpath = d8Classpath
    mainClass.set("com.android.tools.r8.D8")
    args(
        "--release", "--lib", androidJar.absolutePath, "--min-api", "29",
        "--output", layout.buildDirectory.dir("pkg").get().asFile.absolutePath,
        // 模块自身类 + plugin-base 门面类一起 dex（api 类由宿主 runtime dex 提供，不打进来）
        layout.buildDirectory.file("module-classes.jar").get().asFile.absolutePath,
        pluginBaseJar.get().asFile.absolutePath,
    )
    doFirst {
        val pkgDir0 = layout.buildDirectory.dir("pkg").get().asFile
        if (!pkgDir0.isDirectory) pkgDir0.mkdirs()
        val jarFile = layout.buildDirectory.file("module-classes.jar").get().asFile
        jarFile.parentFile.mkdirs()
        // 打 javac 产物 jar（d8 输入）
        JarOutputStream(jarFile.outputStream()).use { jos ->
            sourceSets.main.get().output.classesDirs.forEach { dir ->
                dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
                    val entry = JarEntry(dir.toPath().relativize(f.toPath()).toString())
                    jos.putNextEntry(entry)
                    f.inputStream().use { it.copyTo(jos) }
                    jos.closeEntry()
                }
            }
        }
    }
}

tasks.register<Zip>("buildModuleApk") {
    group = "ratel"
    dependsOn(moduleDex)
    archiveFileName.set("demo-module.apk")
    destinationDirectory.set(layout.buildDirectory)
    from(layout.buildDirectory.dir("pkg")) {
        include("classes.dex")
    }
    from("module-assets")
}
