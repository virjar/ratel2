# AGENTS.md — ratel 2.0 工程指导

本文件是仓库的权威导航：agent 或新维护者接手本仓库前先读完这里。
历史过程看 git log；这里只描述**现在的样子**与**必须遵守的规则**。

## 项目定位

ratel（平头哥）新一代版本。PC 端 APK 工程化二开工具链，产品形态对标 apktool：
`ratel init` 把 apk 展开为可移交的 gradle 工程 → 开发者以**写 Java 为主**（smali 为
指令级逃生舱）改造 → `ratel build` 增量重建 + 代码注入 + 重签名。xposed 式模块注入
是能力项，不是产品中心。

**明确不做**：对抗（svc/子进程/binder ioctl 不碰，入口方案被加固挡住就换方案，best-effort）、
分身/多开、manager app、云服务。支持 Android 10–17（API 29–37）。

## 工程铁律

1. **构建产物零源码树**：so/dex/jar 产物绝不进 src/。产物聚合到各模块 build 目录，
   消费方用 `sourceSets.resources.srcDir` 直连（依赖链自动重建）。**严禁中间 Copy
   暂存任务**——Gradle Copy 的 input 指纹不刷新曾导致 jar 内嵌旧 so（真机抓到过）。
2. **全 gradle 化**：不写 shell 脚本；Windows 可执行后缀（exe/bat）在 build.gradle.kts
   内统一处理；外部工具只用 SDK 自带（cmake/ninja/aapt2/apksigner/keytool）。
3. **私有项目零提及**：任何来源的私有 fork 设计可参考、不可署名/提及（代码注释、
   文档、提交信息全部零痕——包括历史重写）。
4. **依赖治理**：稳定上游（LSPlant/Dobby 等）只内嵌社区上游核心代码（commit 见
   NOTICE），本地补丁标注 `ratel patch`，升级时 diff 重放勿整目录覆盖；能用 Maven
   坐标的不带源码；优先 ratel 自有实现（如 pxb-axml）弃社区替代品。
5. **版本两段式**（2.0 形态）；SDK jar 文件名冻结无版本段（SDK 与引擎强绑定同版本发布）。
6. **toolchain 钉 `options.release=17`**：不钉则编译目标跟随构建 JVM 漂移（曾出现
   v65 字节码在用户 gradle daemon JBR 17 上 LinkageError）。

## 仓库结构

| 模块 | 职责 |
|---|---|
| `module-api/` | 模块开发体系（面向模块开发者）：`api/`（单字符混淆层）、`plugin-base/`（语义门面源码，随模块 dex 编译）、`native/`（ratel_native.h 单一源头） |
| `runtime/` | 注入进 apk 的运行时（AGP app：R8 dex + native so + 签名代理 + LSPlant + native API） |
| `toolchain/` | PC 端 CLI（Java 17）：init/build/pull/stub/patchdex/cert/hookable/unpack/install/doctor |
| `examples/` | demo-module、dev-plugin、native-module、e2e-host、split-app(+split-apks-tool) |
| `docs/` | cli-reference / project-yaml / module-development |

toolchain 内部分层（`com.ratel.toolchain`）：`command/`（picocli）、`project/`
（工程布局/yaml/workspace 生成/origin 输入规范化）、`apk/`（增量 zip）、`dex/`
（DexStore/stub 生成/dex 遮蔽/patch dex/verifier）、`inject/`（clinit 注入/ACF 改写/
manifest 解析/签名提取/防重入检测/还原器）、`decompile/`（jadx 程序化）、`compile/`
（javac/locate/d8 驱动）、`sign/`（apksig）。manifest 二进制编辑用 ratel 1.x 传下的
`org.jf.pxb`（内嵌 toolchain 源码树）。

## 核心机制

### dex 手术 = 改名遮蔽

patch dex 命名为 `classes.dex` 并物理排在 zip 最前；原 dex 条目按序数 +shift 改名
（classes.dex=1、classes2.dex=2…不存在 classes1.dex），字节零重写。
BaseDexClassLoader 按 zip 条目顺序解析 → patched 类遮蔽旧类；旧字节保留（可审计）。
取代全量重序列化（微信 12MB×16 dex 从 10 分钟降到 0）。patch dex 由 d8 产出天然
合规，无 65535 原位溢出问题。

### 入口注入（可插拔，project.yaml entry.scheme）

