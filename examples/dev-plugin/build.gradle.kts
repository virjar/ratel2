import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.Properties

plugins {
    java
}

// dev 插件样板（可安装 apk：aapt2 manifest + classes.dex + assets/xposed_init + apksigner 签名）
// 产物 build/dev-plugin.apk → adb install 后宿主按包名直查加载（更新插件不动宿主）

val isWindows = System.getProperty("os.name").lowercase().contains("win")
fun exe(name: String) = if (isWindows) "$name.exe" else name
fun bat(name: String) = if (isWindows) "$name.bat" else name

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

val sdk = sdkRoot()
val androidJar = latestDir(File(sdk, "platforms"), "android-")?.resolve("android.jar")
    ?: throw GradleException("android.jar 未找到")
val buildTools = latestDir(File(sdk, "build-tools"), "")
    ?: throw GradleException("build-tools 未找到")
val aapt2 = File(buildTools, exe("aapt2"))
val apksigner = File(buildTools, bat("apksigner"))
val keytool = File(System.getProperty("java.home"), "bin/${exe("keytool")}")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// 源码根：src/main/java（样板保持标准布局）
sourceSets.main {
    java.setSrcDirs(listOf("src/main/java"))
}

dependencies {
    compileOnly(files(androidJar))
    // 剔除 h 桥的 SDK jar（桥在运行时由 runtime dex 提供，开发侧不可见）
    compileOnly(project(mapOf("path" to ":module-api:api", "configuration" to "sdk")))
    // 语义门面随插件 dex 分发（参与插件混淆）
    implementation(project(":module-api:plugin-base"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

val d8Classpath = configurations.create("d8tool")
dependencies {
    d8Classpath("com.android.tools:r8:8.13.25")
}

/** aapt2 生成带编译 manifest 的基础 apk。 */
val aapt2Link = tasks.register<Exec>("aapt2Link") {
    group = "ratel"
    val out = layout.buildDirectory.dir("aapt2").get().asFile
    outputs.dir(out)
    doFirst { out.mkdirs() }
    commandLine(
        aapt2.absolutePath, "link",
        "--manifest", project.file("AndroidManifest.xml").absolutePath,
        "-I", androidJar.absolutePath,
        "--min-sdk-version", "29",
        "-o", File(out, "plugin-container.apk").absolutePath,
    )
}

/** d8：插件类 + plugin-base 门面类 → classes.dex。 */
val pluginBaseJar = project(":module-api:plugin-base").layout.buildDirectory
    .file("dist-sdk/ratel/sdk/plugin-base.jar")

val moduleDex = tasks.register<JavaExec>("moduleDex") {
    group = "ratel"
    dependsOn("classes")
    dependsOn(":module-api:plugin-base:jar")
    classpath = d8Classpath
    mainClass.set("com.android.tools.r8.D8")
    val pkgDir = layout.buildDirectory.dir("pkg").get().asFile
    val classesJar = layout.buildDirectory.file("module-classes.jar").get().asFile
    args(
        "--release", "--lib", androidJar.absolutePath, "--min-api", "29",
        "--output", pkgDir.absolutePath, classesJar.absolutePath,
        // api 类由宿主 runtime dex 提供，不打进来
        pluginBaseJar.get().asFile.absolutePath,
    )
    doFirst {
        val pkgDir = layout.buildDirectory.dir("pkg").get().asFile
        if (!pkgDir.isDirectory) pkgDir.mkdirs()
        classesJar.parentFile.mkdirs()
        JarOutputStream(classesJar.outputStream()).use { jos ->
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

/** 组包：base.apk + classes.dex + assets/xposed_init（ant zip 追加，纯 JVM 跨平台）。 */
val assemblePlugin = tasks.register("assemblePlugin") {
    group = "ratel"
    dependsOn(aapt2Link, moduleDex)
    val out = layout.buildDirectory.file("plugin-unsigned.apk").get().asFile
    outputs.file(out)
    doLast {
        out.delete()
        out.outputStream().use { File(layout.buildDirectory.dir("aapt2").get().asFile, "plugin-container.apk").inputStream().copyTo(it) }
        ant.withGroovyBuilder {
            "zip"(
                "destfile" to out.absolutePath,
                "basedir" to layout.buildDirectory.dir("pkg").get().asFile.absolutePath,
                "includes" to "classes.dex",
                "update" to true,
            )
            "zip"(
                "destfile" to out.absolutePath,
                "basedir" to project.file("plugin-assets").absolutePath,
                "update" to true,
            )
        }
    }
}

/** 自持开发证书（首签生成，跨平台 keytool Exec）。 */
val ensureKeystore = tasks.register("ensureKeystore") {
    val ks = project.file("devplugin.keystore")
    outputs.file(ks)
    doLast {
        if (!ks.isFile) {
            val p = ProcessBuilder(
                keytool.absolutePath, "-genkeypair",
                "-keystore", ks.absolutePath, "-storetype", "PKCS12",
                "-storepass", "ratelplugin", "-keypass", "ratelplugin", "-alias", "plugin",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
                "-dname", "CN=Ratel Dev Plugin",
            ).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes()
            if (p.waitFor() != 0) {
                throw GradleException("keytool 失败: " + String(out))
            }
        }
    }
}

tasks.register<Exec>("buildDevPlugin") {
    group = "ratel"
    dependsOn(assemblePlugin, ensureKeystore)
    val unsigned = layout.buildDirectory.file("plugin-unsigned.apk").get().asFile
    val signed = layout.buildDirectory.file("dev-plugin.apk").get().asFile
    outputs.file(signed)
    commandLine(
        apksigner.absolutePath, "sign",
        "--ks", project.file("devplugin.keystore").absolutePath,
        "--ks-pass", "pass:ratelplugin", "--ks-key-alias", "plugin",
        "--out", signed.absolutePath,
        unsigned.absolutePath,
    )
}
