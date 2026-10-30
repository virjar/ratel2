package com.ratel.runtime;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * apk fd 重定向后的资源重建（1.x AssertFixer 精神续作）。
 *
 * <p>坑源（1.x 注释原话）：重定向后若不重建，AssetManager 用旧 fd 的 zip 索引
 * （patched apk 的 central directory）配新内容体（origin apk），索引与内容错位 →
 * resource api 读 axml 直接 crash；依赖 Android 自带刷新机制则会触发 application
 * 重新加载（判断 apk 变化），application 加载代码可能有副作用。
 *
 * <p>做法：清空 ResourcesManager 缓存（mResourceImpls/mCachedApkAssets/
 * mActivityResourceReferences/mResourceReferences），对 resDir 命中的每个
 * ResourcesKey 用 createResourcesImpl 重建（新 fd + 新索引，一致指向 origin），
 * 把新 ResourcesImpl 回填到所有存活的 Resources 对象。
 *
 * <p>反射按 API 29-37 防御性探测——字段/方法缺失时跳过该项（渐进降级不抛错）。
 */
final class ResourcesRebuilder {

    private ResourcesRebuilder() {
    }

    /** @param apkPath 被重定向的 apk 路径（只重建 resDir 命中的 impl）。 */
    static void rebuild(String apkPath) {
        try {
            rebuildInternal(apkPath);
        } catch (Throwable t) {
            Bootstrap.log("resources rebuild failed: " + t);
        }
    }

    private static void rebuildInternal(String apkPath) throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Object activityThread = atClass.getMethod("currentActivityThread").invoke(null);
        Field rmField = atClass.getDeclaredField("mResourcesManager");
        rmField.setAccessible(true);
        Object resourcesManager = rmField.get(activityThread);

        // 1. 清缓存（存在才清）
        clearIfPresent(resourcesManager, "mActivityResourceReferences");
        clearIfPresent(resourcesManager, "mResourceReferences");
        clearIfPresent(resourcesManager, "mCachedApkAssets");

        Field implsField = findField(resourcesManager.getClass(), "mResourceImpls");
        if (implsField == null) {
            Bootstrap.log("resources rebuild: mResourceImpls not found（版本字段变化，跳过）");
            return;
        }
        implsField.setAccessible(true);
        Map<Object, Object> impls = (Map<Object, Object>) implsField.get(resourcesManager);
        if (impls.isEmpty()) {
            return;
        }

        Method createImpl = findMethod(resourcesManager.getClass(), "createResourcesImpl");
        if (createImpl == null) {
            Bootstrap.log("resources rebuild: createResourcesImpl not found（跳过）");
            return;
        }

        // 2. 对命中 apk 的 key 重建 impl
        List<Object[]> replaceList = new ArrayList<>(); // {resourcesImpl(新), resources 对象列表}
        List<Object> keys = new ArrayList<>(impls.keySet());
        for (Object key : keys) {
            String resDir = (String) getFieldValue(key, "mResDir");
            if (resDir == null || !resDir.equals(apkPath)) {
                continue;
            }
            Object oldImplRef = impls.get(key);
            Object newImpl;
            try {
                // Android P+ 双参（ResourcesKey, ApkAssetsSupplier）——supplier 从 manager 字段取
                Object supplier = getFieldValue(resourcesManager, "mApkAssetsSupplier");
                if (supplier != null && createImpl.getParameterCount() == 2) {
                    newImpl = createImpl.invoke(resourcesManager, key, supplier);
                } else {
                    newImpl = createImpl.invoke(resourcesManager, key);
                }
            } catch (Exception e) {
                Bootstrap.log("createResourcesImpl failed: " + e);
                continue;
            }
            if (newImpl == null) {
                continue;
            }
            // 收集引用旧 impl 的 Resources 对象（1.x：替换 impl 而不是换 Resources，
            // 保证 Context 持有的 Resources 身份不变）
            List<Object> holders = new ArrayList<>();
            collectResourceHolders(resourcesManager, oldImplRef, newImpl, holders, replaceList);
            impls.put(key, new WeakReference<>(newImpl));
        }

        // 3. 回填：所有 Resources.mResourcesImpl → 新 impl
        for (Object[] pair : replaceList) {
            Object newImpl = pair[0];
            @SuppressWarnings("unchecked")
            List<Object> resourcesList = (List<Object>) pair[1];
            Field implField = findField(Class.forName("android.content.res.Resources"), "mResourcesImpl");
            if (implField == null) break;
            implField.setAccessible(true);
            for (Object res : resourcesList) {
                try {
                    implField.set(res, newImpl);
                } catch (Throwable ignored) {
                }
            }
        }
        Bootstrap.log("resources rebuilt for " + apkPath);
    }

    /** 从 manager 的引用表里找持有 oldImpl 的 Resources（换 impl 时同步给它们）。 */
    private static void collectResourceHolders(Object resourcesManager, Object oldImplRef,
                                               Object newImpl, List<Object> holders,
                                               List<Object[]> replaceList) throws Exception {
        Object oldImpl = oldImplRef instanceof WeakReference ? ((WeakReference<?>) oldImplRef).get() : oldImplRef;
        if (oldImpl == null) return;
        // ActivityResources 结构里的 activityResources 持有 Resources
        for (String fieldName : new String[]{"mActivityResourceReferences", "mResourceReferences"}) {
            try {
                Field f = findField(resourcesManager.getClass(), fieldName);
                if (f == null) continue;
                f.setAccessible(true);
                Object collection = f.get(resourcesManager);
                for (Object ref : toIterable(collection)) {
                    Object target = ref instanceof WeakReference ? ((WeakReference<?>) ref).get() : ref;
                    if (target == null) continue;
                    // ActivityResources { activityResources: List<ActivityResourceRef{activityResources}> }
                    Object resObj = extractResources(target, oldImpl);
                    if (resObj != null) holders.add(resObj);
                }
            } catch (Throwable ignored) {
            }
        }
        if (!holders.isEmpty()) {
            replaceList.add(new Object[]{newImpl, holders});
        }
    }

    private static Object extractResources(Object holder, Object oldImpl) {
        // Resources 对象本身（mResourceReferences）或 ActivityResources 容器
        if (holder.getClass().getName().endsWith(".Resources")) {
            return holder;
        }
        try {
            Field f = findField(holder.getClass(), "activityResources");
            if (f != null) {
                f.setAccessible(true);
                for (Object ar : toIterable(f.get(holder))) {
                    Field rf = findField(ar.getClass(), "activityRes");
                    if (rf == null) rf = findField(ar.getClass(), "resources");
                    if (rf != null) {
                        rf.setAccessible(true);
                        return rf.get(ar);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void clearIfPresent(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            Object collection = f.get(obj);
            if (collection instanceof Map) ((Map<?, ?>) collection).clear();
            else if (collection instanceof List) ((List<?>) collection).clear();
        } catch (Throwable ignored) {
        }
    }

    private static Iterable<Object> toIterable(Object collection) {
        List<Object> out = new ArrayList<>();
        if (collection instanceof Map) out.addAll(((Map<?, ?>) collection).values());
        else if (collection instanceof List) out.addAll((List<?>) collection);
        return out;
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static Object getFieldValue(Object obj, String name) {
        try {
            Field f = findField(obj.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }
}
