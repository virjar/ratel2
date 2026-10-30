package com.ratel.plugin;

import com.ratel.api.c;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * hook 辅助工具（XposedHelpers 风格）：一行式 find+hook、反射调用与字段读写。
 * 走纯 Java 反射（hidden API 已由运行时豁免），随模块 dex 分发并混淆。
 */
public final class RatelHelpers {

    private RatelHelpers() {
    }

    // ---- 类与方法查找 ----

    /** 加载类（classLoader 传宿主的，见 HostApp.classLoader()）。 */
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("类不存在: " + className, e);
        }
    }

    /**
     * 一行式 hook（XposedHelpers 同形态）：可变参末位为回调，其余为参数类型
     * （Class 或类名字符串，无参方法只传回调）。例：
     * <pre>
     * RatelHelpers.findAndHookMethod("com.foo.Bar", app.classLoader(),
     *         "getTitle", String.class, new MethodHook() {
     *             ...afterHookedMethod(param) -> param.setResult("改了")
     *         });
     * </pre>
     */
    public static Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                           String methodName, Object... parameterTypesAndCallback) {
        return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                           Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof MethodHook)) {
            throw new IllegalArgumentException("最后一个参数必须是 MethodHook 回调");
        }
        Object[] parameterTypes = new Object[parameterTypesAndCallback.length - 1];
        System.arraycopy(parameterTypesAndCallback, 0, parameterTypes, 0, parameterTypes.length);
        Method method = Ratel.findMethodExact(clazz, methodName, parameterTypes);
        if (method == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName
                    + "（参数个数/类型不匹配？传类名字符串可解前向引用）");
        }
        return Ratel.hookMethod(method, (MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1]);
    }

    public static Method findMethodExact(String className, ClassLoader classLoader,
                                         String methodName, Object... parameterTypes) {
        return Ratel.findMethodExact(findClass(className, classLoader), methodName, parameterTypes);
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    // ---- 反射调用 ----

    /** 调对象方法（含私有/继承链，按名+实参个数匹配）。 */
    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            return findMethodByArgs(obj instanceof Class ? (Class<?>) obj : obj.getClass(), methodName, args)
                    .invoke(obj, args);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("callMethod 失败: " + methodName, e);
        }
    }

    /** 实例化（按实参个数匹配构造器）。 */
    public static <T> T newInstance(Class<T> clazz, Object... args) {
        try {
            for (java.lang.reflect.Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                if (ctor.getParameterTypes().length == args.length) {
                    ctor.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    T instance = (T) ctor.newInstance(args);
                    return instance;
                }
            }
            throw new NoSuchMethodError(clazz.getName() + " 无 " + args.length + " 参构造");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("newInstance 失败: " + clazz.getName(), e);
        }
    }

    // ---- 字段读写 ----

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读字段失败: " + fieldName, e);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("写字段失败: " + fieldName, e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读静态字段失败: " + fieldName, e);
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            findField(clazz, fieldName).set(null, value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("写静态字段失败: " + fieldName, e);
        }
    }

    // ---- 内部 ----

    private static Method findMethodByArgs(Class<?> clazz, String methodName, Object[] args) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.getParameterTypes().length != args.length) {
                    continue;
                }
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName + "/" + args.length + "参");
    }
}
