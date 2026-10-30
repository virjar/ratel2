package com.ratel.runtime;

import com.ratel.api.c;
import com.ratel.api.f;

import java.lang.reflect.Method;

/**
 * hook 能力实现（runtime 内部，经 h 桥静态注入 api）。
 * LSPlant 细节、native 调用、适配器构造全部收在本类——插件只见 plugin-base 语义门面。
 */
final class HookProviderImpl implements f {

    @Override
    public c.e a(Method target, c handler) {
        if (target == null || handler == null) {
            return null;
        }
        try {
            target.setAccessible(true);
            // LSPlant 回调约定：hooker 持有 public Object callback(Object[] args)
            HookerAdapter adapter = new HookerAdapter(handler, target);
            Method callback = HookerAdapter.class.getMethod("callback", Object[].class);
            Method backup = (Method) Natives.nativeHookMethod(target, adapter, callback);
            if (backup == null) {
                return null;
            }
            adapter.backup = backup; // native hook 成功后回填，callback 中调原方法
            return new c.e(target);
        } catch (Throwable t) {
            Bootstrap.log("hookMethod failed: " + target + " → " + t);
            return null;
        }
    }

    @Override
    public boolean b(Method target) {
        return Natives.nativeUnhook(target);
    }

    @Override
    public boolean c(Method method) {
        return Natives.nativeDeoptimize(method);
    }
}
