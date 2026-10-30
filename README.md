# ratel 2.0

> PC 端 APK 二次开发工具链——写 Java 改 App，不是写 smali。

## 它解决什么问题

你想改一个 App 的行为，传统路径是：apktool 反编译 → 手改 smali → 回编 → 签名 → 安装。这条路径的死穴是：**smali 写错一行，运行时才崩，排查极难**；而且 App 的签名自检让你重打包后大概率闪退。

ratel 2.0 把 APK 展开成**一个标准的 gradle 工程**：IDEA / Android Studio 打开就是完整项目，写 Java（有语法提示和补全）、配 maven 依赖、跑任务出包。签名自检默认通过（构建期提取原证书，运行时代理还原）。短函数被内联无法 hook？一条命令注入显式 hook 点。

三种改造能力可组合：

- **java 混编**：pull 宿主类为 Java 源码直接改，重编译替换原类；
- **hook 模块**：工程自带的 `module/` 插件项目，类 Xposed API，LSPlant 方法 hook，模块可整体混淆不暴露特征；
- **smali 回编**：指令级修改，配 SmaliIdea 插件在 IDE 里直接导航编辑。

## 五分钟上手

### 环境

JDK 17+、Android SDK（需要 `android.jar`，通常你已有）。手机无需 root。

支持 Android 10-17（arm64 真机 + 模拟器实证；x86_64 产物已含，覆盖模拟器/云手机场景）：
Java 方法 hook、native inline hook、签名代理、crc 校验对抗、split APK——各版本实测矩阵见
[AGENTS.md](AGENTS.md)。

### 1. 获取 ratel

从 [Releases](../../releases) 下载 `ratel-<版本>.zip` 解压，然后：

```bash
export RATEL_HOME=/path/to/ratel     # 解压出的 ratel 目录
$RATEL_HOME/bin/ratel doctor
```

也可以从源码构建（需要 NDK 30 / CMake 3.31.6 / android-37）：

```bash
git clone https://github.com/virjar/ratel2.git
cd ratel2 && ./gradlew :toolchain:installDist
export RATEL_HOME="$(pwd)/toolchain/build/install/ratel"
```

### 2. 展开 APK 为工程

```bash
$RATEL_HOME/bin/ratel init app.apk -o myproj
```

IDEA / Android Studio 打开 `myproj`——这是一个完整的 gradle 工程：

```
myproj/
├── project.yaml          # ratel 配置（入口方案/签名/模块——IDE 里直接改）
├── AGENTS.md             # 工程导读（给 AI 助手或新协作者）
├── origin.apk            # 原始 APK（不可变基准）
├── ratel-dev.keystore    # 开发签名证书（自动生成）
├── module/               # hook 模块（AGP application：R8 混淆产出 module.apk）
│   ├── src/main/java/ratel/module/Main.java    # 模块入口（模板开箱即用）
│   ├── src/main/java/com/ratel/plugin/         # plugin-base 源码（可读可改）
│   ├── src/main/jni/     # native 模块（AGP cmake 始终构建，C 源码与 API 头文件同居）
│   ├── src/main/assets/  # xposed_init / native_init（AGP 标准位置）
│   └── libs/             # ratel-sdk.jar
├── app-patch/            # java 混编（AGP library）
│   ├── src/main/java/    # 你 pull 的类放这里
│   └── libs/             # 宿主类 stub（内容 hash 命名，自动生成）
├── smali/                # smali 补丁（挂为源根，SmaliIdea 可索引导航）
└── out/                  # 产物
```

### 3. 开始改

| 你想做的事 | 怎么做 |
|---|---|
| 写 hook 模块（java） | 直接改 `module/src/main/java/ratel/module/Main.java`（模板 hook 了 onResume 打日志，装上就能看到效果） |
| 写 hook 模块（native） | 改 `module/src/main/jni/nativemodule.c`（AGP cmake 始终构建；`project.yaml` 的 `modules.native: false` 可关闭运行时接线——native hook 会操作 linker） |
| 改宿主某个类 | `./gradlew ratelPull -Pcls=com.example.MainActivity`，Java 出现在 `app-patch/` 里，直接编辑 |
| 看指令级代码 | `./gradlew ratelPull -Pcls=com.example.GiantMethod -Psmali` |
| 加 maven 依赖 | `app-patch/build.gradle.kts` 用 `ratelDeps("...")` 声明，自动 relocate 到 `ratel.repack.*` 不与原 App 冲突 |
| 换注入方案 | `project.yaml` 的 `entry.scheme`：`clinit`（默认，绝大多数 App）/ `acf`（加固 App） |

### 4. 出包

```bash
./gradlew ratelBuild
# 产物: out/*-ratel.apk，adb install 即可
```

签名自检？默认通过。Hook 运行时？已注入。模块加载？自动。

## hook 模块长什么样

```java
public class Main extends RatelModule {
    @Override
    protected void handleLoadPackage(HostApp app) {
        RatelHelpers.findAndHookMethod("com.example.NetworkClient",
                app.classLoader(), "getResponse", new MethodHook() {
                    @Override
                    public void afterHookedMethod(Param param) {
                        param.setResult("modified");   // 拦截返回值
                    }
                });
    }
}
```

底层 API 是单字符混淆层，模块代码可整体混淆——分发物里没有可检索的 API 特征。

