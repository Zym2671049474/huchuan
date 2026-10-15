package com.huchuan;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 入口。M1 阶段只做协议自检展示；M5 会替换为完整 UI。
 */
public class MainActivity extends Activity {

    private static final String TAG = "HuChuan";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("互传");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("M1 · 协议基础层");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        TextView info = new TextView(this);
        info.setText(buildReport());
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setGravity(Gravity.START);
        ScrollView sc = new ScrollView(this);
        sc.addView(info);
        root.addView(sc);

        setContentView(root);
        android.util.Log.i(TAG, "onCreate\n" + buildReport());
    }

    /** 自检：确认协议常量与加密初始化正常。 */
    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("BLE UUID\n");
        sb.append("  广播  ").append(com.huchuan.core.BleUtils.ADV_SERVICE_UUID).append('\n');
        sb.append("  服务  ").append(com.huchuan.core.BleUtils.SERVICE_UUID).append('\n');
        sb.append("  状态  ").append(com.huchuan.core.BleUtils.CHAR_STATUS_UUID).append('\n');
        sb.append("  P2P   ").append(com.huchuan.core.BleUtils.CHAR_P2P_UUID).append("\n\n");
        sb.append("会话 ID  ").append(com.huchuan.core.BleUtils.getSenderId()).append("\n\n");
        sb.append("EC P-256 公钥\n");
        try {
            String pk = com.huchuan.core.BleSecurity.getEncodedPublicKey();
            sb.append("  长度 ").append(pk.length()).append(" (Base64)\n");
            sb.append("  ").append(pk.substring(0, Math.min(40, pk.length()))).append("…\n\n");
            // 自加密回环测试
            com.huchuan.core.BleSecurity.SessionCipher c =
                    com.huchuan.core.BleSecurity.deriveSessionKey(pk);
            String plain = "互传-self-test";
            String enc = c.encrypt(plain);
            String dec = c.decrypt(enc);
            sb.append("加密自检\n");
            sb.append("  明文 ").append(plain).append('\n');
            sb.append("  密文 ").append(enc).append('\n');
            sb.append("  解密 ").append(dec).append('\n');
            sb.append(plain.equals(dec) ? "  ✅ 加解密一致\n" : "  ❌ 加解密不一致\n");
        } catch (Throwable t) {
            sb.append("  ❌ ").append(t).append('\n');
        }
        sb.append("\nWS 握手向量\n");
        try {
            String got = com.huchuan.net.MiniWebSocket.makeAccept("dGhlIHNhbXBsZSBub25jZQ==");
            boolean ok = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".equals(got);
            sb.append(ok ? "  ✅ RFC6455 测试向量通过\n" : "  ❌ 期望 s3pP… 实际 " + got + "\n");
        } catch (Throwable t) {
            sb.append("  ❌ ").append(t).append('\n');
        }
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
