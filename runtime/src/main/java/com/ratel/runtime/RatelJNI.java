package com.ratel.runtime;

/**
 * JNI 动态注册桥——Java 侧传入 native 方法宿主类名，native 侧 FindClass + RegisterNatives。
 * 相比静态 JNI 命名的优势：so 里不暴露 Java 类名/方法名（grep 不到符号）。
 */
public final class RatelJNI {

    /**
     * 注册 Natives 的 native 方法。
     * 在 System.load(libratel.so) 之后、任何 native 调用之前执行。
     */
    public static void ensureRegistered() {
        // Natives.class.getName() 取运行时真实名（R8 混淆后依然可寻）
        registerNatives(Natives.class.getName());
    }

    public static native boolean registerNatives(String nativesClassName);

}
