package com.ratel.toolchain.dex;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.MultiDexContainer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * APK dex 只读视图：一次加载，供 pull / stub / 后续 dex 手术共用。
 *
 * <p>已知风险：org.smali:dexlib2 停在 2.5.2（dex format 039）。ART 自 API 28 后未新增 dex 指令，
 * 预计可覆盖 API 29-37 的存量 dex；若遇到新格式版本，需切换维护 fork 或补 parser。</p>
 */
public final class DexStore implements AutoCloseable {

    public static final class LocatedClass {
        public final String dexEntryName;
        public final ClassDef classDef;

        LocatedClass(String dexEntryName, ClassDef classDef) {
            this.dexEntryName = dexEntryName;
            this.classDef = classDef;
        }
    }

    private final Path apk;
    private final MultiDexContainer<? extends DexBackedDexFile> container;

    public DexStore(Path apk) throws IOException {
        this.apk = apk;
        this.container = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35));
    }

    public List<String> dexEntryNames() throws IOException {
        return container.getDexEntryNames();
    }

    /** 按 zip 条目名（classes.dex / classes2.dex...）取 dex。 */
    public MultiDexContainer.DexEntry<? extends DexBackedDexFile> findEntry(String entryName) throws IOException {
        return container.getEntry(entryName);
    }

    /** 按二进制名（com.foo.Bar，内部类 com.foo.Bar$Baz）定位类。 */
    public LocatedClass findClass(String binaryName) throws IOException {
        String typeDescriptor = 'L' + binaryName.replace('.', '/') + ';';
        for (String entryName : container.getDexEntryNames()) {
            var entry = container.getEntry(entryName);
            if (entry == null) continue;
            DexBackedDexFile dex = entry.getDexFile();
            for (ClassDef classDef : dex.getClasses()) {
                if (classDef.getType().equals(typeDescriptor)) {
                    return new LocatedClass(entryName, classDef);
                }
            }
        }
        return null;
    }

    /** 遍历全部类的二进制名（供 stub 包名过滤与 AI 搜索）。 */
    public List<String> allClassNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (String entryName : container.getDexEntryNames()) {
            var entry = container.getEntry(entryName);
            if (entry == null) continue;
            for (ClassDef classDef : entry.getDexFile().getClasses()) {
                String type = classDef.getType();
                names.add(type.substring(1, type.length() - 1).replace('/', '.'));
            }
        }
        return names;
    }

    @Override
    public void close() {
        // MultiDexContainer 持有的是随机读句柄，dexlib2 无显式 close；依赖 GC 释放
    }
}
