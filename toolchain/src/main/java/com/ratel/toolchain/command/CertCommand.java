package com.ratel.toolchain.command;

import com.ratel.toolchain.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.Callable;

/**
 * 生成/重建开发签名证书（PKCS12）。init 会自动调用；也可独立执行覆盖。
 * 证书是本地开发资产，密码明文写入 project.yaml。
 */
@Command(name = "cert", description = "生成开发签名证书（自动生成随机密码并写回 project.yaml）")
public class CertCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "ratel 工程目录（缺省则仅生成独立证书文件）", arity = "0..1")
    Path project;

    @Option(names = {"--keystore"}, description = "keystore 输出路径")
    Path keystore;

    @Option(names = {"--alias"}, defaultValue = "ratel", description = "key 别名")
    String alias;

    @Override
    public Integer call() throws Exception {
        if (alias == null) alias = "ratel"; // 手动构造（非 picocli 解析）时 defaultValue 不生效
        Path ksPath;
        ProjectLayout layout = null;
        if (project != null) {
            layout = ProjectLayout.load(project);
            ksPath = layout.root.resolve(layout.configString("signing.keystore", "ratel-dev.keystore"));
        } else if (keystore != null) {
            ksPath = keystore;
        } else {
            ksPath = Path.of("ratel-dev.keystore");
        }

        String password = randomPassword();
        Files.createDirectories(ksPath.toAbsolutePath().getParent());

        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process p = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-keystore", ksPath.toString(),
                "-storetype", "PKCS12",
                "-storepass", password,
                "-keypass", password,
                "-alias", alias,
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10950",
                "-dname", "CN=Ratel Dev, OU=ratel, O=ratel")
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            System.err.println(output);
            throw new IllegalStateException("keytool 生成证书失败，exit=" + code);
        }

        if (layout != null) {
            var config = layout.config();
            @SuppressWarnings("unchecked")
            var signing = (java.util.Map<String, Object>) config.computeIfAbsent("signing", k -> new java.util.LinkedHashMap<String, Object>());
            signing.put("keystore", layout.root.relativize(ksPath).toString());
            signing.put("alias", alias);
            signing.put("storePassword", password);
            layout.writeConfig(config);
        } else {
            System.out.println("storePassword: " + password);
        }
        System.out.println("证书已生成: " + ksPath);
        return 0;
    }

    private static String randomPassword() {
        String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
