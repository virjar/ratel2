package com.ratel.toolchain.apk;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * APK 增量重建：origin.apk 中未变更的条目按原始压缩流 raw-copy（不重压缩、不重对齐），
 * 变更/新增条目以 DEFLATED 写入。这是巨型 apk 秒级重建的关键。
 *
 * <p>已知限制：不做 so 的 uncompressed/4096(16KB) 对齐——常规安装提取路径不受影响；
 * Android 15+ 16KB page 与 apk!/ 内嵌 so 直载场景依赖对齐，待补（runtime 注入的 so 走
 * assets 释放到 filesDir 的路径加载，不受此限）。</p>
 */
public final class IncrementalZipPacker {

    /** 新增或覆盖的条目：name -&gt; 数据（DEFLATED 压缩写入）。物理序排在最前——
     *  dex 遮蔽依赖加载顺序（ART 按 zip 条目顺序枚举 classes*.dex）。 */
    private final Map<String, byte[]> overrides;

    /** 需要整体删除的条目（不复制也不替换）。 */
    private final java.util.Set<String> deletions;

    /** 条目改名映射（raw-copy 换名零字节重写，dex 遮蔽架构用）。 */
    private final Map<String, String> renames;

    public IncrementalZipPacker(Map<String, byte[]> overrides) {
        this(overrides, java.util.Set.of(), Map.of());
    }

    public IncrementalZipPacker(Map<String, byte[]> overrides, java.util.Set<String> deletions,
                                Map<String, String> renames) {
        this.overrides = overrides;
        this.deletions = deletions;
        this.renames = renames;
    }

    public void rebuild(Path originApk, Path outputApk) throws IOException {
        try (ZipFile originZip = ZipFile.builder().setFile(originApk.toFile()).get();
             // 必须用 File 构造（可 seek）：STORED 条目 raw-copy 时 CRC 校验依赖随机访问回写
             ZipArchiveOutputStream out = new ZipArchiveOutputStream(outputApk.toFile())) {

            out.setEncoding("UTF-8");

            // 1. overrides 先写（物理序最前，dex 遮蔽依赖此顺序）
            for (Map.Entry<String, byte[]> e : overrides.entrySet()) {
                ZipArchiveEntry newEntry = new ZipArchiveEntry(e.getKey());
                newEntry.setMethod(ZipMethod.DEFLATED.getCode());
                newEntry.setTime(System.currentTimeMillis());
                out.putArchiveEntry(newEntry);
                out.write(e.getValue());
                out.closeArchiveEntry();
            }

            // 2. 原条目 raw-copy（应用改名；只有「未被改名且原名被覆盖/删除」才跳过——
            //    dex 遮蔽场景下原 classes.dex 既在改名表又在覆盖表，改名优先，必须拷出）
            var entries = originZip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                boolean renamed = renames.containsKey(entry.getName());
                if (!renamed && (overrides.containsKey(entry.getName())
                        || deletions.contains(entry.getName()))) {
                    continue;
                }
                String outName = renamed ? renames.get(entry.getName()) : entry.getName();
                if (overrides.containsKey(outName)) {
                    throw new IOException("改名冲突: " + entry.getName() + " -> " + outName
                            + " 与覆盖条目重名（dex 手术的 shift 计算错误）");
                }
                try (InputStream raw = originZip.getRawInputStream(entry)) {
                    // raw-copy：保留原始压缩数据；size/compressedSize/crc 必须拷全，
                    // 否则 STORED 条目的 local header 会损坏（apksig 校验直接失败）
                    ZipArchiveEntry rawEntry = new ZipArchiveEntry(outName);
                    rawEntry.setMethod(entry.getMethod());
                    rawEntry.setSize(entry.getSize());
                    rawEntry.setCompressedSize(entry.getCompressedSize());
                    rawEntry.setCrc(entry.getCrc());
                    rawEntry.setTime(entry.getTime());
                    out.addRawArchiveEntry(rawEntry, raw);
                }
            }
            out.finish();
        }
    }
}
