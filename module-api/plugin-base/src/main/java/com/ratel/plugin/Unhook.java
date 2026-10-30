package com.ratel.plugin;

import com.ratel.api.c;

import java.lang.reflect.Member;

/** unhook 语义句柄。 */
public final class Unhook {

    private final c.e raw;

    Unhook(c.e raw) {
        this.raw = raw;
    }

    /** 被 hook 的方法。 */
    public Member method() {
        return raw.a();
    }

    /** 取消 hook。 */
    public void unhook() {
        raw.b();
    }
}
