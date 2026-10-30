# 贡献指南

## 开发环境

构建 ratel 本体需要：

- JDK 17+（完整 JDK）
- Android SDK：platforms;android-37、NDK 30.0.16248370、CMake 3.31.6（`sdkmanager` 安装）
- 磁盘余量 ~10GB（native 依赖较大）

```bash
git clone https://github.com/virjar/ratel2.git
cd ratel2
./gradlew build                  # 全量构建（含双 ABI native）
./gradlew :toolchain:installDist # 工具链发布布局 → toolchain/build/install/ratel/
```

## 项目结构

| 模块 | 内容 |
|---|---|
| `module-api` | 模块开发体系：`api`（单字符混淆层）、`plugin-base`（语义门面 Ratel/RatelHelpers/MethodHook/HostApp/RatelModule，源码随模块编译）、`native`（ratel_native.h 单一源头） |
| `runtime` | 宿主内运行时（AGP app 产 dex+so：Bootstrap/签名代理/LSPlant/Dobby/native API） |
| `toolchain` | PC 端 CLI（init/build/pull/stub/patchdex/cert/hookable/unpack/install/doctor 十个命令） |
| `examples/` | 模块与场景示例（demo-module/dev-plugin/native-module/e2e-host/split-app） |
| `docs/` | CLI 参考 / project.yaml 参考 / 模块开发指南 |

设计决策与坑录见 [AGENTS.md](AGENTS.md)（随代码演进持续更新）——改动涉及构建链、
产物链、注入机制时先读对应小节。

## 测试

- 全量构建必须绿：`./gradlew build`
- 全链回归（连着设备/模拟器，ANDROID_SERIAL 可选）：`./gradlew :examples:e2e-host:e2e`
  ——八条断言覆盖 bootstrap / lsplant / 模块加载 / hook 真实触发 / crcBypass 重定向 /
  资源重建 / apk 自校验 crc / axml 读取。无设备自动跳过。
- CI 会跑构建与产物自检；e2e 需要本地设备或接模拟器的 runner。

改动 runtime/注入/签名代理相关代码时，请务必在至少一台真机或模拟器上跑过 e2e
再提交。

## 代码约定

- 构建一律 gradle 化，不写 shell 脚本（跨平台）
- 构建产物零源码树：产物走 build 目录 + sourceSets 直连，禁止拷贝进 src/
- 依赖治理：稳定上游（LSPlant/Dobby 等）只内嵌社区上游核心代码，不引入私有 fork；
  新依赖优先 Maven 坐标
- 版本号两段式（如 2.0）
- 提交信息说明动机与验证方式（真机/模拟器实测结果写进去）

## 上游同步

`runtime/src/main/cpp/` 内嵌的 lsplant / dobby / xz 来自社区上游（commit 见 NOTICE），
本地补丁全部标注 `ratel patch`。升级时 diff 上游重放补丁，勿整目录覆盖。
