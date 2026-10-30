package com.ratel.toolchain.inject;

import org.jf.pxb.android.axml.AxmlVisitor;
import org.jf.pxb.android.axml.NodeVisitor;

/**
 * manifest 改写：application@appComponentFactory → com.ratel.runtime.RatelAppComponentFactory。
 * 原 ACF 类名记录在 meta-data 供 loader 链式委托。
 *
 * <p>appComponentFactory 是 application 元素的属性（非子元素），
 * 在 application NodeVisitor 层拦截 visitContentAttr 替换值。</p>
 */
public class ReplaceAppComponentFactory extends AxmlVisitor {

    public static final String RATEL_ACF_CLASS = "com.ratel.runtime.RatelAppComponentFactory";
    private static final String META_KEY = "ratel_origin_acf";

    public ReplaceAppComponentFactory(AxmlVisitor av) {
        super(av);
    }

    @Override
    public NodeVisitor visitFirst(String namespace, String name) {
        // 包装 manifest 根元素的 NodeVisitor
        return new NodeVisitor(super.visitFirst(namespace, name)) {
            @Override
            public NodeVisitor visitChild(String ns, String childName) {
                NodeVisitor original = super.visitChild(ns, childName);
                if (!"application".equals(childName)) {
                    return original;
                }
                // 包装 application 元素，拦截其属性
                return new ApplicationVisitor(original);
            }
        };
    }

    /** application 元素拦截器：替换 appComponentFactory 属性 + visitEnd 注入 meta-data。 */
    private static final class ApplicationVisitor extends NodeVisitor {
        private String foundAcf;
        private boolean replaced = false;

        ApplicationVisitor(NodeVisitor nv) {
            super(nv);
        }

        @Override
        public void visitContentAttr(String ns, String name, int resourceId, int type, Object obj) {
            if ("appComponentFactory".equals(name) && obj instanceof String) {
                foundAcf = (String) obj;
                // 替换属性值
                super.visitContentAttr(ns, name, resourceId, type, RATEL_ACF_CLASS);
                replaced = true;
                return;
            }
            super.visitContentAttr(ns, name, resourceId, type, obj);
        }

        @Override
        public void visitEnd() {
            // 无原 ACF → 注入属性
            if (!replaced) {
                super.visitContentAttr(AxmlVisitor.NS_ANDROID, "appComponentFactory",
                        AxmlVisitor.NAME_RESOURCE_ID, AxmlVisitor.TYPE_STRING, RATEL_ACF_CLASS);
            }
            // meta-data 记录原 ACF
            if (foundAcf != null && !foundAcf.equals(RATEL_ACF_CLASS)) {
                NodeVisitor meta = super.visitChild(null, "meta-data");
                meta.visitContentAttr(AxmlVisitor.NS_ANDROID, "name",
                        AxmlVisitor.NAME_RESOURCE_ID, AxmlVisitor.TYPE_STRING, META_KEY);
                meta.visitContentAttr(AxmlVisitor.NS_ANDROID, "value",
                        AxmlVisitor.VALUE_RESOURCE_ID, AxmlVisitor.TYPE_STRING, foundAcf);
            }
            super.visitEnd();
        }
    }
}
