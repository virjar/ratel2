package com.ratel.toolchain.inject;

import org.jf.pxb.android.axml.AxmlVisitor;
import org.jf.pxb.android.axml.NodeVisitor;

import java.util.HashSet;
import java.util.Set;

/**
 * 定向添加 &lt;queries&gt;&lt;package android:name="..."/&gt;&lt;/queries&gt;（Android 11+ 包可见性）。
 * ratel 1.x AddQueriesForPackage 的直系传承（virjar 原实现），dev 插件按包名直查的 manifest 前置。
 */
public class AddQueriesForPackage extends AxmlVisitor {

    private final Set<String> needAddPackages = new HashSet<>();

    public AddQueriesForPackage(AxmlVisitor av, Set<String> needAddPackages) {
        super(av);
        // ratel patch: 原实现参数名遮蔽字段（needAddPackages.addAll(needAddPackages) 是空集
        // 自合并，字段从未填充——该代码在 1.x 从未生产执行故未暴露），此处修正
        if (needAddPackages != null) {
            this.needAddPackages.addAll(needAddPackages);
        }
    }

    public AddQueriesForPackage(AxmlVisitor av, String needAddPackage) {
        super(av);
        this.needAddPackages.add(needAddPackage);
    }

    @Override
    public NodeVisitor visitFirst(String namespace, String name) {
        return new NodeVisitor(super.visitFirst(namespace, name)){

            private final Set<String> nowPackages = new HashSet<>();

            @Override
            public NodeVisitor visitChild(String ns, String name) {
                if(!"queries".equals(name)){
                    return super.visitChild(ns, name);
                }
                return new NodeVisitor(super.visitChild(ns,name)){
                    @Override
                    public NodeVisitor visitChild(String ns, String name) {
                        if(!"package".equals(name)){
                            return super.visitChild(ns, name);
                        }
                        return new NodeVisitor(super.visitChild(ns, name)){
                            @Override
                            public void visitContentAttr(String ns, String name, int resourceId, int type, Object obj) {
                                super.visitContentAttr(ns, name, resourceId, type, obj);
                                if("name".equals(name) && obj instanceof String && AxmlVisitor.NS_ANDROID.equals(ns)){
                                    nowPackages.add(obj.toString());
                                    
                                }
                            }
                        };
                    }

                    @Override
                    public void visitEnd() {
                        for(String packageName : needAddPackages){
                            if(nowPackages.contains(packageName)){
                               continue;
                            }
                            NodeVisitor nodeVisitor = super.visitChild(null, "package");
                            nodeVisitor.visitContentAttr(AxmlVisitor.NS_ANDROID, "name", AxmlVisitor.NAME_RESOURCE_ID,
                                    AxmlVisitor.TYPE_STRING, packageName);
                            
                        }
                        super.visitEnd();
                    }
                };
            }

            @Override
            public void visitEnd() {
                // 如果没有queries标签
                if(nowPackages.isEmpty()){
                    NodeVisitor nodeVisitor = super.visitChild(null, "queries");
                    for(String packageName : needAddPackages){
                        NodeVisitor packageVisitor = nodeVisitor.visitChild(null, "package");
                        packageVisitor.visitContentAttr(AxmlVisitor.NS_ANDROID, "name", AxmlVisitor.NAME_RESOURCE_ID,
                                AxmlVisitor.TYPE_STRING, packageName);
                        
                    }
                }
                super.visitEnd();
            }
        };
    }
}
