package com.ratel.runtime;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ratel 2.0 loader 入口（由入口注入方案的 clinit 调用拉起）。
 *
 * <p>纯 Java、零第三方依赖（hiddenapibypass 打入 dex）。职责：</p>
 * <ol>
 *   <li><b>签名代理（默认开启）</b>——重打包后绝大多数 app 因证书校验自杀（apktool 被弃用的
 *       根因）。方案：反射替换 ActivityThread.sPackageManager 为动态代理，
 *       拦截 getPackageInfo 系列，把返回的 signatures/signingInfo 替换为构建期提取的原始签名。</li>
 *   <li>LSPlant hook 运行时初始化（libratel.so 加载 + 符号解析 + JNI 注册）。</li>
 *   <li>crcBypass（可选）：origin apk 释放 + fd 重定向 + Resources 重建。</li>
 *   <li>模块加载（发布态 assets 内嵌 / 开发态按包名直查已装插件）。</li>
 * </ol>
 *
 * <p>时机：application 类 clinit——早于任何应用代码（含 Application.onCreate），
 * 早于 ContextImpl 第一次 getPackageManager() 缓存，保证代理先落位。</p>
 */
public final class Bootstrap {

    private static final String TAG = "RatelLoader";
    public static final String CONFIG_ASSET = "assets/ratel/ratel-config.json"; // zip 条目名含 assets/ 前缀

    private static boolean installed = false;

    public static void bootstrap() {
        if (installed) {
            return; // clinit 幂等
        }
        installed = true;
        log("ratel bootstrap start");
        try {
            // 豁免 hidden API 限制（工程工具场景，全量豁免）
            HiddenApiBypass.setHiddenApiExemptions("L");
        } catch (Throwable t) {
            log("hidden api exempt failed: " + t);
        }
        try {
            installSignatureProxy();
        } catch (Throwable t) {
            log("signature proxy failed: " + t);
        }
        try {
            initHookRuntime();
        } catch (Throwable t) {
            log("hook runtime failed: " + t);
        }
        log("ratel bootstrap done");
    }

    /** hook 运行时：释放 libratel.so -> System.load -> LSPlant init -> 加载内嵌模块。 */
    private static void initHookRuntime() {
        String apkPath = ownApkPath();
        String pkg = packageName();
        if (apkPath == null || pkg == null) {
            log("hook runtime skip: apk/pkg unknown");
            return;
        }
        String abi = android.os.Build.SUPPORTED_ABIS != null
                && android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        File soDir = new File("/data/data/" + pkg + "/files/ratel/lib");
        File soFile = new File(soDir, "libratel.so");
        if (!extractAsset(apkPath, "assets/ratel/lib/" + abi + "/libratel.so", soFile)) {
            log("libratel.so missing for abi " + abi + ", hook runtime disabled");
            return;
        }
        try {
            System.load(soFile.getPath());
        } catch (Throwable t) {
            log("System.load failed: " + t);
            return;
        }
        // JNI 动态注册（在任何 native 调用之前）
        try {
            RatelJNI.ensureRegistered();
        } catch (Throwable t) {
            log("JNI registerNatives failed: " + t);
            return;
        }
        boolean ok = Natives.nativeInit();
        log("lsplant init: " + ok);

        // crcBypass：app 自校验读 base.apk → fd 重定向到原始字节。
        // 必须在桥注入/模块加载前完成（模块/宿主代码随时可能读 apk 校验），
        // 且重定向后立即重建 Resources（zip 索引与内容体一致指向 origin，防 axml crash）
        installCrcBypass(apkPath, pkg);

        if (ok) {
            // 桥静态注入（无反射）：api 能力自此可用
            com.ratel.api.h.a = new HookProviderImpl();
            ModuleLoader.load(apkPath, pkg, processName());
        }
    }

    /**
     * crcBypass：assets/ratel/origin.apk 存在（构建期 crcBypass 开关注入）则启用。
     * 释放原始 apk（只读）→ native open 重定向 base.apk → origin → 重建 Resources。
     */
    private static void installCrcBypass(String apkPath, String pkg) {
        try {
            String codePath = apkPath;
            File originApk = new File("/data/data/" + pkg + "/files/ratel/origin.apk");
            if (!extractAsset(apkPath, "assets/ratel/origin.apk", originApk)) {
                return; // 未启用（默认关——完整 origin 内嵌使包体积接近翻倍）
            }
            log("crcBypass: redirect " + codePath + " -> origin.apk");
            Natives.nativeRedirectFile(codePath, originApk.getPath());
            ResourcesRebuilder.rebuild(codePath);
        } catch (Throwable t) {
            log("crcBypass failed: " + t);
        }
    }

