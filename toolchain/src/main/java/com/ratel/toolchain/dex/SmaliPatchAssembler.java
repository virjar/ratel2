package com.ratel.toolchain.dex;

import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * smali 工作区回编：workspace smali/ 目录 → 单个 patch dex → 走改名遮蔽管线。
 *
 * <p>工作流：{@code ratel pull <class> --smali} → 用户编辑 .smali 文件 → {@code ratel build}
 * 自动扫描 smali/ 目录回编。与 Java 混编共用同一条遮蔽合并管线。</p>
 */
public final class SmaliPatchAssembler {

    public static final class Result {
        public final byte[] dexBytes;
        public final Set<String> classDescriptors;
        public final int fileCount;

        Result(byte[] dexBytes, Set<String> classDescriptors, int fileCount) {
            this.dexBytes = dexBytes;
            this.classDescriptors = classDescriptors;
            this.fileCount = fileCount;
        }
    }

    /**
     * @return null if no .smali files found
     */
    public static Result assemble(Path smaliDir) throws IOException {
        List<Path> smaliFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(smaliDir)) {
            walk.filter(p -> p.toString().endsWith(".smali") && Files.isRegularFile(p))
                    .forEach(smaliFiles::add);
        }
        if (smaliFiles.isEmpty()) {
            return null;
        }

        Path outputDex = Files.createTempFile("ratel-smali-", ".dex");
        try {
            SmaliOptions options = new SmaliOptions();
            options.apiLevel = 29;
            options.outputDexFile = outputDex.toString();

            List<String> paths = new ArrayList<>();
            for (Path p : smaliFiles) {
                paths.add(p.toString());
            }
            boolean ok = Smali.assemble(options, paths);
            if (!ok) {
                throw new IOException("smali 汇编失败（" + smaliFiles.size() + " 个文件）——检查 smali 语法");
            }
            if (!Files.isRegularFile(outputDex)) {
                throw new IOException("smali 汇编完成但未产出 dex 文件");
            }
            byte[] dexBytes = Files.readAllBytes(outputDex);

            // 收集产出类的描述符（供遮蔽统计）
            Set<String> descriptors = DexSurgery.collectDescriptors(dexBytes);
            return new Result(dexBytes, descriptors, smaliFiles.size());
        } finally {
            Files.deleteIfExists(outputDex);
        }
    }
}
