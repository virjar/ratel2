plugins {
    `java-library`
}

// api 面向模块开发者：保持 Java 8 字节码（模块工程环境各异）
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

// ratel 模块开发者 API（模块 compileOnly 依赖；运行时由宿主 runtime dex 提供）

// ---- SDK 导出：剔除桥类 h 的 jar ----
// h 是 runtime 静态赋值的桥持有：运行时保留（runtime dex 完整含它），但不出现在
// 插件编译 classpath——插件代码经 plugin-base（已预编译）访问，开发者不可见、
// 无法二次赋值破坏桥
val sdkJar = tasks.register<Jar>("sdkJar") {
    archiveBaseName.set("ratel-sdk")
    archiveClassifier.set("")
    archiveVersion.set("") // 文件名冻结：SDK 与引擎强绑定同版本发布
    // 输出到专属 dist-sdk 目录（toolchain 资源经 sourceSets 直连，产物零源码树）
    destinationDirectory.set(layout.buildDirectory.dir("dist-sdk/ratel/sdk"))
    from(sourceSets.main.get().output)
    exclude("com/ratel/api/h.class")
}
configurations.create("sdk")
artifacts.add("sdk", sdkJar)
