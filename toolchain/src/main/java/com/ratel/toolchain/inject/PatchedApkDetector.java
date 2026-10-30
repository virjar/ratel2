package com.ratel.toolchain.inject;

import com.ratel.toolchain.dex.DexStore;

import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 检测 APK 是否已被 ratel 处理过。
 *
 * <p>二次处理的产物是静默损坏的：签名代理会指向上一轮的开发证书（而非 app 原始证书，
 * 校验必失败）、dex 逐层 +1 堆叠旧 runtime 死代码、ACF 方案自委托死循环——
 * 因此 init 与 build 双入口都拒绝已处理 APK，特征任一命中即判定。
 */
public final class PatchedApkDetector {

    private static final String BOOTSTRAP_CLASS = "com.ratel.runtime.Bootstrap";
    private static final String CONFIG_ENTRY = "assets/ratel/ratel-config.json";
    private static final String MODULE_ENTRY = "assets/ratel/module.apk";

    private PatchedApkDetector() {
    }

    /**
     * @return 命中的特征说明；null 表示未检出
     */
    public static String detect(Path apk) throws Exception {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            if (zip.getEntry(CONFIG_ENTRY) != null) {
                return "含 " + CONFIG_ENTRY + "（ratel 运行时配置）";
            }
            if (zip.getEntry(MODULE_ENTRY) != null) {
                return "含 " + MODULE_ENTRY + "（ratel 内嵌模块）";
            }
        }
        // dex 类特征：任何 dex 里出现 runtime 入口类（防仅注入 runtime 未带 config 的中间态）
        try (DexStore store = new DexStore(apk)) {
            if (store.allClassNames().contains(BOOTSTRAP_CLASS)) {
                return "dex 含 " + BOOTSTRAP_CLASS + "（ratel runtime）";
            }
        }
        // manifest 特征：ACF 已被替换为 ratel 类。
        // 非 apk 输入（.apks/.xapk 容器、目录形态）无 manifest 可解析——跳过，交由上层规范化
        try {
            String acf = ManifestParser.appComponentFactoryName(apk);
            if (ReplaceAppComponentFactory.RATEL_ACF_CLASS.equals(acf)) {
                return "appComponentFactory 已是 " + RATEL_ACF_SHORT + "（ratel ACF 入口）";
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static final String RATEL_ACF_SHORT = "RatelAppComponentFactory";

    /** 拒绝提示（命令行输出用）。 */
    public static String rejectMessage(String reason) {
        return "该 APK 已被 ratel 处理过，不能二次处理（" + reason + "）。\n"
                + "  二次处理的产物会静默损坏：签名代理指向错误证书、dex 层叠膨胀、ACF 自委托。\n"
                + "  请使用原始 APK；需要改配置请在原工程上调整后重新 build。";
    }
}
