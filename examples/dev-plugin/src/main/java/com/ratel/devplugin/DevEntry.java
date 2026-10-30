package com.ratel.devplugin;

import com.ratel.plugin.HostApp;
import com.ratel.plugin.MethodHook;
import com.ratel.plugin.Ratel;
import com.ratel.plugin.RatelModule;

import java.lang.reflect.Method;

/**
 * dev 插件（plugin-base 语义 API）：
 * hook TabItem.getTitle 给 tab 标题加后缀——UI 直接可见，无需重装宿主大包。
 */
public class DevEntry extends RatelModule {

    @Override
    protected void handleLoadPackage(HostApp app) throws Throwable {
        android.util.Log.i("RatelDevPlugin", "dev plugin loaded in " + app.packageName()
                + " process=" + app.processName());
        Class<?> tabItem = app.classLoader().loadClass("com.envdetector.TabItem");
        Method getTitle = Ratel.findMethodExact(tabItem, "getTitle");
        Ratel.hookMethod(getTitle, new MethodHook() {
            @Override
            public void afterHookedMethod(Param param) {
                android.util.Log.i("RatelDevPlugin", "getTitle hooked: " + param.result());
                param.setResult(String.valueOf(param.result()) + "-DEVPLUGIN-V2");
            }
        });
    }
}
