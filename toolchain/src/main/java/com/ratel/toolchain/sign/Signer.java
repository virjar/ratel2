package com.ratel.toolchain.sign;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

/** V2(+V3) 重签名。目标 Android 10+，V1 无必要。 */
public final class Signer {

    public static void sign(Path inputApk, Path outputApk, Path keystore,
                            String storePassword, String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(keystore)) {
            ks.load(in, storePassword.toCharArray());
        }
        KeyStore.PasswordProtection prot = new KeyStore.PasswordProtection(storePassword.toCharArray());
        KeyStore.Entry entry = ks.getEntry(alias, prot);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
            throw new IllegalArgumentException("keystore entry '" + alias + "' is not a private key entry");
        }
        KeyStore.PrivateKeyEntry pkEntry = (KeyStore.PrivateKeyEntry) entry;
        PrivateKey privateKey = pkEntry.getPrivateKey();
        X509Certificate cert = (X509Certificate) pkEntry.getCertificate();

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(alias, privateKey,
                java.util.List.of(cert)).build();

        ApkSigner signer = new ApkSigner.Builder(List.of(signerConfig))
                .setOutputApk(outputApk.toFile())
                .setInputApk(inputApk.toFile())
                .setMinSdkVersion(24)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build();
        signer.sign();
    }

    public static void verify(File apk) throws Exception {
        com.android.apksig.ApkVerifier verifier = new com.android.apksig.ApkVerifier.Builder(apk)
                .setMinCheckedPlatformVersion(24)
                .build();
        com.android.apksig.ApkVerifier.Result result = verifier.verify();
        if (!result.isVerified()) {
            StringBuilder sb = new StringBuilder("signature verification failed:\n");
            for (com.android.apksig.ApkVerifier.IssueWithParams issue : result.getErrors()) {
                sb.append("  ").append(issue).append('\n');
            }
            throw new IOException(sb.toString());
        }
    }
}