### native hook 模块

`module/src/main/jni/` 就是 native 模块（AGP cmake 始终构建，`nativemodule.c` 模板
含可运行示例）。so 导出 `ratel_module_init`，runtime 加载后传入 native API 表
（inline hook / 符号解析 / so 加载监听 / 文件重定向）——不用自己啃 libart 解析和
hook 框架：

```c
#include "ratel_native.h"   // 与 C 源码同居 module/src/main/jni/

static void on_so_loaded(const char *path, void *handle) {
    // 目标 so 已加载，可以 hook 它的符号了
}

void ratel_module_init(const ratel_native_api_t *api) {
    api->watch_library("libtarget.so", on_so_loaded);  // 等 so 加载再 hook
    void *addr = api->resolve("libtarget.so", "secret_check");
    api->hook(addr, my_replacement);   // Dobby inline hook
}
```

native hook 会操作 linker——介意风险可在 `project.yaml` 配 `modules.native: false`
关闭运行时接线（so 仍构建打包，java hook 不受影响）。

### 短函数被内联？注入 hook 点

```bash
ratel hookable myproj 'com.example.ShortMethod#getVersion'
./gradlew ratelBuild
# 这个方法不会被 AOT 内联了，hook 可以正常工作
```

### 快速迭代（改模块不重装 App）

`project.yaml` 配 `modules.dev: com.example.myplugin`，之后只装几 KB 的插件小包：

```bash
adb install -r plugin.apk        # 0.3 秒
adb shell am force-stop com.example.app && adb shell am start ...
```

## smali 指令级修改

有些改动在 Java 层表达不了（巨方法中间改几条指令、绕过某段校验逻辑）：

```bash
./gradlew ratelPull -Pcls=com.example.GiantMethod -Psmali
# 编辑 smali/com/example/GiantMethod.smali
./gradlew ratelBuild
```

smali 目录随工程回编（baksmali → dex → 同一条遮蔽合并管线）。配 SmaliIdea 插件可在
IDEA/AS 里直接导航与编辑 smali 文件。jadx 输出的 Java 仅用于阅读参考，回编走 smali。

## apk 完整性校验对抗（crcBypass）

很多 app 会读自己的 apk 做 crc/hash 自校验，重打包后必然失败。project.yaml 开启：

```yaml
crcBypass: true
```

构建时把原始 apk 完整内嵌，运行时把 app 对 `base.apk` 的文件访问重定向到原始字节——
校验读到的一直是原版内容。代价是包体积接近翻倍，按需开启。

配套处理了重定向的经典坑（zip 索引与内容体错位导致 resource 读 axml crash）：重定向安装后
立即重建 Resources（索引与 fd 一致指向原始 apk）。Java/native hook 模块还能用
`api->redirect_file(源, 目标)` 做任意文件级重定向。

## Split APK（App Bundle）

App Bundle 分发的 app（`pm path` 拉出来是 base + 一堆 split）直接把**集合或 .apks/.xapk 容器**喂给 init：

```bash
ratel init app.apks -o myproj      # 或 split apk 所在目录 / .xapk
./gradlew ratelBuild               # 只手术 base，其余 split 原样重签
ratel install myproj               # adb install-multiple 一键安装
```

不做 dex/资源/manifest 合并（那条路产物不可控）——split 保持原样，安装走集合。

## 更多命令

| 命令 | 作用 |
|---|---|
| `ratel init app.apk -o proj` | 展开工程（已处理过的 APK 会被拒绝；`--unpack` 可从产物还原再 init） |
| `ratel build proj` | 出包（日常用 `./gradlew ratelBuild` 即可） |
| `ratel pull proj cls --java/--smali` | 按需反编译（gradle 任务 `ratelPull` 同） |
| `ratel hookable proj 'cls#method'` | 注入 hook 点 |
| `ratel unpack proj` | 还原原始 APK |
| `ratel install proj [-s serial]` | 安装产物（split 走 install-multiple） |
| `ratel doctor` | 环境自检 |

完整参数与说明：[docs/cli-reference.md](docs/cli-reference.md)；工程配置全字段：
[docs/project-yaml.md](docs/project-yaml.md)；模块开发（java/native API）：
[docs/module-development.md](docs/module-development.md)。

## 性能

微信 8.0.72（256MB / 16 dex / 229,526 类）实测：init 2.5 秒，java 混编全管线 5.7 秒，恒等重建 1.6 秒。

## 与 ratel 1.x 的关系

ratel 2.0 是 [ratel（平头哥）](https://github.com/virjarRatel/ratel-core) 的新一代版本。上一代的核心资产（RDP 重打包、Rposed API、签名对抗经验）全部继承，产品形态从"免 root hook 框架 + manager"进化为"PC 端工程化二开工具链"。砍掉了上一代的分身/多开、群控、云端热发等生态形态。

## 使用边界

ratel 面向自有应用的开发调试、授权范围内的安全研究与学习。使用者需遵守所在地法律与
目标 App 的许可协议——对未授权 App 的破解、规避付费、数据窃取等用途与本工具无关。
详见 [SECURITY.md](SECURITY.md)。

## 参与贡献

开发环境、测试与代码约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## License

Apache-2.0（内嵌 LSPlant LGPL-3、Dobby Apache-2.0，详见 [NOTICE](NOTICE)）