- **clinit**（默认）：改写 application 类（无自定义则 launcher activity）的 `<clinit>`
  最先调 `Bootstrap.bootstrap()`。注入器支持给无 clinit 类新增。
- **acf**：manifest `appComponentFactory` → `RatelAppComponentFactory`（链式委托原 ACF，
  原值记在 meta-data）。适合加固 App。

### 签名代理（核心卖点，默认开）

构建期 apksig 提取原始证书 → `assets/ratel/ratel-config.json` 下发 → Bootstrap 反射
替换 `ActivityThread.sPackageManager` 为动态代理 → 拦截 `getPackageInfo` 把
signatures/signingInfo 换成原始证书。开发证书仅用于安装，与 app 内签名自检无关。
未签名 origin（bundle 直出）自动跳过；`--unpack` 恢复时证书经工程根 `origin-certs.txt` 携带。

### API 分层（混淆即 API）

- **api 层即混淆层**：`com.ratel.api` 全单字符（a=入口接口 / b=加载参数 / c=hook
  回调（内嵌 d=param、e=unhook）/ f=能力接口 / g=静态分发点 / h=桥持有），语义只在
  源码 javadoc 不进字节码。插件引用符号无特征，可整体混淆（病毒库难命中）。
- **plugin-base 语义门面**：开发者写 `Ratel.hookMethod(m, new MethodHook(){...})`；
  门面类随模块 dex 分发、随模块混淆。模块 dex = 自身类 + plugin-base 类（api 由宿主提供）。
- **桥 = 静态赋值无反射**：Bootstrap 直接 `h.a = new HookProviderImpl()`。native 方法
  全收 runtime 内部类 `Natives`（RegisterNatives 传 `getName()` 运行时真名，类名参与
  R8 混淆，方法名保留供注册表匹配）。R8 keep 全部 api 类，runtime 内部类照常混淆。
- **SDK 导出剔除桥**：api 的 sdkJar 剔 `h.class`——插件编译不依赖桥（经 plugin-base
  预编译 jar），开发者不可见、无法二次赋值破坏。工具链资源 `ratel/sdk/` 内嵌两个 jar，
  init 时释放到工程 `sdk/`。

### 单模块模型

无 manager 即无多插件概念：一个工程至多一个模块 apk。发布态 `modules.embed`
（asset 固定 `assets/ratel/module.apk`）+ 开发态 `modules.dev`（包名直查已装小包，
构建期定向注入 `<queries>`，运行时经原始 IPackageManager binder 直查 sourceDir）。
模块加载参数仅 packageName/processName/classLoader。加载 = 释放（**必须
setReadOnly**，Android 10+ 拒载可写 dex/so）+ DexClassLoader(parent=宿主 loader，
api 类 parent-first 拦截)。

### hook 运行时（Java + native 双层）

- **Java hook = LSPlant**（社区上游内嵌，Android 16 剥 .symtab 场景靠自研三路符号
  解析：.symtab → .dynsym → .gnu_debugdata（xz-embedded **XZ_SINGLE 模式**——无内部
  内存分配，规避 scudo 分配器冲突；不调 xz_dec_end）。libart 定位用 `dl_iterate_phdr`
  零竞态）。
- **native API**（模块 so 导出 `ratel_module_init(api*)`，函数指针表传入零链接依赖）：
  `hook`（Dobby inline hook 返 trampoline）/ `unhook` / `resolve`（dlsym）/
  `watch_library`（已加载立即回调；否则 hook dlopen/android_dlopen_ext libdl stub
  等加载回调，一次性）/ `redirect_file`（文件级重定向）。头文件
  `sdk/native/ratel_native.h`，struct 追加字段必须放尾部（二进制兼容），api_version 递增。
- **hook 点注入**：短方法被 AOT 内联无法 hook → `ratel hookable` 在方法开头插静态
  分发调用（`g.a(String)`），模块 `Ratel.hookableDispatch` 拦截。

### crcBypass（apk 完整性校验对抗）

