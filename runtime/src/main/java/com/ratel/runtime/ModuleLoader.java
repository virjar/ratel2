package com.ratel.runtime;

import com.ratel.api.a;
import com.ratel.api.b;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模块加载（单模块模型：一个 app 至多一个模块 apk）。
 * 发布态：assets/ratel/module.apk 内嵌（含 classes.dex + assets/xposed_init 的 zip）。
 * 开发态：ratel-config.json devPackage 按包名直查已装插件（快速迭代——只装小包，宿主大包不动）。
 */
final class ModuleLoader {

    private static final String MODULE_ASSET = "assets/ratel/module.apk";

    static void load(String ownApkPath, String packageName, String processName) {
        boolean nativeHookEnabled = SignatureProxy.parseNativeHook(readConfig(ownApkPath));
        boolean devLoaded = false;
        String devPkg = SignatureProxy.parseDevPackage(readConfig(ownApkPath));
        if (devPkg != null) {
            Object binder = SignatureProxy.originalBinder();
            String path = binder != null ? SignatureProxy.queryInstalledApkPath(binder, devPkg) : null;
            if (path == null || !new File(path).exists()) {
                Bootstrap.log("dev 模块未安装: " + devPkg);
            } else {
                Bootstrap.log("dev 模块: " + devPkg + " → " + path);
                devLoaded = loadModule(new File(path), packageName, processName, nativeHookEnabled) > 0;
            }
        }

        boolean embeddedLoaded = false;
        File moduleApk = new File("/data/data/" + packageName + "/files/ratel/module.apk");
        if (Bootstrap.extractAsset(ownApkPath, MODULE_ASSET, moduleApk)) {
            embeddedLoaded = loadModule(moduleApk, packageName, processName, nativeHookEnabled) > 0;
        }
        Bootstrap.log("module loaded (dev=" + devLoaded + ", embedded=" + embeddedLoaded + ")");
    }

    /**
     * 原生模块：assets/native_init 每行一个 so 文件名（如 libmymodule.so），
     * 对应 lib/arm64-v8a/<so>。提取（只读——Android 10+ 拒载可写 so）→ System.load →
     * 调用其 ratel_module_init 传入 native API 表。可与 java 入口共存。
     */
    private static void loadNativeEntries(File apk, String packageName, boolean enable) {
        if (!enable) {
            Bootstrap.log("native 模块未接线（modules.native=false）");
            return;
        }
        List<String> soNames = new ArrayList<>();
        try (ZipFile zip = RatelZip.open(apk)) {
            ZipEntry init = zip.getEntry("assets/native_init");
            if (init == null) {
                return;
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(zip.getInputStream(init), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        soNames.add(line);
                    }
                }
            }
        } catch (Throwable t) {
            Bootstrap.log("read native_init failed → " + t);
            return;
        }

        File libDir = new File("/data/data/" + packageName + "/files/ratel/lib-modules");
        for (String so : soNames) {
            try {
                File out = new File(libDir, so);
                if (!extractZipEntry(apk, "lib/arm64-v8a/" + so, out)) {
                    Bootstrap.log("native 模块缺 lib/arm64-v8a/" + so);
                    continue;
                }
                System.load(out.getPath());
                boolean ok = Natives.nativeModuleInit(out.getPath());
                Bootstrap.log("native 模块 init: " + so + " → " + (ok ? "ok" : "无 ratel_module_init 入口"));
            } catch (Throwable t) {
                Bootstrap.log("native 模块失败: " + so + " → " + t);
            }
        }
    }

    /** 从模块 apk zip 提取条目到文件（大小一致跳过；Android 10+ 要求只读）。 */
    private static boolean extractZipEntry(File apk, String entryName, File out) throws Exception {
        try (ZipFile zip = RatelZip.open(apk)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return false;
            }
            if (out.exists() && out.length() == entry.getSize()) {
                out.setReadOnly();
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
            out.setReadOnly();
            return out.exists();
        }
    }

    private static int loadModule(File apk, String packageName, String processName, boolean nativeHookEnabled) {
        List<String> entryClasses = new ArrayList<>();
        try (ZipFile zip = RatelZip.open(apk)) {
            ZipEntry init = zip.getEntry("assets/xposed_init");
            if (init == null) {
                // 纯 native 模块（无 java 入口）：直接走 native 加载
                loadNativeEntries(apk, packageName, nativeHookEnabled);
                return 1;
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(zip.getInputStream(init), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        entryClasses.add(line);
                    }
                }
            }
        } catch (Throwable t) {
            Bootstrap.log("read module failed: " + apk.getName() + " → " + t);
            return 0;
        }

        File odex = new File(apk.getParentFile(), "odex");
        ClassLoader appCl = Bootstrap.class.getClassLoader();
        // parent 挂宿主 classloader：api 类由 runtime dex 提供，模块自带 api 类会被 parent-first 拦截
        DexClassLoader moduleCl = new DexClassLoader(
                apk.getPath(), odex.getPath(), null, appCl);

        b param = new b();
        param.a = packageName;
        param.b = processName;
        param.c = appCl;

        int loaded = 0;
        for (String entry : entryClasses) {
            try {
                Class<?> clazz = Class.forName(entry, true, moduleCl);
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (instance instanceof a) {
                    ((a) instance).a(param);
                    loaded++;
                    Bootstrap.log("module entry ok: " + entry + " @" + apk.getName());
                } else {
                    Bootstrap.log("module entry 未实现入口接口: " + entry);
                }
            } catch (Throwable t) {
                Bootstrap.log("module entry 失败: " + entry + " → " + t);
            }
        }

        loadNativeEntries(apk, packageName, nativeHookEnabled);
        return loaded;
    }

    private static String readConfig(String apkPath) {
        try (ZipFile zip = RatelZip.open(apkPath)) {
            ZipEntry entry = zip.getEntry("assets/ratel/ratel-config.json");
            if (entry == null) {
                return null;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
