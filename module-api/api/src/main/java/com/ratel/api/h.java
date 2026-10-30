package com.ratel.api;

/**
 * runtime→api 桥持有。语义：APIBridge。
 *
 * <p>runtime 启动时直接静态赋值实现对象（无反射），插件侧经 plugin-base 门面访问。
 *
 * <p>本类在 SDK 导出（sdkJar）时剔除：插件工程编译不依赖它（plugin-base 已预编译），
 * 运行时由 runtime dex 提供；插件开发者不可见，无法二次赋值破坏桥。
 */
public class h {

    /** hook 能力实现（runtime 赋值）。 */
    public static f a;
}
