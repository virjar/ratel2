package com.ratel.toolchain.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 生成工作区的 gradle 工程文件（virjar 定版：工作区是标准 gradle 项目，IDE 全程可开发——
 * gradle 管模块/patch 编译与任务编排，ratel CLI 管 stub/relocate/dex/apk 手术）。
 *
 * <p>工程布局（IDE 打开即完整项目）：
 * <pre>
 *   module/      插件工程（sdk/ 下的 ratel-sdk + plugin-base 编译，d8 产 module.apk）
 *   app-patch/   java 混编工程（宿主 stub 编译 + relocate + d8 patch dex）
 *   smali/       按需 pull 的 smali（SmaliIdea 插件可导航），回编走 ratelBuild
 *   sdk/         ratel-sdk.jar + plugin-base.jar（init 时从工具链释放）
 *   project.yaml ratel 配置（注入方案等，IDE 直接编辑）
 * </pre>
 *
 * <p>任务链：ratelBuild = :module:buildModuleApk + :app-patch:ratelBuild（CLI build）；
 * ratelPull 按需反编译；ratelStub 保证 stub jar。
 */
public final class GradleWorkspaceGenerator {

    public static void generate(ProjectLayout layout, String packageName, String ratelDistLibDir)
            throws IOException {
        String name = "ratel-" + packageName.replaceAll("[^a-zA-Z0-9]", "-");

        writeString(layout.root.resolve("settings.gradle.kts"),
                """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }

                rootProject.name = "%s"

                include(":module")
                include(":app-patch")
                """.formatted(name));

        writeString(layout.root.resolve("gradle.properties"),
                """
                # ratel 工具链 dist 的 lib 目录。定位优先级：RATEL_HOME 环境变量 > 本属性
                # （工程移交到其他机器时：设 RATEL_HOME=<发布包解压路径>，或改下面这行）
                ratelDistDir=%s
                org.gradle.jvmargs=-Xmx2g
                org.gradle.parallel=true
                """.formatted(ratelDistLibDir));

        writeString(layout.root.resolve("build.gradle.kts"),
                """
                allprojects {
                    repositories {
                        mavenCentral()
                        google()
                    }
                }

                // ratel 工具链 classpath（dist lib 目录；RATEL_HOME 优先于 gradle.properties）
                val ratelHome = System.getenv("RATEL_HOME")?.let { File(it, "lib") }
                val ratelCp = files(fileTree(ratelHome ?: File(providers.gradleProperty("ratelDistDir").get())) {
                    include("*.jar")
                })

                // ratel 全链一键构建：模块 apk + patch dex + 遮蔽合并 + 签名
                // （mustRunAfter 显式排序：CLI build 要读 module/build/module.apk，并行下防竞态）
                tasks.register("ratelBuild") {
                    group = "ratel"
                    dependsOn(":module:buildModuleApk")
                    dependsOn(":app-patch:ratelBuild")
                }
                gradle.projectsEvaluated {
                    project(":app-patch").tasks.named("ratelBuild") {
                        mustRunAfter(":module:buildModuleApk")
                    }
                }

                // 按需反编译宿主类到工程（写代码前先拉目标类），例：
                //   ./gradlew ratelPull -Pcls=com.foo.Bar           # 反编译为 java（jadx）
                //   ./gradlew ratelPull -Pcls=com.foo.Bar -Psmali  # 反编译为 smali（可回编）
                tasks.register("ratelPull", JavaExec::class) {
                    group = "ratel"
                    classpath = ratelCp
                    mainClass.set("com.ratel.toolchain.Main")
                    doFirst {
                        val cls = project.findProperty("cls") as String?
                            ?: throw GradleException("用法: ./gradlew ratelPull -Pcls=com.foo.Bar [-Psmali]")
                        setArgs(listOf("pull", rootProject.projectDir.absolutePath, cls)
                                + if (project.hasProperty("smali")) listOf("--smali") else listOf("--java"))
                    }
                }
                """);

        writeModuleProject(layout);
        writeAppPatchProject(layout);

        // 内嵌模块指向 module 工程产物（单模块模型，ratelBuild 前置构建）
        Map<String, Object> config = layout.config();
        @SuppressWarnings("unchecked")
        Map<String, Object> modules = (Map<String, Object>) config.computeIfAbsent(
                "modules", k -> new java.util.LinkedHashMap<String, Object>());
        modules.put("embed", "module/dist/module.apk");
        layout.writeConfig(config);

        // SDK 释放（模块编译依赖：ratel-sdk 剔桥；plugin-base 走源码释放）
        extractSdk(layout);

        // 工作区 AGENTS.md：面向 AI 助手的工程导读（构建/开发/背景）
        writeString(layout.root.resolve("AGENTS.md"), WORKSPACE_AGENTS);

        // git 化支持：产物/机器相关文件忽略（cert 保留——多人协作需同一开发证书互相覆盖安装）
        writeString(layout.root.resolve(".gitignore"), WORKSPACE_GITIGNORE);

        // AGP（module 工程）需要 SDK 定位：local.properties 写 sdk.dir（机器相关，移交工程时重设）
        try {
            java.nio.file.Path androidJar = com.ratel.toolchain.compile.AndroidJarLocator.locate();
            Files.writeString(layout.root.resolve("local.properties"),
                    "sdk.dir=" + androidJar.getParent().getParent().getParent() + "\n");
        } catch (Exception e) {
            System.out.println("[init] SDK 定位失败（module 工程首次构建前需设 ANDROID_HOME 或 local.properties）: "
                    + e.getMessage());
        }

        copyWrapper(layout);
    }

