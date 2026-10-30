package com.envdetector.demo;

import com.ratel.plugin.HostApp;
import com.ratel.plugin.MethodHook;
import com.ratel.plugin.Ratel;
import com.ratel.plugin.RatelModule;

import java.lang.reflect.Method;

/**
 * demo 模块（plugin-base 语义 API）：
 * hook DetectorManager.getDetectors 返回空列表 + hook Activity.onResume（每次启动必然
 * 触发，作为 hook 链路自证据）。
 */
public class DemoEntry extends RatelModule {

    @Override
    protected void handleLoadPackage(HostApp app) throws Throwable {
        android.util.Log.i("RatelDemoModule", "module loaded in " + app.packageName()
                + " process=" + app.processName());

        // hook 链路自证据：onResume 必然被调用
        Method onResume = Ratel.findMethodExact(android.app.Activity.class, "onResume");
        Ratel.hookMethod(onResume, new MethodHook() {
            @Override
            public void afterHookedMethod(Param param) {
                android.util.Log.i("RatelDemoModule",
                        "Activity.onResume hooked! -> " + param.thisObject().getClass().getName());
            }
        });

        Class<?> manager = app.classLoader().loadClass("com.envdetector.core.DetectorManager");
        Method getDetectors = Ratel.findMethodExact(manager, "getDetectors");
        if (getDetectors == null) {
            android.util.Log.e("RatelDemoModule", "getDetectors not found");
            return;
        }
        Ratel.hookMethod(getDetectors, new MethodHook() {
            @Override
            public void afterHookedMethod(Param param) {
                android.util.Log.i("RatelDemoModule",
                        "getDetectors hooked! original=" + ((java.util.List<?>) param.result()).size()
                        + " items → replacing with empty");
                param.setResult(java.util.Collections.emptyList());
            }
        });
        android.util.Log.i("RatelDemoModule", "hook getDetectors: OK");
    }
}
