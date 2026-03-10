package org.ddns.crypto;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.security.KeyStore;

public class SSL {

    public static KeyStore createKeyStore() throws Exception {

        String password = "password123";

        // Generate RSA keypair
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();

        // Certificate info
        long now = System.currentTimeMillis();
        Date start = new Date(now);
        Date end = new Date(now + 365L * 86400000);

        X500Name dn = new X500Name("CN=dDNS");

        BigInteger serial = BigInteger.valueOf(now);

        ContentSigner signer =
                new JcaContentSignerBuilder("SHA256withRSA")
                        .build(pair.getPrivate());

        X509v3CertificateBuilder certBuilder =
                new JcaX509v3CertificateBuilder(
                        dn,
                        serial,
                        start,
                        end,
                        dn,
                        pair.getPublic()
                );

        X509Certificate cert =
                new JcaX509CertificateConverter()
                        .getCertificate(certBuilder.build(signer));

        // Create keystore
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);

        ks.setKeyEntry(
                "ddns_web",
                pair.getPrivate(),
                password.toCharArray(),
                new java.security.cert.Certificate[]{cert}
        );

        return ks;
    }
    public static String createTempKeystore() throws Exception {

        KeyStore ks = createKeyStore();

        String password = "password123";
        String path = "keystore.jks";

        try (FileOutputStream fos = new FileOutputStream(path)) {
            ks.store(fos, password.toCharArray());
        }

        return path;
    }
}
