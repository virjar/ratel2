package ratel.tool;

import com.android.tools.build.bundletool.androidtools.Aapt2Command;
import com.android.tools.build.bundletool.commands.BuildApksCommand;

import java.nio.file.Path;

/**
 * bundletool lib 封装入口：aab + device-spec json → .apks（config splits）。
 * 用法: SplitApksBuilder <aab> <out.apks> <device-spec.json> <aapt2路径>
 */
public final class SplitApksBuilder {

    public static void main(String[] args) {
        java.nio.file.Path out = Path.of(args[1]);
        out.toFile().delete(); // bundletool 拒绝覆盖已存在输出
        BuildApksCommand.builder()
                .setBundlePath(Path.of(args[0]))
                .setOutputFile(Path.of(args[1]))
                .setDeviceSpec(Path.of(args[2]))
                .setAapt2Command(Aapt2Command.createFromExecutablePath(Path.of(args[3])))
                .build()
                .execute();
        System.out.println("apks 生成: " + args[1]);
    }
}
