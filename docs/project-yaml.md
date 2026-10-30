# project.yaml 配置参考

工程根目录的 `project.yaml` 是 ratel 的全部配置。IDE 里直接编辑，下次 `./gradlew ratelBuild` 生效。

```yaml
package: com.example.app          # 只读（init 时从 APK 解析）
versionName: "1.2.3"              # 只读
versionCode: 45                   # 只读

entry:
  scheme: clinit                  # clinit（默认）| acf —— 注入方案

signing:
  keystore: ratel-dev.keystore
  alias: ratel
  storePassword: <init 时随机生成写回>

stubs:
  includes: []                    # stub 闭包种子（源码 import 自动补种，手动扩闭包用）
  # - 'com.example.**'

modules:
  embed: module/build/module.apk  # 内嵌模块 apk（单模块模型；默认指向工程 module/ 产物）
  dev: null                       # dev 插件包名（按包名直查已装 apk，快速迭代）
  native: true                    # native hook 接线开关（so 始终构建打包；false 时运行时
                                  # 不加载模块 native so——watch/redirect 会操作 linker）

crcBypass: false                  # apk 完整性校验对抗（origin apk 全量内嵌，体积翻倍）

runtime:
  version: none                   # 预留
```

## 字段详解

### entry.scheme

- **clinit**（默认）：改写 application 类（无自定义 application 则 launcher activity）
  的 `<clinit>`，最先执行 `Bootstrap.bootstrap()` 再走原逻辑。绝大多数 App 用这个。
- **acf**：改写 manifest `appComponentFactory` → `RatelAppComponentFactory`（链式委托
  原 ACF）。适合加固 App（壳抢先于 application 类初始化，clinit 被阻挡）。

### signing

构建期签名用开发证书（自动生成）。原 App 的签名校验由**签名代理**处理：构建期提取
原始证书写入运行时配置，运行时代理 `IPackageManager.getPackageInfo` 把 signatures
替换为原始证书——app 内一切签名自检默认通过，与这里的开发证书无关。

### modules.embed / modules.dev（单模块模型）

一个工程至多一个模块 apk：

- **embed**：发布态，模块 apk 内嵌进宿主包。默认值指向工程 `module/` 的构建产物，
  `./gradlew ratelBuild` 自动先构建模块。
- **dev**：开发态，填一个独立安装的插件包名（如 `com.example.myplugin`）。宿主大包
  不动，改模块只重装几 KB 小包 + 重启宿主，秒级迭代。manifest 会自动定向注入
  `<queries>` 保证包可见。两者可同时配置。

模块 apk 形态：zip 容器，`classes.dex` + `assets/xposed_init`（每行一个入口类），
可选 `lib/arm64-v8a/<so>` + `assets/native_init`（原生模块）。

### crcBypass

开启后构建期把**原始 apk 完整内嵌**，运行时把 app 对 `base.apk` 的文件访问
（open/openat）重定向到原始字节——app 的 crc/hash 完整性自检读到的一直是原版内容。
代价：包体积接近翻倍。配套自动重建 Resources（防 zip 索引错位 crash）。

### stubs.includes

java 混编编译需要宿主类的 stub（签名闭包）。工程任务链按 `app-patch/` 源码的 import
自动播种；当你需要引用未被 import 的类（如反射目标、注解类）时手动追加 glob。
