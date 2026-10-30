plugins {
    application
}

application {
    mainClass = "com.ratel.toolchain.Main"
    applicationName = "ratel"
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

// CLI 在用户工作区 gradle daemon 里被 java -cp 调起，daemon JVM 常见为 JBR 17——
// 不钉版本则编译目标跟随本仓库构建 JVM 漂移（21 编出 v65 字节码在 17 上 LinkageError）
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// runtime 产物（runtime.dex/libratel.so）不落源码树：resources 直连 :runtime build 输出目录。
// 不用中间 Copy 任务暂存（曾出现 input 指纹不刷新、jar 内嵌旧 so 的事故），直连 + 显式
// dependsOn 让 processResources 每次从权威产物目录取文件
sourceSets {
    main {
        // runtime 产物（runtime.dex/libratel.so）与模块开发 SDK（ratel-sdk/plugin-base jar）
        // 全部 sourceSets 直连各模块 build 输出目录——产物零源码树，依赖链自动重建
        resources.srcDir(project(":runtime").layout.buildDirectory.dir("distribution/ratel"))
        resources.srcDir(project(":module-api:api").layout.buildDirectory.dir("dist-sdk"))
        resources.srcDir(project(":module-api:plugin-base").layout.buildDirectory.dir("dist-sdk"))
        // native API 头文件单一源头（module-api/native，资源布局 ratel/native/）
        resources.srcDir(rootProject.layout.projectDirectory.dir("module-api/native"))
        // plugin-base 源码（init 释放进工作区模块工程——可读可改；文件清单见
        // GradleWorkspaceGenerator.PLUGIN_BASE_SOURCES，新增/改名类须同步）
        resources.srcDir(project(":module-api:plugin-base").layout.projectDirectory.dir("src/main/java"))
    }
}
tasks.named("processResources") {
    dependsOn(project(":runtime").tasks.named("buildRuntime"))
    dependsOn(project(":module-api:api").tasks.named("sdkJar"))
    dependsOn(project(":module-api:plugin-base").tasks.named("jar"))
}

dependencies {
    implementation("info.picocli:picocli:4.7.7")
    implementation("org.yaml:snakeyaml:2.2")

    // zip 增量打包（raw-copy 未变更条目）
    implementation("org.apache.commons:commons-compress:1.28.0")

    // dex 读写 / smali 反编译回编
    implementation("org.smali:dexlib2:2.5.2")
    implementation("org.smali:baksmali:2.5.2")
    implementation("org.smali:smali:2.5.2")
    implementation("com.google.guava:guava:33.0.0-jre") // dexlib2 immutable API 编译期需要

    // java 级反编译（程序化调用，不走 CLI）
    implementation("io.github.skylot:jadx-core:1.5.6")
    implementation("io.github.skylot:jadx-dex-input:1.5.6")

    // manifest 元信息解析
    implementation("net.dongliu:apk-parser:2.6.10")

    // 重签名
    implementation("com.android.tools.build:apksig:8.13.2")

    // class -> dex 编译（java 混编产物落 dex）
    implementation("com.android.tools:r8:8.13.25")

    // dex -> class 字节码直转（stub jar 生成，dex2jar 路线，不走 java 源码+javac）
    implementation("org.ow2.asm:asm:9.11.1")
    // 依赖重定位（ClassRemapper）：混编 maven 依赖 relocate 到 ratel.repack.* 防与原 app 冲突
    implementation("org.ow2.asm:asm-commons:9.11.1")
}
