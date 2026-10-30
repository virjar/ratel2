plugins {
    java
}

// bundletool lib 封装：aab + device-spec → .apks（仅构建期使用，不打进任何 apk）
dependencies {
    implementation("com.android.tools.build:bundletool:1.17.0")
}
tasks.named<Jar>("jar") {
    archiveFileName.set("split-apks-tool.jar")
    archiveVersion.set("")
}
