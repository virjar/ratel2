package com.ratel.plugin;

import com.ratel.api.b;

/**
 * 宿主进程上下文（语义包装，构造仅 runtime 入口适配使用）。
 */
public final class HostApp {

    private final b param;

    HostApp(b param) {
        this.param = param;
    }

    /** 宿主包名。 */
    public String packageName() {
        return param.a;
    }

    /** 当前进程名（含 :xxx 后缀）。 */
    public String processName() {
        return param.b;
    }

    /** 宿主 classloader（全部 dex 可见，含 patch dex）。 */
    public ClassLoader classLoader() {
        return param.c;
    }
}
