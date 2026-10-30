package com.ratel.api;

/**
 * 模块加载参数。语义：LoadPackageParam。
 */
public class b {

    /** 语义：packageName——宿主包名。 */
    public String a;

    /** 语义：processName——当前进程名（含 :xxx 后缀）。 */
    public String b;

    /** 语义：classLoader——宿主 classloader（全部 dex 可见，含 patch dex）。 */
    public ClassLoader c;
}
