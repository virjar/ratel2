package com.ratel.runtime;

import java.lang.reflect.Method;

/**
 * native 方法宿主（libratel.so：LSPlant + Dobby）。
 * RegisterNatives 动态注册（RatelJNI 传本类运行时真实名——R8 混淆后依然可寻），
 * 方法名经注册表按名匹配故不可混淆（proguard keep native methods）。
 * 本类不进 api：插件可见层只有能力抽象（RatelHook/b 桥）。
 */
final class Natives {

    private Natives() {
    }

    static native boolean nativeInit();

    static native Object nativeHookMethod(Method target, Object hooker, Method callback);

    static native boolean nativeUnhook(Method target);

    static native boolean nativeDeoptimize(Method method);

    /** 调用模块 so 的 ratel_module_init 入口（传 native API 表；so 已由 System.load 加载）。 */
    static native boolean nativeModuleInit(String soPath);

    /** 文件级重定向（crcBypass）：open(origPath) 实际打开 newPath。 */
    static native boolean nativeRedirectFile(String origPath, String newPath);

    /** 重定向豁免（runtime 内部读 patched apk 时挂起重定向）。 */
    static native boolean nativeRedirectSuspend();

    static native boolean nativeRedirectResume();
}
