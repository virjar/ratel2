plugins {
    `java-library`
}

// jar 输出到 dist-sdk 目录（toolchain 资源直连；工作区 sdk/ 分发给模块开发者）
tasks.named<Jar>("jar") {
    archiveBaseName.set("plugin-base")
    archiveClassifier.set("")
    archiveVersion.set("") // 文件名冻结：SDK 与引擎强绑定同版本发布
    destinationDirectory.set(layout.buildDirectory.dir("dist-sdk/ratel/sdk"))
}

// 语义门面层：随插件 dex 分发（插件 implementation 依赖），随插件混淆。
// 依赖 api 为 compileOnly——运行时由宿主 runtime dex 提供
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

dependencies {
    compileOnly(project(":module-api:api"))
}