project.yaml 开关（默认关——origin apk 全量内嵌，体积近翻倍）。运行时释放 origin
（只读）→ native hook open/openat 把 `base.apk` 重定向到 origin → app 自校验读到
原始字节。**必须配套 ResourcesRebuilder**：重定向后若 AssetManager 沿用旧 zip 索引
（patched 的 central directory）配新内容体（origin），索引与内容错位 → resource 读
axml 直接 crash；且 Android 刷新机制判定 apk 变化会触发 application 重复加载（副作用）。
重建 = 清 mResourceImpls/mCachedApkAssets/mActivityResourceReferences/mResourceReferences
→ createResourcesImpl（**P+ 双参**：key + ApkAssetsSupplier）→ 回填所有
Resources.mResourcesImpl（保对象身份）。classloader 的 dex fd 在 hook 安装前已打开，
patched dex 不受重定向影响。**runtime 自身读 patched apk 必须豁免**：统一走
`RatelZip.open()`（构造瞬间 suspend/resume 重定向）——open hook 曾劫持 runtime 自己
导致模块静默丢失。

### Split APK（集合保持，禁合并）

不做 arsc/dex/manifest 合并（bug 海）。只手术 base，其余 split 原样重签
（install-multiple 要求全集合同签名），`ratel install` 一键安装。init 输入支持
单 apk / split 目录 / .xapk / .apks（bundletool）。DFM（feature split）因
AGP+Gradle 9 组合 bug 暂不可用（见坑录）。

### 工作区形态（IDE 完整工程）

init 生成标准 gradle 工程，全部产物走 AGP 标准链路（禁手写 d8/zip——R8 无法接入）：

- `:module` = **AGP com.android.application**：module.apk 即应用产物（R8 混淆 dex +
  cmake jni so + assets）。release `minifyEnabled` + proguard-rules.pro（keep 反射
  入口 `ratel.module.Main`；**`-repackageclasses ratel.m` 独占包**——默认根包 a/b
  会与宿主 runtime dex 的混淆名碰撞，parent-first 下父类被宿主劫持
  InstantiationError）。plugin-base 以**源码**释放进 src/main/java（可读可改；
  Ratel 经 sdk jar 内已编译的 bridge `i` 转发——i 对 h 的引用只在字节码常量池，
  h 不出 sdk jar，模块编译无感知，工程里零 api 源码）。`buildModuleApk` =
  Copy 产物到 `module/dist/`（build/ 为 AGP 输出域不可占用）。jni 始终构建
  （`src/main/jni`，ratel_native.h 同居）；接线开关 `modules.native` 运行时判定。
- `:app-patch` = **AGP com.android.library**：javac 产物经任务 API 执行期取
  destinationDirectory（AGP 9 任务注册晚于脚本求值，挂接用 matching().configureEach）；
  maven 依赖走 `ratelDeps` 专属 configuration；smali/ 挂 sourceSets 源根
  （SmaliIdea 可索引，javac 只认 .java 共存无碍）。
- root `ratelBuild` 聚合（mustRunAfter 防并行竞态）+ `ratelPull -Pcls=`。
- stub（宿主类编译依赖）：CLI 产出**内容 hash 命名** jar 到 `app-patch/libs/`
  （`ratel-stubs-<sha1前8>.jar` + 前缀特征清理；路径随内容变——gradle 必重编 +
  IDE 必重索引，解"路径不变内容变"陷阱；默认入仓）。
- 工作区含 AGENTS.md（AI 助手导读）+ .gitignore（产物忽略；origin.apk/keystore/
  sdk jar/stub jar 跟踪——协作可直接构建；>100MB 走 git-lfs）。
- 开发证书 `ratel-dev.keystore` 在工程根（单证书常态，无 cert/ 目录）。
- java 混编链：ratelStub（import 播种）→ javac → ratelPatchDex（relocate 到
  `ratel.repack.*`）→ 遮蔽合并。jadx 输出仅阅读参考；kotlin 混编不做。

## 构建与验证

```bash
./gradlew build                             # 全量（双 ABI native）
./gradlew :toolchain:installDist            # 工具链发布布局 + dist 资源自检（CI 同款）
./gradlew :examples:e2e-host:e2e            # 全链回归（需设备，ANDROID_SERIAL 可选）
```

- 环境：JDK 17+ / android-37 / NDK 30.0.16248370 / CMake 3.31.6。runtime 为 AGP
  `com.android.application` + `multiDexEnabled=false`（遮蔽要求恰一个 classes.dex），
  dex 取 `intermediates/dex/release/minifyReleaseWithR8/`，so 取 `stripped_native_libs`
  （剥符号 10.9MB→760KB）。AGP 9.4.1 / Gradle 9.7.1（AGP 不追新：对齐 Android Studio 支持上限，避免迫使用户升级 IDE）。
