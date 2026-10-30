package com.ratel.plugin;

import com.ratel.api.c;

import java.lang.reflect.Member;

/**
 * hook 回调语义基类（xposed before/after 语义）。
 */
public abstract class MethodHook {

    /** 原方法调用前（可改 args / setResult / setThrowable 跳过原方法）。 */
    public void beforeHookedMethod(Param param) throws Throwable {
    }

    /** 原方法调用后（可读改 result / throwable）。 */
    public void afterHookedMethod(Param param) throws Throwable {
    }

    /** hook 参数与结果载体（底层对象的语义视图）。 */
    public static final class Param {
        final c.d raw;

        Param(c.d raw) {
            this.raw = raw;
        }

        /** 被 hook 的方法。 */
        public Member method() {
            return raw.a;
        }

        /** 非静态方法调用的接收者（静态方法为 null）。 */
        public Object thisObject() {
            return raw.b;
        }

        /** 方法实参（可写元素实现参数改写）。 */
        public Object[] args() {
            return raw.c;
        }

        /** 原方法返回值（before 阶段为 null）。 */
        public Object result() {
            return raw.d;
        }

        /** 设置返回值并跳过原方法（before 阶段使用）。 */
        public void setResult(Object value) {
            raw.a(value);
        }

        /** 原方法抛出的异常（after 阶段可读）。 */
        public Throwable throwable() {
            return raw.e;
        }

        /** 以异常中止原方法（before 阶段使用）。 */
        public void setThrowable(Throwable t) {
            raw.b(t);
        }
    }

    /** 适配到底层回调（每次 hook 一次适配，非每次调用）。 */
    c adapt() {
        return new c() {
            @Override
            public void a(c.d param) throws Throwable {
                beforeHookedMethod(new Param(param));
            }

            @Override
            public void b(c.d param) throws Throwable {
                afterHookedMethod(new Param(param));
            }
        };
    }
}
