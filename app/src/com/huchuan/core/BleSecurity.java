package com.huchuan.core;


import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ECDH(P-256) 协商 + AES/CTR 加解密。
 * IV 为协议硬编码常量，切勿修改，否则无法与对端互通。
 */
public final class BleSecurity {
    private static final byte[] IV = "0102030405060708".getBytes();
    private static ECPrivateKey localPrivate;
    private static ECPublicKey localPublic;

    static {
        try {
            KeyPairGenerator kg = KeyPairGenerator.getInstance("EC");
            kg.initialize(256);
            KeyPair kp = kg.generateKeyPair();
            localPublic = (ECPublicKey) kp.getPublic();
            localPrivate = (ECPrivateKey) kp.getPrivate();
        } catch (Exception e) {
            throw new RuntimeException("EC 密钥生成失败", e);
        }
    }

    public static String getEncodedPublicKey() {
        return java.util.Base64.getEncoder().encodeToString(localPublic.getEncoded());
    }

    public static SessionCipher deriveSessionKey(String peerPubKeyBase64) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("EC");
        ECPublicKey peer = (ECPublicKey) kf.generatePublic(
                new X509EncodedKeySpec(java.util.Base64.getDecoder().decode(peerPubKeyBase64)));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(localPrivate);
        ka.doPhase(peer, true);
        // Android 的 KeyAgreement.generateSecret(String) 在编译桩与运行时的返回类型不一致
        // （桩=SecretKey，运行时=byte[]），故用 Object 接收后分支处理。
        Object secret = ka.generateSecret("TlsPremasterSecret");
        byte[] keyBytes = (secret instanceof byte[])
                ? (byte[]) secret
                : ((javax.crypto.SecretKey) secret).getEncoded();
        return new SessionCipher(new SecretKeySpec(keyBytes, "AES"));
    }

    public static final class SessionCipher {
        private final SecretKeySpec key;
        SessionCipher(SecretKeySpec key) { this.key = key; }

        public String encrypt(String data) throws Exception {
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(IV));
            return java.util.Base64.getEncoder().encodeToString(c.doFinal(data.getBytes("UTF-8")));
        }

        public String decrypt(String data) throws Exception {
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(IV));
            return new String(c.doFinal(java.util.Base64.getDecoder().decode(data)), "UTF-8");
        }
    }

    private BleSecurity() {}
}