- **e2e 八断言**（改 runtime/注入/签名代理必须跑过再提交）：bootstrap done /
  lsplant init ok / module entry ok / onResume hook 真实触发 / crcBypass redirect /
  resources rebuilt / self-crc==origin / axml-read OK。无设备自动 skip。
- 发布：tag `v*` → release.yml → `ratel-<版本>.zip` 挂 GitHub Release。

## Android 兼容矩阵（实证）

| 版本 | 验证方式 | 结论 |
|---|---|---|
| 29 (10) | e2e 模拟器 | 全过（minSdk 边界；Writable dex 限制发源版本，setReadOnly 实证） |
| 31 (12) | 模拟器全功能 | 全过（含微信 256MB 全管线） |
| 33/34/35 | e2e 模拟器 | 全过 |
| 36 (16) | 真机 2211133C | 全过（含 native 三件套、dev 链路、crcBypass、split） |
| 37 (17) | 模拟器 | 全过（与 16 同 .gnu_debugdata 符号架构） |
| 30/32 | — | 未逐一跑（相邻版本覆盖） |

x86_64：双 ABI 产物已含（ELF64 解析与 dobby trampoline 无架构硬编码）；本机
aarch64 无法运行验证（QEMU 不支持），运行时验证靠 CI x86_64 模拟器接 e2e。

## 坑录（按主题）

### 构建链 / Gradle

- Gradle 9.7 移除 AGP 8.x 的 internal API（InternalProblems）——AGP 8.x 全系不可用，须 AGP 9+。
- DFM（dynamic-feature）applicationId 解析 `Collection is empty`：AGP 8.7.3 与 9.4 均在
  （Gradle 9 组合 bug），待上游修复；config splits 不受影响。
- `dynamicFeatures.add()` 对 SetProperty 静默无效，须 `+=`。
- root 脚本引用子工程任务须 `gradle.projectsEvaluated`（求值顺序）。
- workspace 内 `./gradlew` 失败时先看子进程完整输出（e2e 的 runCmd 捕获后携带打印）；
  `ANDROID_HOME` **空字符串**视同未设置（`?:` 不触发、`File("")` 列表空直接抛错）。
- `ProcessBuilder.inheritIO()` 必须在 `start()` 之前——放后面无效且管道无人读。
- bundletool lib 无 main（自建封装 `examples/split-apks-tool`）；BuildApksCommand 拒绝
  覆盖已存在输出；device-spec 必含 supportedLocales。
- Gradle Copy 暂存链的指纹不可靠（见铁律 1）；Jar 任务不清理历史产物，改输出布局后要 clean。

### 运行时 / native

- JNI 注册宿主是 `Natives` 类——新 native 方法声明错类会导致 RegisterNatives
  **整体失败**（连 lsplant 都起不来，且报错只在 logcat）。
- Android 10+ 拒载可写 dex/so：提取后必 `setReadOnly`。
- lsplant `import std` 展开后 atoi 需显式 include cstdlib；xz 必须 XZ_SINGLE（XZ_DYNALLOC
  与 scudo 冲突 SIGABRT）；Android 16 GetMethodShorty 已删（反射 fallback）。
- dobby 的 /proc/self/maps 直接 deref 有早期 SIGBUS 竞态，且 SELinux 拒开
  /proc/self/mem——libart 定位一律 `dl_iterate_phdr` + 文件 symtab pread。
- open/openat hook 会劫持 runtime 自己读 patched apk——`RatelZip` 豁免（见 crcBypass）。
- DFM 之外的 split base 匹配必须精确 `base-master.apk`（base-arm64 等配置包 zip 序
  可能更前；ZipEntry 每次枚举新实例只能按名比较）。

### 工具链 / 工程

- apk-parser 解析新 AGP 的 sparse resources.arsc 必崩（BufferUnderflow）——读路径全面
  pxb-axml 直读二进制 manifest（ApkInfo 降级 / ManifestParser.DumpNode），不碰资源表。
- snakeyaml 对共享空集合输出 `*id00x` anchor——config 各集合用独立 ArrayList。
- jadx stub 兜底 glob 千万不能用 `**`（微信 229k 类闭包 11 分钟）；兜底 = 源码包推导。
- 已处理 APK 再进 init/build 是静默损坏（签名代理指错证书/dex 层叠/ACF 自委托）——
  `PatchedApkDetector` 三特征拒绝；`--unpack` 从产物精确还原（front dex 判定：
  含 `com/ratel/` 类 ∨ 类被更后 dex 遮蔽，无需构建期记录）。
