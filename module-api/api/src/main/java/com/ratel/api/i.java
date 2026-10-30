package com.ratel.api;

import java.lang.reflect.Method;

/**
 * bridge 门面（sdk 内部操作，插件侧不可见的底层细节止于此）。
 * 语义：bridge——plugin-base 经本类转发到底层能力；底层桥持有类不出 sdk jar
 * （运行时由宿主 runtime dex 提供），本类对它的引用仅存在于字节码常量池，
 * 插件 javac 编译期无感知。
 */
public class i {

    private i() {
    }

    /** 语义：hookMethod(target, handler)——转发到底层能力接口。 */
    public static c.e a(Method target, c handler) {
        return h.a.a(target, handler);
    }
}
