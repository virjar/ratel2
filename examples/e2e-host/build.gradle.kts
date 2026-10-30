import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
}

// e2e 最小宿主：assembleRelease 产 origin apk；e2e 任务跑全链
// （init 模板模块 → ratelBuild → adb install → logcat 断言）

android {
    namespace = "com.ratel.e2ehost"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ratel.e2ehost"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

val adbPath: String = run {
    val home = System.getenv("ANDROID_HOME")
        ?: File(System.getProperty("user.home"), "Library/Android/sdk").absolutePath
    File(home, "platform-tools/adb").absolutePath
}

val ratelBinPath: String = rootProject.layout.buildDirectory
    .file("install-ratel/bin/ratel").get().asFile.absolutePath

/** origin apk 的 entry 指纹（name+crc+size），与 e2e-host logSelfCrc 同算法。 */
fun originCrc(origin: File): Long {
    val crc = CRC32()
    ZipFile(origin).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            crc.update(e.name.toByteArray(Charsets.UTF_8))
            crc.update(e.crc.toInt())
            crc.update(e.size.toInt())
        }
    }
    return crc.value
}

fun runCmd(vararg cmd: String, dir: File? = null, capture: Boolean = false): String {
    val pb = ProcessBuilder(*cmd)
    if (dir != null) pb.directory(dir)
    pb.redirectErrorStream(true)
    val p = pb.start()
    val output = p.inputStream.bufferedReader().readText()
    if (!capture && output.isNotBlank()) print(output)
    check(p.waitFor(10, TimeUnit.MINUTES) && p.exitValue() == 0) {
        "命令失败: ${cmd.joinToString(" ")}\n$output"
    }
    return output
}

/**
 * 全链 e2e：构建 e2e-host → init（默认模板模块）→ gradle ratelBuild → 安装 → logcat 断言。
 * 断言集（覆盖核心链路）：
 *   1. "ratel bootstrap done"      入口注入 + runtime 生效
 *   2. "lsplant init ok"           hook 运行时（libart 符号解析）
 *   3. "module entry ok"           模块加载（plugin-base 全链）
 *   4. "onResume -> "              LSPlant hook 真实触发
 * 设备：ANDROID_SERIAL 或第一台在线设备；无设备跳过（exit 0）。
 */
tasks.register("e2e") {
    group = "ratel"
    dependsOn(":toolchain:installDist", "assembleRelease")
    outputs.upToDateWhen { false }

    doLast {
        var serial: String? = System.getenv("ANDROID_SERIAL")?.takeIf { it.isNotBlank() }
        if (serial == null) {
            serial = runCmd(adbPath, "devices", capture = true).lines()
                .filter { it.endsWith("\tdevice") }
                .firstOrNull()?.substringBefore("\t")
        }
        if (serial == null) {
            println("[e2e] 无已连接设备，跳过（CI 接模拟器后自动覆盖）")
            return@doLast
        }
        println("[e2e] 目标设备: $serial")

        val ratelBin = File(rootProject.projectDir, "toolchain/build/install/ratel/bin/ratel").absolutePath
        val origin = File(project.buildDir, "outputs/apk/release/e2e-host-release.apk")
        val ws = File(project.buildDir, "e2e-ws")
        ws.deleteRecursively()

        runCmd(ratelBin, "init", origin.absolutePath, "-o", ws.absolutePath)

        // crcBypass 场景：yaml 开启（注入完整 origin.apk + fd 重定向）
        val yaml = File(ws, "project.yaml")
        yaml.writeText(yaml.readText().replace("crcBypass: false", "crcBypass: true"))

        // origin 期望 crc（与 e2e-host MainActivity.logSelfCrc 同算法：entry name+crc+size）
        val expected = originCrc(origin)
        println("[e2e] origin-crc=$expected")

        runCmd("./gradlew", "ratelBuild", "-q", dir = ws)
        val outApk = File(ws, "out").listFiles { f: File -> f.name.endsWith("-ratel.apk") }!!.first()

        // workspace 每次重建生成新开发证书——先卸载旧签名版本保证幂等（允许失败：可能未装/PM 未就绪）
        try {
            runCmd(adbPath, "-s", serial, "uninstall", "com.ratel.e2ehost", capture = true)
        } catch (e: Exception) {
            println("[e2e] uninstall 跳过: ${e.message?.lineSequence()?.first()}")
        }
        runCmd(adbPath, "-s", serial, "install", outApk.absolutePath)
        runCmd(adbPath, "-s", serial, "logcat", "-c")
        runCmd(adbPath, "-s", serial, "shell", "am", "start", "-n", "com.ratel.e2ehost/.MainActivity")
        Thread.sleep(9000)
        val logs = runCmd(adbPath, "-s", serial, "logcat", "-d", capture = true)

        val asserts = listOf(
            "ratel bootstrap done",
            "lsplant init ok",
            "module entry ok",
            "onResume -> ",
            "crcBypass: redirect",        // 重定向已安装
            "resources rebuilt",          // Resources 重建（zip 索引一致性）
            "self-crc=$expected",         // 自校验读到原始字节
            "axml-read=OK",                // 资源系统读 axml 无 crash（索引一致性）
        )
        var failed = false
        for (a in asserts) {
            val hit = logs.contains(a)
            println("[e2e] ${if (hit) "PASS" else "FAIL"} $a")
            if (!hit) failed = true
        }
        if (failed) throw GradleException("[e2e] 断言失败（设备 $serial）——完整日志见 logcat")
        println("[e2e] 全链通过 (device=$serial)")
    }
}
