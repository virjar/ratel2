package com.ratel.toolchain.dex;

import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;

/**
 * stub jar 生成服务（CLI stub 命令与 java 混编编译共用）。
 *
 * <p>路线：dexlib2 直接读 dex → ASM 生成 class 文件 → 打 jar（dex2jar 变体，virjar 定夺，
 * 取代 java 源码 + javac 路线——无表达力限制、泛型签名原样透传、毫秒级、无编译错误面）。</p>
 *
 * <p>引用闭包自动扩展：命中类签名引用到的同 apk 类（kotlin-stdlib、androidx 等）自动纳入，
 * 否则用户源码 javac 时报「找不到符号」。framework 类由 android.jar classpath 提供。</p>
 */
public final class StubBuilder {

    public static final class Stats {
        public final int seedCount;
        public final int total;
        public final int skipped;

        Stats(int seedCount, int total, int skipped) {
            this.seedCount = seedCount;
            this.total = total;
            this.skipped = skipped;
        }
    }

    /**
     * @param extraSeeds 额外种子类描述符（java 混编源码 import 的类——方法体引用不在签名闭包内，
     *                   必须显式补种），不在 apk dex 内的会被忽略
     */
    public static Stats build(DexStore store, Path outJar,
                              List<Pattern> seedPatterns, java.util.Set<String> extraSeeds) throws IOException {
        // 全量索引
        Map<String, ClassDef> allClasses = new HashMap<>();
        for (String entryName : store.dexEntryNames()) {
            var entry = store.findEntry(entryName);
            if (entry == null) continue;
            for (ClassDef classDef : entry.getDexFile().getClasses()) {
                allClasses.put(classDef.getType(), classDef);
            }
        }

        // 种子：glob 命中 + 显式描述符
        Deque<String> worklist = new ArrayDeque<>();
        for (ClassDef classDef : allClasses.values()) {
            String name = toBinaryName(classDef.getType());
            for (Pattern p : seedPatterns) {
                if (p.matcher(name).matches()) {
                    worklist.add(classDef.getType());
                    break;
                }
            }
        }
        int seedCount = worklist.size();
        if (extraSeeds != null) {
            for (String desc : extraSeeds) {
                if (allClasses.containsKey(desc)) worklist.add(desc);
            }
        }

        // 签名引用闭包
        Map<String, ClassDef> selected = new LinkedHashMap<>();
        while (!worklist.isEmpty()) {
            String type = worklist.poll();
            if (selected.containsKey(type)) continue;
            ClassDef classDef = allClasses.get(type);
            if (classDef == null) continue;
            selected.put(type, classDef);
            for (String ref : signatureRefs(classDef)) {
                String classRef = classRefOf(ref);
                if (classRef != null && !selected.containsKey(classRef)) {
                    worklist.add(classRef);
                }
            }
        }

        // ASM 直出 class jar
        int skipped = 0;
        Files.createDirectories(outJar.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outJar))) {
            for (Map.Entry<String, ClassDef> e : selected.entrySet()) {
                byte[] bytes;
                try {
                    bytes = ClassStubAsmWriter.toClassFile(e.getValue(), selected.keySet());
                } catch (Exception ex) {
                    skipped++;
                    continue;
                }
                jar.putNextEntry(new JarEntry(
                        e.getKey().substring(1, e.getKey().length() - 1) + ".class"));
                jar.write(bytes);
                jar.closeEntry();
            }
        }
        return new Stats(seedCount, selected.size(), skipped);
    }

    /** 收集类签名引用的类型描述符（超类/接口/字段/方法签名；不含方法体与注解）。 */
    static List<String> signatureRefs(ClassDef classDef) {
        List<String> refs = new ArrayList<>();
        if (classDef.getSuperclass() != null) refs.add(classDef.getSuperclass());
        refs.addAll(classDef.getInterfaces());
        for (Field field : classDef.getFields()) {
            refs.add(field.getType());
        }
        for (Method method : classDef.getMethods()) {
            refs.add(method.getReturnType());
            for (CharSequence p : method.getParameterTypes()) {
                refs.add(p.toString());
            }
        }
        return refs;
    }

    /** 从（可能带数组的）描述符抽取类引用；基本类型返回 null。 */
    static String classRefOf(String descriptor) {
        String d = descriptor;
        while (!d.isEmpty() && d.charAt(0) == '[') d = d.substring(1);
        if (!d.isEmpty() && d.charAt(0) == 'L' && d.charAt(d.length() - 1) == ';') {
            return d;
        }
        return null;
    }

    public static String toBinaryName(String typeDescriptor) {
        return typeDescriptor.substring(1, typeDescriptor.length() - 1).replace('/', '.');
    }

    /** com.foo.** -&gt; ^com\.foo\..*$；com.foo.Bar -&gt; 精确；com.foo.* -&gt; 单段。 */
    public static Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder("^");
        String[] parts = glob.split("\\.", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("\\.");
            String part = parts[i];
            if ("**".equals(part)) {
                sb.setLength(sb.length() - (i > 0 ? 2 : 0));
                sb.append(".*");
                break;
            } else if ("*".equals(part)) {
                sb.append("[^.]*");
            } else {
                sb.append(Pattern.quote(part));
            }
        }
        return Pattern.compile(sb.append("$").toString());
    }
}
