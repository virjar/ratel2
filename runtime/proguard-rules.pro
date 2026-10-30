# ratel runtime release R8 rules
# 混淆目标：类名/方法名全部重命名，但保留 JNI 入口和反射访问点

# native 方法名必须保留（RegisterNatives 注册表按名匹配），但宿主类名可混淆——
# FindClass 用运行时 Class.getName() 动态取真名（RatelJNI 传参），改名不影响寻址
-keepclassmembers class * {
    native <methods>;
}

# api 层即混淆层（类/方法全单字符，语义只在源码 javadoc）——跨 dex 链接需要签名稳定，
# 但 keep 下来也无语义特征可检索；runtime 内部实现类照常参与 R8 混淆
-keep class com.ratel.api.** { *; }

# Bootstrap 是 clinit 注入的入口点（EntryInjector 硬编码类名）
-keep class com.ratel.runtime.Bootstrap { *; }

# RatelJNI 是 JNI 动态注册入口（唯一静态 JNI 命名保留点）
-keep class com.ratel.runtime.RatelJNI { *; }

# RatelAppComponentFactory 是 ACF 入口（manifest 硬编码类名）
-keep class com.ratel.runtime.RatelAppComponentFactory { *; }

# hiddenapibypass：反射操作 hidden API，保留全部
-keep class org.lsposed.hiddenapibypass.** { *; }

# LSPlant 反射调用 callback + HookProviderImpl 反射取 callback Method
-keep class com.ratel.runtime.HookerAdapter { *; }

# 保留注解（LSPlant 通过注解查找方法）
-keepattributes *Annotation*

# 保留泛型签名（部分反射操作依赖）
-keepattributes Signature

# 不警告
-dontwarn org.lsposed.hiddenapibypass.**
-dontwarn com.ratel.api.**
