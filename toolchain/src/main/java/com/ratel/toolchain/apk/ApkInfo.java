package com.ratel.toolchain.apk;

import net.dongliu.apk.parser.ApkFile;

import java.io.File;
import java.io.IOException;

/** AndroidManifest 元信息解析（包名/版本），仅用于工程初始化与命名。 */
public final class ApkInfo {

    public final String packageName;
    public final String versionName;
    public final long versionCode;
    public final String label;

    private ApkInfo(String packageName, String versionName, long versionCode, String label) {
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.label = label;
    }

    public static ApkInfo parse(File apk) throws IOException {
        try (ApkFile apkFile = new ApkFile(apk)) {
            var meta = apkFile.getApkMeta();
            return new ApkInfo(
                    meta.getPackageName(),
                    meta.getVersionName() == null ? "0" : meta.getVersionName(),
                    meta.getVersionCode() == null ? 0 : meta.getVersionCode(),
                    meta.getLabel());
        } catch (Exception e) {
            // 降级：新 AGP 产出的 resources.arsc（sparse 编码等）超出 apk-parser 解析能力
            // （BufferUnderflowException）——manifest 属性足够 init 用，label 缺省
            return parseFromManifest(apk);
        }
    }

    /**
     * manifest 降级解析（pxb-axml 直读二进制 manifest，不碰资源表——apk-parser 的
     * getManifestXml 也会解析资源表，新 AGP 的 sparse arsc 同样崩）。
     */
    private static ApkInfo parseFromManifest(File apk) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) {
                throw new IOException("apk 无 AndroidManifest.xml");
            }
            byte[] data;
            try (var in = zip.getInputStream(entry);
                 var bos = new java.io.ByteArrayOutputStream()) {
                in.transferTo(bos);
                data = bos.toByteArray();
            }
            final String[] pkg = {null};
            final String[] versionName = {null};
            final long[] versionCode = {0};
            new org.jf.pxb.android.axml.AxmlReader(data).accept(
                    new org.jf.pxb.android.axml.AxmlVisitor() {
                        @Override
                        public org.jf.pxb.android.axml.NodeVisitor visitFirst(String ns, String name) {
                            // manifest 根元素属性
                            return new org.jf.pxb.android.axml.NodeVisitor() {
                                @Override
                                public void visitContentAttr(String ns, String name, int resourceId,
                                                             int type, Object obj) {
                                    if ("package".equals(name) && obj instanceof String) {
                                        pkg[0] = (String) obj;
                                    } else if ("versionName".equals(name) && obj instanceof String) {
                                        versionName[0] = (String) obj;
                                    } else if ("versionCode".equals(name) && obj instanceof Integer) {
                                        versionCode[0] = (Integer) obj;
                                    }
                                }
                            };
                        }
                    });
            if (pkg[0] == null) {
                throw new IOException("manifest 无 package 属性");
            }
            return new ApkInfo(pkg[0],
                    versionName[0] == null ? "0" : versionName[0],
                    versionCode[0], "<unnamed>");
        } catch (IOException io) {
            throw io;
        } catch (Exception ex) {
            throw new IOException("manifest 降级解析失败: " + ex, ex);
        }
    }

}
