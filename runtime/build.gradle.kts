plugins {
    id("com.android.application")
}

// ---- runtime 模块：完整 AGP app 流程（LSPatch patch-loader 同款）----
// AGP 管 R8 → D8 → dex 合并（:api、hiddenapibypass 等 implementation 依赖自动入 dex）、
// native cmake、debug/release variant；dex/so 从 intermediates 提取，不手写 d8/AAR 解包
// multiDex 关死：改名遮蔽方案要求 runtime 恰好一个 classes.dex

android {
    namespace = "com.ratel.runtime"
    ndkVersion = "30.0.16248370"
    // compileSdk 37：与项目目标 API 29-37 对齐；hiddenapibypass 6.2 的 AAR metadata 亦要求 37
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        multiDexEnabled = false

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=gnu++23", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-29",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLSPLANT_BUILD_SHARED=OFF",
                    "-DDEX_BUILDER_BUILD_SHARED=OFF",
                    "-DDOBBY_GENERATE_SHARED=OFF",
                )
            }
        }
        ndk {
            // x86_64：CI ubuntu 模拟器 / 云手机场景（native 侧 ELF64 解析与 dobby trampoline 均无架构硬编码）
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // JDK21 javac 不再接受 source/target 8；D8 desugar 到 minSdk 29
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // 该 app 是 dex 容器、永不安装上架，Android release lint 在此无对象
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":module-api:api"))
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
}

// ---- 产物聚合：intermediates dex + stripped so → distribution ----
// LSPatch 同款取法：release（minify=true）走 R8 产物，debug 走 dex 合并产物
// toolchain 经 sourceSets 直连本目录，布局稳定即接口

val ratelVariant = (project.findProperty("ratelVariant") as String?) ?: "release"
val buildTypeName = if (ratelVariant == "debug") "Debug" else "Release"
val distributionDir = layout.buildDirectory.dir("distribution/ratel")

tasks.register("buildRuntime") {
    group = "ratel"
    outputs.upToDateWhen { false }
    dependsOn("assemble${buildTypeName}")

    doLast {
        val dexDir = if (ratelVariant == "debug")
            File(buildDir, "intermediates/dex/${ratelVariant}/merge${buildTypeName}Dex")
        else
            File(buildDir, "intermediates/dex/${ratelVariant}/minify${buildTypeName}WithR8")
        val dexSrc = File(dexDir, "classes.dex")
        if (!dexSrc.isFile) throw GradleException("dex not found: $dexSrc")

        val dist = File(distributionDir.get().asFile, "ratel")
        dist.mkdirs()
        File(dist, "runtime.dex").writeBytes(dexSrc.readBytes())

        var soCount = 0
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val soSrc = File(buildDir, "intermediates/stripped_native_libs/${ratelVariant}/strip${buildTypeName}DebugSymbols/out/lib/$abi/libratel.so")
            if (soSrc.isFile) {
                val libDir = File(dist, "lib/$abi")
                libDir.mkdirs()
                File(libDir, "libratel.so").writeBytes(soSrc.readBytes())
                soCount++
            }
        }
        println("runtime built (variant=$ratelVariant, dex=${dexSrc.length()}B, so=$soCount abi)")
    }
}
