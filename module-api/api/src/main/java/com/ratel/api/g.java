package com.ratel.api;

/**
 * 静态 hook 分发点（ratel hookable 命令注入的调用目标）。语义：hookableDispatch 宿主。
 * 默认无操作（零开销）；hook 本方法可实现拦截（HookParam.args()[0] 即注入的 tag）。
 */
public class g {

    /** 语义：hookableDispatch(String tag)。 */
    @SuppressWarnings("unused")
    public static void a(String tag) {
        // no-op by default
    }
}
