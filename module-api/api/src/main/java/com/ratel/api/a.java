package com.ratel.api;

/**
 * 模块入口接口（assets/xposed_init 声明的类需实现）。
 *
 * <p>api 层命名约定：类与方法一律单字符，语义只存在于 javadoc（不进字节码）——
 * api 层即是混淆层，插件代码可整体混淆，对 api 的引用符号不构成可检索特征。
 * 语义化 API 由 plugin-base 提供（随插件 dex 分发，随插件混淆）。
 */
public interface a {

    /**
     * 语义：handleLoadPackage(LoadPackageParam)。
     * 宿主每个进程启动时回调一次。
     */
    void a(b param) throws Throwable;
}
