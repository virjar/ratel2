package com.ratel.api;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * hook 回调（xposed before/after 语义，精简版）。语义：RC_MethodHook。
 */
public abstract class c {

    /** 语义：beforeHookedMethod(MethodHookParam)。 */
    public void a(d param) throws Throwable {
    }

    /** 语义：afterHookedMethod(MethodHookParam)。 */
    public void b(d param) throws Throwable {
    }

    /**
     * hook 参数与结果载体。语义：MethodHookParam——
     * a=method，b=thisObject，c=args，d=result，e=throwable，f=returnEarly；
     * {@link #a(Object)}=setResult（设返回值并跳过原方法），{@link #b(Throwable)}=setThrowable。
     */
    public static class d {
        /** 语义：method——被 hook 的方法。 */
        public Member a;
        /** 语义：thisObject——非静态方法调用的接收者。 */
        public Object b;
        /** 语义：args——方法实参。 */
        public Object[] c;
        /** 语义：result——after 阶段为原方法返回值；before 阶段可设置。 */
        public Object d;
        /** 语义：throwable——原方法抛出的异常（after 阶段可读可改）。 */
        public Throwable e;
        /** 语义：returnEarly——before 中置 true 跳过原方法。 */
        public boolean f;

        /** 语义：setResult(Object)。 */
        public void a(Object result) {
            this.d = result;
            this.f = true;
        }

        /** 语义：setThrowable(Throwable)。 */
        public void b(Throwable t) {
            this.e = t;
            this.f = true;
        }
    }

    /** unhook 句柄。语义：Unhook——a()=getMethod()，b()=unhook()。 */
    public static class e {
        private final Member a;

        public e(Member method) {
            this.a = method;
        }

        /** 语义：getMethod()。 */
        public Member a() {
            return this.a;
        }

        /** 语义：unhook()。 */
        public void b() {
            if (this.a instanceof Method) {
                h.a.b((Method) this.a);
            }
        }
    }
}
