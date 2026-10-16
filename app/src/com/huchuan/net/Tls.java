package com.huchuan.net;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS 支持 —— 与 CatShare / 小米互传互通的关键。
 *
 * 为什么必须（踩坑 P023）：
 *   CatShare 发送方用 **HTTPS/WSS**（Ktor sslConnector + 自签名证书），
 *   接收方用 FakeTrustManager + hostnameVerifier{true}：
 *   **传输层是 TLS，但证书内容不被校验**。
 *   我们必须同样提供 TLS，否则对方（如小米原生互传）连不上。
 *
 * 做法：
 *   - 服务端：用随包分发的自签名证书（res/raw/huchuan.p12）
 *   - 客户端：全信任 TrustManager + 放行所有主机名（对齐 FakeTrustManager）
 *
 * ★关于 huchuan.p12：它是「公开凭据」，不是私钥★
 *   下面 PASS 常量就是它的口令，明文可见 —— 这是**有意为之**：
 *   · 双方都对证书做全信任，它只用于让 TLS 握手能建立，不承担身份校验；
 *   · 它**不用于签名 APK**（签名密钥在 $HOME/.huchuan-keystore，不入库）；
 *   · 因此入库没有安全风险，等价于一个公开常量。
 *   需要重新生成时用 `./scripts/gen-cert.sh --force`
 *   （详见 docs/04-参考/协议规格.md §6）。
 */
public final class Tls {

    private static final String TAG = "HuChuan.Tls";
    private static final char[] PASS = "huchuan".toCharArray();
    private static final String ALIAS = "huchuan";

    private static SSLContext serverCtx;
    private static SSLContext clientCtx;

    /** 服务端 SSLContext（自签名证书，随包分发）。 */
    public static synchronized SSLContext serverContext(Context ctx) throws Exception {
        if (serverCtx != null) return serverCtx;

        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream in = ctx.getResources().openRawResource(
                ctx.getResources().getIdentifier("huchuan", "raw", ctx.getPackageName()));
        try {
            ks.load(in, PASS);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
        Log.i(TAG, "已加载自签名证书，别名 " + ALIAS);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);

        SSLContext c = SSLContext.getInstance("TLS");
        c.init(kmf.getKeyManagers(), null, new SecureRandom());
        serverCtx = c;
        return c;
    }

    /** 客户端 SSLContext（信任一切，对齐 CatShare 的 FakeTrustManager）。 */
    public static synchronized SSLContext clientContext() throws Exception {
        if (clientCtx != null) return clientCtx;
        TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(null, tm, new SecureRandom());
        clientCtx = c;
        return c;
    }

    private Tls() {}
}
