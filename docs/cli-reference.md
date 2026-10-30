# CLI 命令参考

ratel CLI 位于发布包 `bin/ratel`（源码构建产物在 `toolchain/build/install/ratel/`）。
日常开发主要用 gradle 任务（工程内 `./gradlew ratelBuild` / `ratelPull`），CLI 命令
是同一能力的直接入口，适合脚本化场景。

全局约定：`<project>` 指 ratel 工程目录（`ratel init` 生成的那个）。

## init —— 展开 APK 为工程

```bash
ratel init app.apk -o myproj
```

| 参数 | 说明 |
|---|---|
| `<apk>` | 原始 APK：单 `.apk` / split apk 目录 / `.apks`（bundletool）/ `.xapk`（APKPure）|
| `-o, --output` | 工程输出目录（必须不存在或为空） |
| `--entry=<scheme>` | 入口注入方案，见下 |
| `--unpack` | 输入是已处理产物时剥离注入层，用还原的原始基准 init |
| `--full` | 全量反编译 smali（默认按需 pull，暂未实现全量） |

入口注入方案（之后可在 project.yaml 改）：

| 方案 | 原理 | 适用 |
|---|---|---|
| `clinit`（默认） | 改写 application / launcher activity 的 `<clinit>` | 绝大多数 App |
| `acf` | 改写 manifest `appComponentFactory` | 加固 App（clinit 被壳阻挡时） |

已处理过的 APK 会被拒绝（防静默损坏的二次处理：签名代理指向错误证书 / dex 层叠 /
ACF 自委托）。确要从产物恢复：`--unpack`（front dex 精确剥离，原 dex 改名归位，
原始证书携带到新工程，上一轮 patch/模块不迁移）。

## build —— 出包

```bash
ratel build myproj              # 全流程：patch dex 合并 + 入口注入 + 签名
```

| 参数 | 说明 |
|---|---|
| `--no-sign` | 产出未签名包 |
| `-o, --output` | 输出路径（默认 `out/<pkg>-v<ver>-ratel.apk`） |

日常建议用工程内 `./gradlew ratelBuild`（自动先编译 `module/` 与 `app-patch/`）。
split APK 工程会同时重签全部 splits 到 `out/`。

## pull —— 按需反编译

```bash
ratel pull myproj com.example.MainActivity --java     # jadx → app-patch/src/main/java/
ratel pull myproj com.example.GiantMethod --smali     # baksmali → smali/
ratel pull myproj com.example.Bar --smali --stdout    # 打印不落盘
```

gradle 等价：`./gradlew ratelPull -Pcls=com.example.Bar [-Psmali]`。
注意：jadx 输出未必可直接编译，编辑后构建期会报错定位；smali 输出可直接回编。

## stub —— 生成 compileOnly stub jar

```bash
ratel stub myproj -i 'com.example.**' -i 'com.other.Util'
```

从原 APK dex 抽取指定包/类的签名闭包，生成 stub jar 到 `app-patch/libs/`
（内容 hash 命名 `ratel-stubs-<hash>.jar`，自动清理旧版本——文件名随内容变化，
gradle 与 IDE 必然感知）。工程 gradle 任务链会在编译前自动执行（以源码 import
为种子），此命令用于手动扩大闭包。

## patchdex —— patch dex 生成

```bash
ratel patchdex myproj --classes-dir app-patch/build/classes/java/main [--dep-jars a.jar,b.jar]
```

gradle 编译产物 + maven 依赖 jar → 整体 relocate 到 `ratel.repack.*`（防与宿主内
同名库冲突）→ d8 → `build/ratel/patch-dexes/`。工程任务链自动调用。

## cert —— 开发证书

```bash
ratel cert myproj        # 生成 ratel-dev.keystore（工程根），随机密码写回 project.yaml
```

init 时自动执行；换证书删掉 keystore 再跑即可。

## hookable —— 注入静态 hook 点

```bash
ratel hookable myproj 'com.example.ShortMethod#getVersion'
./gradlew ratelBuild
```

在目标方法开头插入静态分发调用，方法不再被 AOT 内联，LSPlant hook 稳定生效。
模块侧用 `Ratel.hookableDispatch(...)` 拦截（native 侧 resolve `g.a`）。

## unpack —— 还原原始 APK

```bash
ratel unpack myproj      # → out/unpacked-origin.apk（拷贝工作区不可变基准）
```

## install —— 安装产物

```bash
ratel install myproj [-s <serial>]
```

自动定位 adb（ANDROID_HOME → 默认 SDK 路径 → PATH），单包走 install，split 工程
走 `install-multiple -r`（自动排除 -unsigned 中间产物）。

## doctor —— 环境自检

```bash
ratel doctor
```

检查 JDK / android.jar / 发布包资源齐备性，缺什么提示装什么。
