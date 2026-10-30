package com.ratel.toolchain.inject;

import com.android.apksig.ApkVerifier;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 构建期提取原始 apk 签名证书（base64），供运行时签名代理还原。 */
public final class OriginSignatureExtractor {

    public static List<String> extractBase64(Path originApk) throws Exception {
        ApkVerifier verifier = new ApkVerifier.Builder(originApk.toFile())
                .setMinCheckedPlatformVersion(24)
                .build();
        ApkVerifier.Result result = verifier.verify();
        List<X509Certificate> certs = result.getSignerCertificates();
        if (certs == null || certs.isEmpty()) {
            throw new IllegalStateException("原始 apk 无签名（未签名包无法提取）");
        }
        List<String> out = new ArrayList<>();
        for (X509Certificate cert : certs) {
            out.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
        }
        return out;
    }
}