    /** module/：插件工程（模板入口可编译可运行，自证据 hook）。 */
    private static void writeModuleProject(ProjectLayout layout) throws IOException {
        Files.createDirectories(layout.root.resolve("module/src/main/java/ratel/module"));
        writeString(layout.root.resolve("module/build.gradle.kts"), MODULE_BUILD_GRADLE);

        // application 插件需要 manifest（模块 apk 不安装，最小形态即可）
        Files.writeString(layout.root.resolve("module/src/main/AndroidManifest.xml"),
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" />
                """);

        // R8 keep 规则：入口类由 xposed_init 反射加载必须 keep；其余（含 plugin-base
        // 门面类）整体混淆——分发物无语义特征。改入口类名时同步本文件与 xposed_init
        writeString(layout.root.resolve("module/proguard-rules.pro"),
                """
                # ratel 模块 R8 规则
                # 入口类：ModuleLoader 按 assets/xposed_init 反射实例化（改名须同步两处）
                -keep class ratel.module.Main { *; }

                # plugin-base 门面与 api 引用不 keep——随模块混淆（单字符 api 层无特征，
                # 门面类混淆后业务代码依旧自洽）；api 层 compileOnly 不进模块 dex
                """ + "-dontwarn com.ratel.api.**\n" 
                + "# 混淆类收进独占包：避免与宿主 runtime dex 的 R8 混淆名（根包 a/b/...）\n"
                + "# 碰撞——DexClassLoader parent-first 下同名父类会被宿主劫持（InstantiationError）\n"
                + "-repackageclasses ratel.m\n");

        writeString(layout.root.resolve("module/src/main/java/ratel/module/Main.java"),
                """
                package ratel.module;

                import com.ratel.plugin.HostApp;
                import com.ratel.plugin.MethodHook;
                import com.ratel.plugin.RatelHelpers;
                import com.ratel.plugin.RatelModule;

                import java.lang.reflect.Method;

                /**
                 * ratel 模块模板入口（assets/xposed_init 声明本类）。
                 * 逻辑随便改——这里默认 hook Activity.onResume 打日志作为链路自证据。
                 */
                public class Main extends RatelModule {

                    @Override
                    protected void handleLoadPackage(HostApp app) throws Throwable {
                        android.util.Log.i("RatelModule", "module loaded in " + app.packageName()
                                + " process=" + app.processName());

                        RatelHelpers.findAndHookMethod(android.app.Activity.class, "onResume", new MethodHook() {
                            @Override
                            public void afterHookedMethod(Param param) {
                                android.util.Log.i("RatelModule",
                                        "onResume -> " + param.thisObject().getClass().getName());
                            }
                        });
                    }
                }
                """);

        // plugin-base 以源码释放进模块工程（可读可导航可改，随模块一起编译）
        releasePluginBaseSources(layout);

        Files.createDirectories(layout.root.resolve("module/src/main/assets"));
        writeString(layout.root.resolve("module/src/main/assets/xposed_init"),
                "ratel.module.Main\n");
        // native 声明（so 始终打包；是否加载由 modules.native 运行时配置）
        writeString(layout.root.resolve("module/src/main/assets/native_init"),
                "libnativemodule.so\n");

        writeNativeModule(layout);
    }

    /** plugin-base 源码文件清单（toolchain 资源直连 module-api/plugin-base 源码目录）。 */
    private static final String[] PLUGIN_BASE_SOURCES = {
            "com/ratel/plugin/Ratel.java",
            "com/ratel/plugin/RatelHelpers.java",
            "com/ratel/plugin/MethodHook.java",
            "com/ratel/plugin/HostApp.java",
            "com/ratel/plugin/RatelModule.java",
            "com/ratel/plugin/Unhook.java",
    };

    private static void releasePluginBaseSources(ProjectLayout layout) throws IOException {
        for (String rel : PLUGIN_BASE_SOURCES) {
            byte[] data = readResource(rel);
            if (data == null) {
                throw new IOException("toolchain 资源缺失: " + rel
                        + "（plugin-base 新增/改名类须同步 GradleWorkspaceGenerator.PLUGIN_BASE_SOURCES）");
            }
            Path target = layout.root.resolve("module/src/main/java").resolve(rel);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        }

    }

    /**
     * native 模块（src/main/jni，NDK 传统布局）：始终构建——native hook 是模块
     * 基础设施（watch_library/redirect 会操作 linker），so 永远进 module.apk；
     * 是否加载由 project.yaml modules.native 控制（运行时接线）。
     * ratel_native.h 与模块 C 源码同居（API 定义与实现一处可读）。
     */
    private static void writeNativeModule(ProjectLayout layout) throws IOException {
        Path dir = layout.root.resolve("module/src/main/jni");
        Files.createDirectories(dir);

        byte[] header = readResource("ratel/native/ratel_native.h");
        if (header == null) {
            throw new IOException("toolchain 资源缺失: ratel/native/ratel_native.h");
        }
        Files.write(dir.resolve("ratel_native.h"), header);

        Files.writeString(dir.resolve("CMakeLists.txt"),
                """
                cmake_minimum_required(VERSION 3.22)
                project(ratel_module)

                include_directories(${CMAKE_CURRENT_SOURCE_DIR})

                add_library(nativemodule SHARED nativemodule.c)
                target_link_libraries(nativemodule log)
                """);
        Files.writeString(dir.resolve("nativemodule.c"),
                """
                #include "ratel_native.h"

                #include <android/log.h>

                #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RatelNativeModule", __VA_ARGS__)

                static const ratel_native_api_t *api;

                static int replacement_test_target() {
                    LOGI("ratel_test_target hooked!");
                    return 42;
                }

                void ratel_module_init(const ratel_native_api_t *a) {
                    api = a;
                    LOGI("native module init, api_version=%d", a->api_version);

                    void *target = a->resolve("libratel.so", "ratel_test_target");
                    if (target != NULL) {
                        void *orig = a->hook(target, (void *) replacement_test_target);
                        int v = ((int (*)()) target)();
                        LOGI("inline hook 验证: %d (orig=%d)", v, orig ? ((int (*)()) orig)() : -1);
                    }
                }
                """);
    }

    private static final String MODULE_BUILD_GRADLE = """
            plugins {
                id("com.android.application") version "9.4.1"
            }

            android {
                namespace = "ratel.module"
                compileSdk = 35

                defaultConfig {
                    applicationId = "ratel.module"   // 仅构建标识，模块 apk 不安装
                    minSdk = 29
                    ndk { abiFilters += "arm64-v8a" }
                }

                buildTypes {
                    release {
                        // 模块整体混淆（分发物无语义特征）；入口类经 proguard-rules.pro keep
                        isMinifyEnabled = true
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    }
                    debug {
                        isMinifyEnabled = false
                    }
                }

                buildFeatures {
                    buildConfig = false
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                // native 始终构建（src/main/jni）——native hook 是模块基础设施；so 永远打进
                // module.apk，运行时按 project.yaml modules.native 决定是否加载（接线）
                externalNativeBuild {
                    cmake {
                        path = file("src/main/jni/CMakeLists.txt")
                    }
                }
            }

            dependencies {
                // ratel api（剔桥版；运行时由宿主 runtime dex 提供）——本地 jar 惯例位置 libs/
                compileOnly(files("libs/ratel-sdk.jar"))
                // plugin-base 以源码形式在 src/main/java/com/ratel/plugin（可读可改，随模块一起编译）
            }

            // module apk = AGP 应用产物（dex 含 R8 混淆 + jni so + assets，标准链路零手写）。
            // 输出到 dist/（build/ 为 AGP 输出域不可占用）
            tasks.register<Copy>("buildModuleApk") {
                group = "ratel"
                dependsOn("assembleRelease")
                from(layout.buildDirectory.file("outputs/apk/release/module-release-unsigned.apk"))
                into(layout.projectDirectory.dir("dist"))
                rename { "module.apk" }
            }
        """;

    private static void writeAppPatchProject(ProjectLayout layout) throws IOException {
        // 最小 manifest（AGP 需要；app-patch 产物是 patch dex 不安装）
        Files.writeString(layout.root.resolve("app-patch/src/main/AndroidManifest.xml"),
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" />
                """);

        writeString(layout.root.resolve("app-patch/build.gradle.kts"), APP_PATCH_BUILD_GRADLE);
    }

    private static final String APP_PATCH_BUILD_GRADLE = """
            plugins {
                id("com.android.library") version "9.4.1"
            }

            android {
                namespace = "ratel.patch"
                compileSdk = 35

                defaultConfig {
                    minSdk = 29
                }

                buildFeatures {
                    buildConfig = false
                }

                // smali 补丁目录挂为源根：AS（配 SmaliIdea 插件）可索引导航；
                // javac 只认 .java 文件，与混编互不干扰
                sourceSets {
                    getByName("main") {
                        java.srcDir(file("../smali"))
                    }
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            val ratelDistDir: String = System.getenv("RATEL_HOME")
                ?.let { "$it/lib" }
                ?: providers.gradleProperty("ratelDistDir").get()

            val ratelCp = files(fileTree(File(ratelDistDir)) { include("*.jar") })
            val ratelDeps = configurations.create("ratelDeps")

            dependencies {
                // 宿主类 stub（ratelStub 任务产出，内容 hash 命名——文件名变即触发重编与
                // IDE 重索引，规避"路径不变内容变"的缓存问题）
                compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
                // 第三方库依赖用 ratelDeps 声明：构建时整体 relocate 到 ratel.repack.*
                // 前缀打入 apk，不会与原始 apk 内的同名库（okhttp/kotlin-stdlib 等）冲突
                // ratelDeps("org.apache.commons:commons-lang3:3.14.0")
            }

            // stub 生成：ratel CLI 以源码 import 为种子 → libs/ 下内容 hash 命名
            // （AGP 9 任务注册晚于脚本求值——挂接用 matching，产物引用在执行期取）

            tasks.register("ratelStub", JavaExec::class) {
                group = "ratel"
                // 无源码时无 stub 种子（ratel stub 退出码 2），跳过即可
                onlyIf { File(projectDir, "src/main/java").walkTopDown().any { it.isFile } }
                classpath = ratelCp
                mainClass.set("com.ratel.toolchain.Main")
                // CLI 直接产出内容 hash 命名 jar（app-patch/libs/ratel-stubs-<hash>.jar，
                // 自动清理旧版本）——文件名随内容变化：gradle 输入与 IDE 索引必然感知
                args("stub", rootProject.projectDir.absolutePath)
            }

            tasks.matching { it.name == "compileReleaseJavaWithJavac" }
                .configureEach { dependsOn("ratelStub") }

            // patch dex：javac 产物 + maven 依赖 → relocate → d8
            tasks.register("ratelPatchDex", JavaExec::class) {
                group = "ratel"
                dependsOn("compileReleaseJavaWithJavac")
                onlyIf { File(projectDir, "src/main/java").walkTopDown().any { it.isFile } }
                classpath = ratelCp
                mainClass.set("com.ratel.toolchain.Main")
                doFirst {
                    val deps = ratelDeps.resolve()
                        .filter { it.isFile && it.extension == "jar" }
                        .joinToString(",") { it.absolutePath }
                    val classesDir = tasks.named<JavaCompile>("compileReleaseJavaWithJavac")
                        .get().destinationDirectory.get().asFile
                    val full = mutableListOf(
                        "patchdex", rootProject.projectDir.absolutePath,
                        "--classes-dir", classesDir.absolutePath)
                    if (deps.isNotEmpty()) {
                        full += listOf("--dep-jars", deps)
                    }
                    setArgs(full)
                }
            }

            tasks.register("ratelBuild", JavaExec::class) {
                group = "ratel"
                dependsOn("ratelPatchDex")
                classpath = ratelCp
                mainClass.set("com.ratel.toolchain.Main")
                args("build", rootProject.projectDir.absolutePath)
            }
    """;

    /**
     * 释放模块开发 SDK（工具链资源 → module/libs/：api jar + native 头文件——
     * 仅 module 工程消费随工程走；plugin-base 走源码释放）。
     */
    private static void extractSdk(ProjectLayout layout) throws IOException {
        Path libsDir = layout.root.resolve("module/libs");
        Files.createDirectories(libsDir);
        byte[] sdkJar = readResource("ratel/sdk/ratel-sdk.jar");
        if (sdkJar == null) {
            throw new IOException("toolchain 资源缺失: ratel/sdk/ratel-sdk.jar");
        }
        Files.write(libsDir.resolve("ratel-sdk.jar"), sdkJar);
    }

    /** 工作区 AGENTS.md 模板（面向用户的 AI 助手；github 地址开源后补全）。 */
    private static final String WORKSPACE_AGENTS = """
        # AGENTS.md — ratel 工程导读（面向 AI 助手与开发者）

        本工程由 **ratel 2.0** 从一个 APK 展开——ratel 是 PC 端 APK 二次开发工具链
        （写 Java 改 App，不是写 smali），项目主页：https://github.com/virjar/ratel2。
        改完执行 `./gradlew ratelBuild`，产物在 `out/`，adb install 安装。

        ## 工程结构与职责

        | 路径 | 用途 |
        |---|---|
        | `origin.apk` | 原始 APK（不可变基准，勿改） |
        | `ratel-dev.keystore` | 开发签名证书（自动生成；多人协作跟踪同一证书才能互相覆盖安装） |
        | `project.yaml` | ratel 配置（入口方案/签名/模块/native 接线/crcBypass） |
        | `module/` | hook 模块（AGP application 工程，R8 混淆，java + native） |
        | `app-patch/` | java 混编（AGP library；pull 宿主类为 Java 直接改） |
        | `smali/` | smali 指令级补丁（baksmali 产出，可直接编辑回编） |
        | `out/` | 构建产物 |

        ## 常用操作

        ```bash
        ./gradlew ratelBuild                          # 出包（模块+补丁+注入+签名）
        ./gradlew ratelPull -Pcls=com.foo.Bar         # 拉宿主类为 Java 到 app-patch/
        ./gradlew ratelPull -Pcls=com.foo.Bar -Psmali # 拉为 smali 到 smali/
        ./gradlew :module:assembleDebug               # 单独构建模块（debug 不混淆）
        ```

        安装产物：`adb install -r out/*.apk`（split 工程用 `ratel install <工程>`）。

        ## 开发指引

        **写 hook（module/）**：入口 `module/src/main/java/ratel/module/Main.java`
        （`assets/xposed_init` 声明），继承 `RatelModule`，用 `Ratel.hookMethod` /
        `MethodHook`（类 Xposed 语义）。门面源码在同目录 `com/ratel/plugin/`（可读可改，
        随模块编译并混淆）。native hook 写在 `module/src/main/jni/`（API 头文件
        `ratel_native.h` 同目录；运行时接线开关 project.yaml `modules.native`）。
        release 产物整体 R8 混淆（入口类 keep，改名须同步 proguard-rules.pro 与
        xposed_init）。

        **改宿主类（app-patch/）**：先 `ratelPull` 拉类 → 编辑（宿主类的编译依赖由
        `libs/ratel-stubs-<hash>.jar` 提供，任务链自动生成）→ ratelBuild 自动
        编译+relocate+合并替换原类。jadx 反编译输出可能与原逻辑有出入，编译报错即
        修复点；巨方法/关键字包名场景走 smali。

        **配置（project.yaml）**：`entry.scheme`（clinit 默认/acf 加固 App）；
        `crcBypass: true`（apk 完整性自校验对抗，包体积翻倍）；`modules.native`
        （native 接线开关）；`modules.dev`（dev 插件快速迭代——改模块只装 KB 级小包）。

        ## 注意

        - stub jar 以内容 hash 命名（`ratel-stubs-<hash>.jar`）：pull 新类后重构建
          会生成新 hash 文件，IDE 检测到新文件自动重建索引——若补全异常先 sync
        - 构建需要 JDK 17 + Android SDK；工具链位置在 gradle.properties（RATEL_HOME
          环境变量优先）
        - ratel 详细文档：https://github.com/virjar/ratel2/tree/main/docs（CLI 参考/配置参考/模块开发）
    """;

    private static final String WORKSPACE_GITIGNORE = """
            # 构建产物（gradle/ratel 任务链生成）
            .gradle/
            build/
            out/
            module/build/
            module/dist/
            module/.cxx/
            app-patch/build/

            # 注意：以下均为构建必需，默认跟踪入库——
            #   app-patch/libs/ratel-stubs-<hash>.jar：stub 依赖（hash 命名，变更即新文件，
            #     git diff 清晰；不入仓则协作者 IDE 首次索引报错）
            #   origin.apk：构建基准（不入仓协作者无法构建）
            # 超出 git 单文件 100MB 限制的（GitHub）用 git-lfs：
            #   git lfs install && git lfs track "*.apk" && git add .gitattributes

            # 机器相关（SDK 路径等）
            local.properties

            # IDE
            .idea/
            *.iml
            .DS_Store

            # 其余保留跟踪：ratel-dev.keystore（开发证书——多人协作同一证书才能互相覆盖安装）、
            # module/libs/ratel-sdk.jar 与 module/src/main/jni/ratel_native.h
            # （SDK 与工具链版本绑定）、origin-splits/（split 工程的构建基准，同 apk
            # 大文件处理）
    """;

    /** wrapper 内嵌于 toolchain 资源（二进制分发自包含，零路径依赖）。 */
    private static void copyWrapper(ProjectLayout layout) throws IOException {
        String[] entries = {
                "ratel/wrapper/gradlew", "ratel/wrapper/gradlew.bat",
                "ratel/wrapper/gradle-wrapper.jar", "ratel/wrapper/gradle-wrapper.properties",
        };
        for (String entry : entries) {
            byte[] data = readResource(entry);
            if (data == null) {
                throw new IOException("toolchain 资源缺失: " + entry);
            }
            Path target = layout.root.resolve(
                    entry.endsWith("gradle-wrapper.jar") || entry.endsWith("gradle-wrapper.properties")
                            ? "gradle/wrapper/" + entry.substring(entry.lastIndexOf('/') + 1)
                            : entry.substring(entry.lastIndexOf('/') + 1));
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            if (entry.endsWith("gradlew")) {
                target.toFile().setExecutable(true);
            }
        }
    }

    /**
     * text block 模板输出前剥离基准缩进：以首个非空行的缩进为准统一剥离
     * （模板在源码中嵌套层级不一，此法与嵌套深度无关）。
     */
    private static String dedent(String textBlock) {
        int base = 0;
        for (String l : textBlock.split("\n", -1)) {
            if (!l.isBlank()) {
                base = l.indexOf(l.trim());
                break;
            }
        }
        final int n = base;
        return textBlock.lines()
                .map(l -> l.length() >= n && l.startsWith(" ".repeat(n)) ? l.substring(n) : l)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static void writeString(Path path, String textBlock) throws IOException {
        Files.writeString(path, dedent(textBlock));
    }

    private static byte[] readResource(String path) throws IOException {
        try (var in = GradleWorkspaceGenerator.class.getResourceAsStream("/" + path)) {
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 工具链自身 jar 所在目录（installDist 布局即 lib/，运行时 classpath 全在此）。
     * 不依赖任何源码路径——二进制分发到用户机器同样成立。
     */
    public static String distLibDir() {
        try {
            var codeSource = GradleWorkspaceGenerator.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                return java.nio.file.Path.of(codeSource.getLocation().toURI()).getParent().toString();
            }
        } catch (Exception ignored) {
        }
        throw new IllegalStateException("无法定位工具链 lib 目录（非标准布局运行）");
    }
}
