package com.ratel.toolchain.inject;


import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** manifest 解析：application 类名（入口注入目标）。 */
public final class ManifestParser {

    private static final Pattern APPLICATION_NAME = Pattern.compile(
            "<application[^>]*android:name=\"([^\"]+)\"");

    /** 返回 application 类的二进制名（相对名按老规则补全包名）；无自定义 application 返回 null。 */
    public static String applicationClassName(Path apk, String packageName) throws Exception {
        String xml = manifestXml(apk);
        Matcher m = APPLICATION_NAME.matcher(xml);
        if (!m.find()) {
            return null;
        }
        return relativize(m.group(1), packageName);
    }

    private static final Pattern ACTIVITY_NAME = Pattern.compile("android:name=\"([^\"]+)\"");

    /**
     * launcher activity 类名（MAIN + LAUNCHER 的 activity）。
     * 无自定义 application 的 app（Compose 新项目常态）用它作 clinit 注入目标。
     */
    public static String launcherActivityName(Path apk, String packageName) throws Exception {
        String xml = manifestXml(apk);
        for (String chunk : xml.split("<activity")) {
            if (chunk.isEmpty() || chunk.startsWith("-")) {
                continue; // <activity-alias 暂不处理
            }
            if (!chunk.contains("android.intent.action.MAIN")
                    || !chunk.contains("android.intent.category.LAUNCHER")) {
                continue;
            }
            Matcher m = ACTIVITY_NAME.matcher(chunk);
            if (m.find()) {
                return relativize(m.group(1), packageName);
            }
        }
        return null;
    }

    /** 读 application@appComponentFactory 类名（无则 null）。 */
    public static String appComponentFactoryName(Path apk) throws Exception {
        String xml = manifestXml(apk);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "appComponentFactory=\"([^\"]+)\"").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String manifestXml(Path apk) throws Exception {
        byte[] data;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) {
                throw new IllegalStateException("apk 无 AndroidManifest.xml");
            }
            try (var in = zip.getInputStream(entry);
                 var bos = new java.io.ByteArrayOutputStream()) {
                in.transferTo(bos);
                data = bos.toByteArray();
            }
        }
        StringBuilder sb = new StringBuilder();
        new org.jf.pxb.android.axml.AxmlReader(data).accept(
                new org.jf.pxb.android.axml.AxmlVisitor() {
                    @Override
                    public org.jf.pxb.android.axml.NodeVisitor visitFirst(String ns, String name) {
                        return new DumpNode(sb, name);
                    }
                });
        return sb.toString();
    }

    /**
     * pxb-axml → xml 文本 dump（节点 visitor）。不碰资源表——apk-parser 的
     * getManifestXml 连带解析 resources.arsc，新 AGP 的 sparse arsc 直接
     * BufferUnderflow，读路径全面改 pxb 直读。
     */
    private static final class DumpNode extends org.jf.pxb.android.axml.NodeVisitor {
        private final StringBuilder sb;
        private final String tag;
        private final StringBuilder attrs = new StringBuilder();
        private boolean tagClosed = false;
        private boolean hasChildren = false;

        DumpNode(StringBuilder sb, String tag) {
            this.sb = sb;
            this.tag = tag;
        }

        @Override
        public void visitContentAttr(String ns, String name, int resourceId, int type, Object obj) {
            String prefix = ns != null && ns.contains("android") ? "android:" : "";
            attrs.append(' ').append(prefix).append(name).append("=\"")
                    .append(obj == null ? "" : String.valueOf(obj).replace("\"", "")).append('\"');
        }

        @Override
        public org.jf.pxb.android.axml.NodeVisitor visitChild(String ns, String name) {
            if (!tagClosed) {
                sb.append('<').append(tag).append(attrs).append('>').append('\n');
                tagClosed = true;
            }
            hasChildren = true;
            return new DumpNode(sb, name);
        }

        @Override
        public void visitEnd() {
            if (!tagClosed) {
                sb.append('<').append(tag).append(attrs).append("/>").append('\n');
            } else {
                sb.append("</").append(tag).append('>').append('\n');
            }
        }
    }

    private static String relativize(String name, String packageName) {
        if (name.startsWith(".")) {
            return packageName + name;
        }
        if (!name.contains(".")) {
            return packageName + "." + name;
        }
        return name;
    }
}