- e2e/workspace 每次重建生成新开发证书——安装前先 uninstall（幂等，且 PM 未就绪时
  容错）；模拟器 `install -r` 对同 versionCode 不同签名可能不刷新，用 uninstall+fresh。

### 设备相关

- 版本矩阵脚本化时注意 AVD 的 .ini 半损坏（删 .avd 目录重建）与 GPU 残留
  （杀 qemu 进程；-no-window）。

## 设计决策速查（为什么）

| 决策 | 理由 |
|---|---|
| split 不做合并 | arsc/dex/manifest table merge 是 bug 海（APKEditor 打磨多年）；集合重签 + install-multiple 即可 |
| 单模块模型 | 无 manager 即无多插件概念；modulePath/appPath 对单模块无意义 |
| API 全单字符 + plugin-base | api 层 keep 后无语义特征（病毒库难做），插件可整体混淆；开发者仍用可读 API |
| 桥静态赋值非反射 | 反射非 Java 第一推荐；混淆后反射字符串维护脆弱 |
| 改名遮蔽非重序列化 | 原 dex 字节零改动：性能（分钟→0）、dex2oat 重校验面最小、可回滚 |
| jadx 输出仅阅读 | 反编译重命名/语法糖展开不可往返；回编一律 smali；关键字包名 java 无法表达 |
| kotlin 混编不做 | 语法糖展开问题，收益不抵坑；需要时 smali |
| stub = dexlib2→ASM 直出 | R8 混淆的 `if`/`do` 关键字包字节码层合法；泛型 Signature 透传零损失；毫秒级 |
| clinit 默认 + acf 备选 | clinit 不动 manifest 最小侵入；加固 App 壳抢先时 acf 兜底 |
| 开发证书随机密码明文存 yaml | 本地开发资产，不是秘密；发布者可换 |

## 内嵌上游核心

| 组件 | 版本/commit | 许可 | 本地补丁（升级时重放，全部标注 ratel patch） |
|---|---|---|---|
| LSPlant | 6ac2280 | LGPL-3（动态链接合规见 NOTICE） | import std 展开为传统 include；find_package(cxx) 改可选 |
| Dobby | c1da031 | Apache-2.0 | ProcessRuntimeUtility 改 pread（见坑录 dobby 条） |
| dex_builder | ac7fb22 | Apache-2.0 | — |
| xz-embedded | — | 0BSD | XZ_SINGLE 集成方式本身 |
| pxb-axml | ratel 1.x 内嵌 | Apache-2.0 | 修复 1.x 两个隐藏 bug（AddQueriesForPackage 参数遮蔽、package 子元素命名空间） |

工具链 Maven 依赖：dexlib2（已停更，dex 039；ART 自 API 28 未新增指令，覆盖 29-37，
遇新格式需换 fork）、jadx-core 1.5.6（程序化调用）、apksig、commons-compress、picocli、
snakeyaml、hiddenapibypass（AAR）。

## 已知问题 / 待办

- `InitCommand` 双花括号内联调 CertCommand（hack，待重构为共享服务）。
- so 的 STORED+16KB 对齐未处理（runtime 注入的 so 走 assets 释放路径加载，不受影响；
  用户级文件替换功能已移除——dex 走混编/smali 正路、so 走 native hook、axml/资源不开放）。
- stub 生成不支持 enum/annotation 类（跳过并告警）。
- jadx 大 apk 首次全量索引秒级~十秒级，pull 单类也付此成本；待按需索引或进程内复用。
- CI 尚未接 x86_64 模拟器跑 e2e（x86_64 运行时验证缺口）。

## 维护流程

- 改 runtime/注入/签名代理 → 真机或模拟器跑 e2e（八断言）再提交，实测结果写进提交信息。
- 升级上游（lsplant/dobby）→ 重放 ratel patch → e2e + 真机冒烟。
- 新增 CLI 命令/参数 → 同步 `docs/cli-reference.md`（从 `ratel <cmd> --help` 实际输出
  整理）与 README 命令表；project.yaml 新字段同步 `docs/project-yaml.md`。
- 新增 native API 字段 → 只能加 struct 尾部 + api_version 递增 + 同步 ratel_native.h
  两份（toolchain 资源与 examples/native-module）。
