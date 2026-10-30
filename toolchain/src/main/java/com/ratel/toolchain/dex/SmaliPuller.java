package com.ratel.toolchain.dex;

import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.baksmali.formatter.BaksmaliWriter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 单类 smali 反编译（baksmali ClassDefinition，不做全量 dex 展开）。
 * 产物落 smali/ 工作区，文件头记录来源 dex，build 时按来源做回编合并。
 */
public final class SmaliPuller {

    public static Path pullToProject(DexStore.LocatedClass located, Path smaliDir) throws IOException {
        String binaryName = located.classDef.getType()
                .substring(1, located.classDef.getType().length() - 1).replace('/', '.');
        Path target = smaliDir.resolve(binaryName.replace('.', '/') + ".smali");
        Files.createDirectories(target.getParent());
        try (BufferedWriter bw = Files.newBufferedWriter(target, StandardCharsets.UTF_8);
             BaksmaliWriter writer = new BaksmaliWriter(bw)) {
            newClassDefinition(located).writeTo(writer);
            writer.flush();
        }
        return target;
    }

    public static String disassembleToString(DexStore.LocatedClass located) throws IOException {
        StringWriter sw = new StringWriter();
        try (BaksmaliWriter writer = new BaksmaliWriter(sw)) {
            newClassDefinition(located).writeTo(writer);
            writer.flush();
        }
        return sw.toString();
    }

    private static ClassDefinition newClassDefinition(DexStore.LocatedClass located) {
        BaksmaliOptions options = new BaksmaliOptions();
        options.debugInfo = true;
        return new ClassDefinition(options, located.classDef);
    }
}
