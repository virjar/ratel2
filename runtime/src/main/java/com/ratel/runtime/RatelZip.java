package com.ratel.runtime;

import java.util.zip.ZipFile;

/**
 * runtime 内部打开 apk 的统一入口：ZipFile 构造瞬间挂起重定向（crcBypass 场景
 * open 已被劫持到 origin——runtime 自己必须读 patched apk：module.apk/libratel.so/
 * 配置都在 patched 内），fd 打开后立即恢复（后续读走已开 fd，不再触发 open）。
 */
final class RatelZip {

    private RatelZip() {
    }

    static ZipFile open(String path) throws Exception {
        return open(new java.io.File(path));
    }

    static ZipFile open(java.io.File file) throws Exception {
        try {
            Natives.nativeRedirectSuspend();
        } catch (Throwable ignored) {
        }
        try {
            return new ZipFile(file);
        } finally {
            try {
                Natives.nativeRedirectResume();
            } catch (Throwable ignored) {
            }
        }
    }
}