    /** 释放 apk 内 asset 到文件（大小一致则跳过；ModuleLoader 复用）。 */
    static boolean extractAsset(String apkPath, String entryName, File out) {
        try (ZipFile zip = RatelZip.open(apkPath)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return false;
            }
            if (out.exists() && out.length() == entry.getSize()) {
                out.setReadOnly(); // Android 10+ 拒绝加载可写 dex（升级自旧版可写文件的场景也覆盖）
                return true;
            }
            out.getParentFile().mkdirs();
            File tmp = new File(out.getPath() + ".tmp");
            try (InputStream in = zip.getInputStream(entry);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(out)) {
                out.delete();
                tmp.renameTo(out);
            }
            // Android 10+ 拒绝加载可写 dex/so（module.apk 走 DexClassLoader、libratel.so 走
            // System.load，均要求只读；顺带消掉 "Attempt to load writable file" 警告）
            out.setReadOnly();
            return out.exists();
        } catch (Throwable t) {
            log("extractAsset failed: " + t);
            return false;
        }
    }

    static String packageName() {
        String pn = processName();
        if (pn != null) {
            int colon = pn.indexOf(':');
            return colon > 0 ? pn.substring(0, colon) : pn;
        }
        String apk = ownApkPath();
        if (apk != null) {
            int start = apk.indexOf("/data/app/");
            if (start >= 0) {
                String rest = apk.substring(start + "/data/app/".length());
                String[] parts = rest.split("/");
                if (parts.length > 1) {
                    String seg = parts[1];
                    int dash = seg.lastIndexOf('-');
                    return dash > 0 ? seg.substring(0, dash) : seg;
                }
            }
        }
        return null;
    }

    static String processName() {
        try {
            Object at = currentActivityThread();
            if (at != null) {
                return (String) at.getClass().getMethod("getProcessName").invoke(at);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---- 签名代理 ----

    private static void installSignatureProxy() throws Exception {
        String apkPath = ownApkPath();
        if (apkPath == null) {
            log("cannot locate own apk, skip");
            return;
        }
        String config = readConfig(apkPath);
        String[] certB64 = SignatureProxy.parseOriginCerts(config);
        if (certB64 == null || certB64.length == 0) {
            log("no origin certs in config, skip");
            return;
        }
        Object activityThread = currentActivityThread();
        if (activityThread == null) {
            log("ActivityThread not ready, skip");
            return;
        }
        Class<?> ipmClass = Class.forName("android.content.pm.IPackageManager");
        Field spmField = findField(activityThread.getClass(), "sPackageManager");
        if (spmField == null) {
            log("sPackageManager field not found");
            return;
        }
        Object original = spmField.get(null);
        if (original == null) {
            log("sPackageManager is null (not used yet) — proxy will miss, TODO hook creation");
            return;
        }
        if (Proxy.isProxyClass(original.getClass())) {
            return; // 已安装
        }
        Object proxy = Proxy.newProxyInstance(
                Bootstrap.class.getClassLoader(),
                new Class[]{ipmClass},
                new SignatureProxy(original, certB64));
        spmField.set(null, proxy);
        // 已存在的 ApplicationPackageManager 实例里的 mPM 也要换（未来创建的会用 sPackageManager）
        patchExistingPackageManagers(activityThread, ipmClass, proxy);
        log("signature proxy installed, originCerts=" + certB64.length);
    }

    private static void patchExistingPackageManagers(Object activityThread, Class<?> ipmClass, Object proxy) {
        try {
            // ContextImpl.getPackageManager 缓存在 mPackageManager；app clinit 阶段通常尚未创建，
            // 这里只处理已存在的情况（防御）
            java.lang.reflect.Method getApplication = activityThread.getClass()
                    .getMethod("getApplication");
            Object app = getApplication.invoke(activityThread);
            if (app == null) {
                return;
            }
            Object context = app.getClass().getMethod("getApplicationContext").invoke(app);
            if (context == null) {
                return;
            }
            Field pmField = findField(context.getClass(), "mPackageManager");
            if (pmField == null) {
                return;
            }
            Object apm = pmField.get(context);
            if (apm == null) {
                return;
            }
            Field mpmField = findField(apm.getClass(), "mPM");
            if (mpmField != null) {
                mpmField.set(apm, proxy);
                log("patched existing ApplicationPackageManager.mPM");
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object currentActivityThread() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        try {
            return at.getMethod("currentActivityThread").invoke(null);
        } catch (Exception e) {
            // clinit 阶段 currentActivityThread 可能尚未赋值——直接取 sCurrentActivityThread
            Field f = findField(at, "sCurrentActivityThread");
            return f == null ? null : f.get(null);
        }
    }

    /** 通过 BaseDexClassLoader.pathList.dexElements[].path 定位自身 apk。 */
    private static String ownApkPath() {
        try {
            ClassLoader cl = Bootstrap.class.getClassLoader();
            Field pathListField = findField(cl.getClass(), "pathList");
            if (pathListField == null) {
                return null;
            }
            Object pathList = pathListField.get(cl);
            Field elementsField = findField(pathList.getClass(), "dexElements");
            if (elementsField == null) {
                return null;
            }
            Object[] elements = (Object[]) elementsField.get(pathList);
            for (Object element : elements) {
                Field pathField = findField(element.getClass(), "path");
                if (pathField == null) {
                    continue;
                }
                Object pathValue = pathField.get(element);
                String path;
                if (pathValue instanceof File) {
                    path = ((File) pathValue).getPath(); // API 26+ Element.path 是 File
                } else if (pathValue instanceof String) {
                    path = (String) pathValue;
                } else {
                    continue;
                }
                if (path != null && path.endsWith(".apk")) {
                    return path;
                }
            }
        } catch (Throwable t) {
            log("ownApkPath failed: " + t);
        }
        return null;
    }

    private static String readConfig(String apkPath) {
        try (ZipFile zip = RatelZip.open(apkPath)) {
            ZipEntry entry = zip.getEntry(CONFIG_ASSET);
            if (entry == null) {
                return null;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            log("readConfig failed: " + t);
            return null;
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    static void log(String msg) {
        android.util.Log.i(TAG, msg);
    }
}
