# 模块开发指南

模块 = 你写的 hook 代码载体。工程里的 `module/` 就是模块项目（init 自带可运行模板），
可 java、可 native、可混合。

- 模块 apk 内嵌于宿主包（`modules.embed`），或开发期独立小包（`modules.dev`）
- 单模块模型：一个工程一个模块 apk，`assets/xposed_init` 可声明多个入口类
- 底层 API 是单字符混淆层（`com.ratel.api.a/b/c/...`），模块对 API 的引用符号无语义
  特征，模块代码可整体混淆（proguard 无需 keep 任何 ratel API）

## java 模块

入口类继承 `RatelModule`（`com.ratel.plugin` 包，随模块 dex 分发）：

```java
public class Main extends RatelModule {
    @Override
    protected void handleLoadPackage(HostApp app) throws Throwable {
        // 宿主每个进程启动回调一次
        // app.packageName() / app.processName() / app.classLoader()
    }
}
```

声明于 `module/src/main/assets/xposed_init`（每行一个类全名，AGP 标准 assets 位置）。

### hook API（Ratel / MethodHook）

```java
Class<?> cls = app.classLoader().loadClass("com.example.NetworkClient");
Method m = Ratel.findMethodExact(cls, "getResponse", String.class);

Ratel.hookMethod(m, new MethodHook() {
    @Override
    public void beforeHookedMethod(Param param) throws Throwable {
        Object[] args = param.args();          // 实参（可写元素改参）
        param.setResult("fake");               // 设返回值并跳过原方法
        // param.setThrowable(ex);             // 或以异常中止
    }

    @Override
    public void afterHookedMethod(Param param) throws Throwable {
        Object ret = param.result();           // 原方法返回值（可改）
        Throwable t = param.throwable();       // 原方法异常（可读可改）
        param.setResult("modified");
    }
});
```

| API | 说明 |
|---|---|
| `RatelHelpers.findAndHookMethod(...)` | **一行式 find+hook**（最常用）：`findAndHookMethod("com.foo.Bar", cl, "getTitle", String.class, callback)`——可变参末位为回调，参数类型传 Class 或类名字符串（无参方法只传回调） |
| `Ratel.hookMethod(Method, MethodHook)` | hook 单个方法，失败返回 null |
| `Ratel.hookAllMethods(Class, name, MethodHook)` | hook 全部同名重载 |
| `Ratel.findMethodExact(Class, name, params...)` | 精确查找（参数传 Class 或类名字符串） |
| `RatelHelpers.findClass(name, classLoader)` | 加载宿主类 |
| `RatelHelpers.callMethod(obj, name, args...)` | 反射调方法（含私有/继承链） |
| `RatelHelpers.getObjectField / setObjectField` | 实例字段读写（含私有） |
| `RatelHelpers.getStaticObjectField / setStaticObjectField` | 静态字段读写 |
| `RatelHelpers.newInstance(clazz, args...)` | 反射实例化 |
| `RatelHelpers.findField(clazz, name)` | 字段获取（自动 setAccessible） |
| `Ratel.hookableDispatch(MethodHook)` | 拦截 `ratel hookable` 注入的静态分发点 |
| `Unhook.unhook()` | 取消 hook |

hook 由 LSPlant 实现（art 方法入口改写），Android 10-17 实证支持。

### 编译与混淆

模块 SDK 在工程 `module/libs/` 下：`ratel-sdk.jar`（compileOnly，运行时由宿主提供）。
plugin-base 以**源码**形式在 `module/src/main/java/com/ratel/plugin/`——可读可导航可改，
随模块一起编译。Ratel 调用的底层能力经 sdk jar 内已编译的 bridge 类转发，桥的底层
实现类不出 sdk jar（运行时由宿主提供），插件编译期对此无感知——模块工程里没有
也不需要任何 api 层源码。
模块自己的代码随意混淆——plugin-base 类也在模块 dex 里一起混淆，无需 keep。

### 内联坑

短方法 / 被 AOT 内联的调用点无法 hook。注入显式 hook 点：

```bash
ratel hookable myproj 'com.example.ShortMethod#getVersion'
./gradlew ratelBuild
```

模块内 `Ratel.hookableDispatch(...)` 拦截（`param.args()[0]` 是注入 tag）。

## native 模块

在 `module/src/main/jni/` 写 C 代码（模板自带可运行示例，AGP cmake 始终构建）。
so 导出固定入口，runtime 加载后传入 API 函数指针表（**零动态链接依赖**，不用链
libratel.so）：

```c
#include "ratel_native.h"   // 与 C 源码同居 module/src/main/jni/

static void on_loaded(const char *path, void *handle) {
    // 目标 so 刚加载完，可以 hook 它的符号了
}

void ratel_module_init(const ratel_native_api_t *api) {
    // 1. inline hook（Dobby）：返回 trampoline（调用即原函数）
    void *orig = api->hook(target_addr, my_replacement);

    // 2. 符号解析（dlsym；lib 名传短名/全路径/NULL 全局）
    void *addr = api->resolve("libtarget.so", "secret_check");

    // 3. so 加载监听：已加载立即回调返回 1；否则等 dlopen 拦截回调返回 0（一次性）
    api->watch_library("libtarget.so", on_loaded);

    // 4. 文件级重定向：open(orig_path) 实际打开 new_path
    api->redirect_file("/data/...", "/data/.../fake");
}
```

构建零配置：`module/src/main/jni/` 存在即由 AGP cmake 自动编译打包（需 NDK），
产物自动进 module.apk 的 `lib/arm64-v8a/`。

与 java 入口可共存（java 先加载，native 后）；纯 native 模块（无 xposed_init）合法。
仓库 `examples/native-module` 是独立构建的同款示例（脱离工作区场景参考）。

## dev 模式快速迭代

`project.yaml` 配 `modules.dev: com.example.myplugin`，模块独立成可安装小包
（参考 `examples/dev-plugin` 样板：aapt2 manifest + dex + xposed_init + 签名）：

```bash
adb install -r myplugin.apk                      # 几 KB，0.3 秒
adb shell am force-stop com.example.app          # 重启宿主生效
```

宿主大包完全不动。

## 完整示例

仓库 `examples/`：

| 示例 | 内容 |
|---|---|
| `demo-module` | java 模块（hook 宿主业务方法 + Activity 探针） |
| `dev-plugin` | dev 模式独立小包（aapt2 + d8 + apksigner 全 gradle 化） |
| `native-module` | native 模块（watch/resolve/hook 三件套自验证） |
| `e2e-host` | e2e 全链回归宿主（`./gradlew :examples:e2e-host:e2e`） |
| `split-app` + `split-apks-tool` | App Bundle split 样本（bundletool 产 .apks） |
