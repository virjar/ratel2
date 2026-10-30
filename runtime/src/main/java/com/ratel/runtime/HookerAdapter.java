package com.ratel.runtime;

import com.ratel.api.c;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * LSPlant hooker 适配：LSPlant 回调约定为 {@code public Object callback(Object[] args)}
 * （args[0] 为非静态方法的 this），此处翻译为 api 回调（c）的 before/after 语义。
 */
public final class HookerAdapter {

    final c handler;
    final Method target;
    volatile Method backup; // nativeHook 成功后回填

    public HookerAdapter(c handler, Method target) {
        this.handler = handler;
        this.target = target;
    }

    @SuppressWarnings("unused") // LSPlant 反射调用
    public Object callback(Object[] args) throws Throwable {
        c.d param = new c.d();
        param.a = target;
        boolean isStatic = Modifier.isStatic(target.getModifiers());
        if (args == null || args.length == 0) {
            param.b = null;
            param.c = new Object[0];
        } else if (isStatic) {
            param.b = null;
            param.c = args;
        } else {
            param.b = args[0];
            param.c = new Object[args.length - 1];
            System.arraycopy(args, 1, param.c, 0, param.c.length);
        }

        try {
            handler.a(param);
        } catch (Throwable t) {
            Bootstrap.log("beforeHookedMethod 异常: " + t);
            param.f = true;
            param.d = null;
        }

        if (!param.f) {
            try {
                param.d = backup.invoke(param.b, param.c);
            } catch (InvocationTargetException e) {
                param.e = e.getCause() != null ? e.getCause() : e;
            } catch (Throwable t) {
                param.e = t;
            }
        }

        try {
            handler.b(param);
        } catch (Throwable t) {
            Bootstrap.log("afterHookedMethod 异常: " + t);
        }

        if (param.e != null) {
            throw param.e;
        }
        return param.d;
    }
}
