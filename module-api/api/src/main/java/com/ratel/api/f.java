package com.ratel.api;

import java.lang.reflect.Method;

/**
 * hook 能力抽象（runtime 实现并经 h 桥注入）。语义：HookProvider。
 * a(Method, c)=hookMethod（失败返回 null），b(Method)=unhook，c(Method)=deoptimize。
 */
public interface f {

    /** 语义：hookMethod(target, handler)。 */
    c.e a(Method target, c handler);

    /** 语义：unhookMethod(target)。 */
    boolean b(Method target);

    /** 语义：deoptimize(method)。 */
    boolean c(Method method);
}
