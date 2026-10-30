package com.ratel.plugin;

import com.ratel.api.c;
import com.ratel.api.g;
import com.ratel.api.i;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * ratel hook 语义门面——插件开发者主要入口。
 *
 * <p>本类随插件 dex 分发并参与插件混淆；对底层单字符 api 的引用不构成可检索特征。
 */
public final class Ratel {

    private Ratel() {
    }

    /** hook 一个方法；失败返回 null。 */
    public static Unhook hookMethod(Method target, MethodHook hook) {
        if (target == null || hook == null) {
            return null;
        }
        c.e unhook = i.a(target, hook.adapt());
        return unhook == null ? null : new Unhook(unhook);
    }

    /** hook 类的全部同名方法（重载）。 */
    public static List<Unhook> hookAllMethods(Class<?> clazz, String name, MethodHook hook) {
        List<Unhook> result = new ArrayList<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                Unhook u = hookMethod(m, hook);
                if (u != null) {
                    result.add(u);
                }
            }
        }
        return result;
    }

    /** hook 静态分发点（ratel hookable 命令注入的调用目标），args()[0] 为注入 tag。 */
    public static Unhook hookableDispatch(MethodHook hook) {
        Method dispatch = findMethodExact(g.class, "a", String.class);
        return dispatch == null ? null : hookMethod(dispatch, hook);
    }

    /**
     * 精确查找方法（参数类型传 Class 或 String 类名）。
     * 例：findMethodExact(cl.loadClass("com.foo.Bar"), "getTitle")
     */
    public static Method findMethodExact(Class<?> clazz, String name, Object... parameterTypes) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != parameterTypes.length) {
                continue;
            }
            boolean match = true;
            Class<?>[] params = m.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                Object want = parameterTypes[i];
                String wantName = want instanceof Class
                        ? ((Class<?>) want).getName() : String.valueOf(want);
                if (!params[i].getName().equals(wantName)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
