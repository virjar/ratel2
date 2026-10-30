package com.ratel.plugin;

import com.ratel.api.a;
import com.ratel.api.b;

/**
 * 模块语义入口：业务模块继承本类并实现 {@link #handleLoadPackage(HostApp)}，
 * assets/xposed_init 声明业务子类的全限定名。
 *
 * <p>本类随插件 dex 分发，参与插件混淆——继承关系经底层单字符接口维持，
 * 混淆后符号无语义特征。
 */
public abstract class RatelModule implements a {

    @Override
    public final void a(b param) throws Throwable {
        handleLoadPackage(new HostApp(param));
    }

    /** 宿主加载回调：每个进程启动时一次。 */
    protected abstract void handleLoadPackage(HostApp app) throws Throwable;
}
