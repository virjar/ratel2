package com.ratel.toolchain.dex;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 依赖重定位：混编引入的 maven 依赖库统一 relocate 到 {@code ratel.repack.<pkg>.*}，
 * 避免与原始 apk 内已打包的同名库（okhttp/kotlin-stdlib 等）类冲突（virjar 定版规则：
 * 依赖 repkg 后 append，只有显式 pull 的类才按原名替换）。
 *
 * <p>映射规则：收集依赖 jar 的全部顶层包名，统一加 {@code ratel/repack/} 前缀。
 * keepSet（patch 自身类 + 原 apk 类）内的名字永不重定位。字节码类引用（含继承/签名/
 * 注解）由 ASM ClassRemapper 全量改写；字符串常量按「形如二进制名/描述符前缀」启发式改写
 * （反射 Class.forName 场景）。</p>
 */
public final class DepRelocator {

    /** 收集 jar 里出现的全部顶层包段（okhttp3、okio、org 等）。 */
    public static Set<String> topLevelPackages(Path jar) throws IOException {
        Set<String> tops = new HashSet<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                int slash = e.getName().indexOf('/');
                if (slash > 0) tops.add(e.getName().substring(0, slash));
            }
        }
        return tops;
    }

    /**
     * 对一个 jar 做重定位。
     *
     * @param prefixMap 顶层包名 -&gt; 目标顶层内部名（如 okhttp3 -&gt; ratel/repack/okhttp3）
     * @param keepSet   不重定位的内部名集合（patch 自身类）
     */
    public static Path relocateJar(Path jar, Map<String, String> prefixMap, Set<String> keepSet)
            throws IOException {
        Path out = Files.createTempFile("ratel-reloc-", ".jar");
        try (JarFile jf = new JarFile(jar.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.getName().endsWith(".class")) {
                    byte[] remapped = remapClass(jf.getInputStream(e).readAllBytes(), prefixMap, keepSet);
                    String newName = remapResourcePath(e.getName(), prefixMap);
                    jos.putNextEntry(new JarEntry(newName));
                    jos.write(remapped);
                    jos.closeEntry();
                } else if (!e.getName().endsWith("/") && !e.getName().startsWith("META-INF/")
                        && !e.getName().endsWith(".kotlin_module") && !e.getName().endsWith(".kotlin_metadata")) {
                    // 非 class 资源原样保留（META-INF/services 的服务名改写 v2 再做）
                    jos.putNextEntry(new JarEntry(e.getName()));
                    jos.write(jf.getInputStream(e).readAllBytes());
                    jos.closeEntry();
                }
            }
        }
        return out;
    }

    /** own classes 目录打成 jar 并标记为 keep（不 relocate，按原名替换/新增）。 */
    public static Path jarClasses(Path classesDir) throws IOException {
        Path out = Files.createTempFile("ratel-own-", ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
            List<Path> files = new ArrayList<>();
            try (var walk = Files.walk(classesDir)) {
                walk.filter(p -> p.toString().endsWith(".class")).forEach(files::add);
            }
            for (Path f : files) {
                String rel = classesDir.relativize(f).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(rel));
                jos.write(Files.readAllBytes(f));
                jos.closeEntry();
            }
        }
        return out;
    }

    private static byte[] remapClass(byte[] classBytes, Map<String, String> prefixMap, Set<String> keepSet) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(0); // 不 COMPUTE_FRAMES：仅改名不改结构，原 frames 仍有效
        ClassRemapper remapper = new ClassRemapper(writer, new PrefixRemapper(prefixMap, keepSet));
        reader.accept(remapper, 0);
        return writer.toByteArray();
    }

    private static String remapResourcePath(String entryName, Map<String, String> prefixMap) {
        int slash = entryName.indexOf('/');
        if (slash <= 0) return entryName;
        String top = entryName.substring(0, slash);
        String mapped = prefixMap.get(top);
        return mapped == null ? entryName : mapped + entryName.substring(slash);
    }

    private static final class PrefixRemapper extends Remapper {
        private final Map<String, String> prefixMap;
        private final Set<String> keepSet;

        PrefixRemapper(Map<String, String> prefixMap, Set<String> keepSet) {
            this.prefixMap = prefixMap;
            this.keepSet = keepSet;
        }

        @Override
        public String map(String internalName) {
            if (keepSet.contains(internalName)) return internalName;
            int slash = internalName.indexOf('/');
            if (slash <= 0) return internalName; // 默认包/单段类不动
            String top = internalName.substring(0, slash);
            String mapped = prefixMap.get(top);
            return mapped == null ? internalName : mapped + internalName.substring(slash);
        }

        @Override
        public Object mapValue(Object value) {
            if (value instanceof String) {
                String s = (String) value;
                String mapped = mapStringConstant(s);
                if (mapped != null) return mapped;
            }
            return super.mapValue(value);
        }

        /** 反射字符串启发式：okhttp3.OkHttp -> ratel.repack.okhttp3.OkHttp；描述符同理。 */
        private String mapStringConstant(String s) {
            boolean descriptor = s.startsWith("L") && s.indexOf(';') > 0;
            String probe = descriptor ? s.substring(1) : s;
            char sep = descriptor ? '/' : '.';
            int cut = probe.indexOf(sep);
            if (cut <= 0) return null;
            String firstSegment = probe.substring(0, cut);
            String mapped = prefixMap.get(firstSegment);
            if (mapped == null) return null;
            String target = descriptor ? mapped : mapped.replace('/', '.');
            return (descriptor ? "L" : "") + target + probe.substring(cut);
        }
    }
}
