package com.ratel.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * IPackageManager 动态代理：拦截 getPackageInfo 系列，把返回值里的签名信息替换为
 * 构建期提取的原始 apk 证书（默认过证书检查的核心机制）。
 *
 * <p>覆盖的检查路径：app 内绝大多数证书校验最终走
 * {@code PackageManager.getPackageInfo(pkg, GET_SIGNATURES|GET_SIGNING_CERTIFICATES)}，
 * 在 app 进程内即被本代理改写。native 层直读 apk 字节的校验由 crcBypass 的
 * native open/openat 重定向处理。</p>
 */
final class SignatureProxy implements InvocationHandler {

    private final Object original;
    private final String[] originCertB64;
    private volatile Object cachedSignatureArray; // Signature[]，构造一次复用
    private static int interceptCount = 0; // 前 3 次拦截打日志，证明代理真实生效
    private static volatile Object lastOriginalBinder; // dev 插件查询复用原 binder

    SignatureProxy(Object original, String[] originCertB64) {
        this.original = original;
        lastOriginalBinder = original;
        this.originCertB64 = originCertB64;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object result;
        try {
            result = method.invoke(original, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
        String name = method.getName();
        if (result == null || !(name.equals("getPackageInfo")
                || name.equals("getPackageInfoAsUser")
                || name.equals("getCurrentSnapshotCookie"))) {
            return result;
        }
        try {
            patchPackageInfo(result);
            if (interceptCount < 3) {
                interceptCount++;
                Bootstrap.log("signature proxy intercepted " + name + " #" + interceptCount
                        + " (signatures replaced with origin)");
            }
        } catch (Throwable t) {
            Bootstrap.log("patch PackageInfo failed: " + t);
        }
        return result;
    }

    private void patchPackageInfo(Object packageInfo) throws Exception {
        Object signatures = buildSignatures();
        if (signatures == null) {
            return;
        }
        setField(packageInfo, "signatures", signatures);

        // signingInfo (API 28+)：内部 SigningInfo 持有真实数据，直接置空可能触发 NPE，
        // 尝试构造；失败则保留（signatures 字段是老路径，多数检查兼容）
        Object signingInfo = packageInfo.getClass().getField("signingInfo").get(packageInfo);
        if (signingInfo != null) {
            tryPatchSigningInfo(signingInfo, signatures);
        }
    }

    private void tryPatchSigningInfo(Object signingInfo, Object signatures) {
        try {
            Class<?> sigInfoClass = signingInfo.getClass();
            // 真实结构：SigningInfo.mSigningDetails(SigningDetails) → past/curr 签名数组
            Object details = getField(sigInfoClass, signingInfo, "mSigningDetails");
            if (details != null) {
                setField(details, "pastSigningCertificates", null);
                setField(details, "signatures", signatures);
                setField(details, "schemeVersion", 0x7fffffff); // 覆盖所有 scheme 位
            }
            setField(signingInfo, "mSigningDetails", details);
        } catch (Throwable ignored) {
        }
    }

    private Object buildSignatures() throws Exception {
        Object cached = cachedSignatureArray;
        if (cached != null) {
            return cached;
        }
        Class<?> signatureClass = Class.forName("android.content.pm.Signature");
        Object array = java.lang.reflect.Array.newInstance(signatureClass, originCertB64.length);
        for (int i = 0; i < originCertB64.length; i++) {
            java.lang.reflect.Constructor<?> ctor = signatureClass
                    .getConstructor(byte[].class);
            java.lang.reflect.Array.set(array, i, ctor.newInstance(
                    Base64.getDecoder().decode(originCertB64[i])));
        }
        cachedSignatureArray = array;
        return array;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = findField(target.getClass(), name);
        if (f != null) {
            f.set(target, value);
        }
    }

    private static Object getField(Class<?> clazz, Object target, String name) throws Exception {
        java.lang.reflect.Field f = findField(clazz, name);
        return f == null ? null : f.get(target);
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** 从 config json 抽取 originCerts 数组（轻量手写解析，避免引入 json 库）。 */
    static String[] parseOriginCerts(String json) {
        if (json == null) {
            return null;
        }
        int idx = json.indexOf("\"originCerts\"");
        if (idx < 0) {
            return null;
        }
        int start = json.indexOf('[', idx);
        int end = json.indexOf(']', start);
        if (start < 0 || end < 0) {
            return null;
        }
        List<String> certs = new ArrayList<>();
        for (String piece : json.substring(start + 1, end).split(",")) {
            String v = piece.trim().replaceAll("^\"|\"$", "").trim();
            if (!v.isEmpty()) {
                certs.add(v);
            }
        }
        return certs.toArray(new String[0]);
    }

    /**
     * dev 插件查询：绕过客户端 PackageManager 直接调原 IPackageManager binder
     * （宿主 manifest 构建期已注 QUERY_ALL_PACKAGES，服务端可见性放行）。
     */
    public static String queryInstalledApkPath(Object originalBinder, String packageName) {
        try {
            int userId = android.os.Process.myUid() / 100000;
            Object info = null;
            for (Method m : originalBinder.getClass().getMethods()) {
                if (!m.getName().equals("getApplicationInfo")) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                // API 33+: (String, long, int)；API 29-32: (String, int, int)
                if (pts.length == 3 && pts[0] == String.class && pts[2] == int.class) {
                    if (pts[1] == long.class) {
                        info = m.invoke(originalBinder, packageName, 0L, userId);
                    } else if (pts[1] == int.class) {
                        info = m.invoke(originalBinder, packageName, 0, userId);
                    } else {
                        continue;
                    }
                    break;
                }
            }
            if (info == null) {
                return null;
            }
            for (Class<?> c = info.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("sourceDir");
                    f.setAccessible(true);
                    return (String) f.get(info);
                } catch (NoSuchFieldException ignored) {
                }
            }
            return null;
        } catch (Throwable t) {
            Bootstrap.log("queryInstalledApkPath failed: " + t);
            return null;
        }
    }

    /** 解析 config 里的 devPackage（单模块模型，与 originCerts 同款手写解析）。 */
    static String parseDevPackage(String json) {
        if (json == null) {
            return null;
        }
        int idx = json.indexOf("\"devPackage\"");
        if (idx < 0) {
            return null;
        }
        int start = json.indexOf('"', idx + 12);
        int end = start >= 0 ? json.indexOf('"', start + 1) : -1;
        if (start < 0 || end < 0) {
            return null;
        }
        String v = json.substring(start + 1, end).trim();
        return v.isEmpty() ? null : v;
    }


    /** 解析 config 的 nativeHook 开关（缺省 true——so 始终打包，默认接线）。 */
    static boolean parseNativeHook(String json) {
        if (json == null) {
            return true;
        }
        int idx = json.indexOf("\"nativeHook\"");
        if (idx < 0) {
            return true;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return true;
        }
        // 值域截到行尾/下个键，含 false 即为关
        int lineEnd = json.indexOf('\n', colon);
        String value = json.substring(colon + 1, lineEnd > 0 ? lineEnd : Math.min(colon + 12, json.length()));
        return !value.contains("false");
    }

    /** 原始 IPackageManager binder（代理安装后仍可直查，绕过客户端包装层）。 */
    public static Object originalBinder() {
        return lastOriginalBinder;
    }
}
