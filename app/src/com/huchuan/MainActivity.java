package com.huchuan;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.huchuan.core.BleSecurity;
import com.huchuan.core.BleUtils;
import com.huchuan.core.P2pManager;
import com.huchuan.core.Perms;
import com.huchuan.net.MiniWebSocket;

/**
 * M2 测试台：验证 WiFi Direct 建组/加入。
 * M5 会替换为正式 UI。
 */
public class MainActivity extends Activity {

    private static final String TAG = "HuChuan";
    private static final int REQ_PERM = 1001;

    private TextView logView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private P2pManager p2p;
    private String testSsid, testPsk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        log("互传 M2 测试台");
        log("───────────────");
        log("M1 自检: " + selfTestM1());
        String[] miss = Perms.missing(this);
        if (miss.length > 0) {
            log("请求权限 (" + miss.length + " 项)…");
            requestPermissions(miss, REQ_PERM);
        } else {
            log("✅ 权限已全部授予");
            initP2p();
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        root.setPadding(p, p, p, p);

        TextView t = new TextView(this);
        t.setText("互传 · M2 测试台");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(t);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(8), 0, dp(8));
        bar.addView(btn("① 建组", new Runnable() { public void run() { doCreateGroup(); } }));
        bar.addView(btn("② 加入", new Runnable() { public void run() { doConnect(); } }));
        bar.addView(btn("③ 状态", new Runnable() { public void run() { doStatus(); } }));
        bar.addView(btn("清空", new Runnable() { public void run() { logView.setText(""); } }));
        root.addView(bar);

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setGravity(Gravity.START);
        logView.setMovementMethod(new ScrollingMovementMethod());
        ScrollView sc = new ScrollView(this);
        sc.addView(logView);
        root.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private Button btn(String label, final Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        return b;
    }

    // ---- 动作 ----

    private void initP2p() {
        try {
            p2p = new P2pManager(this);
            p2p.register();
            log("✅ P2pManager 初始化成功");
            maybeAutoTest();
        } catch (Throwable e) {
            log("❌ P2pManager 初始化失败: " + e);
        }
    }

    /**
     * 自动测试模式：Intent 带 autotest=true 时自动跑一次建组。
     * 用途：无需驱动 UI 即可验证（屏幕锁定时也能跑）。
     *   am start -n com.huchuan/.MainActivity --ez autotest true
     */
    private void maybeAutoTest() {
        if (!getIntent().getBooleanExtra("autotest", false)) return;
        log("=== 自动测试模式 ===");
        ui.postDelayed(new Runnable() { public void run() { doCreateGroup(); } }, 1500);
    }

    private void doCreateGroup() {
        if (p2p == null) { log("❌ P2P 未初始化"); return; }
        testSsid = P2pManager.randomSsid();
        testPsk = P2pManager.randomPsk();
        log("── 建组 ──");
        log("SSID=" + testSsid + "  PSK=" + testPsk);
        new Thread(new Runnable() { public void run() {
            try {
                android.net.wifi.p2p.WifiP2pGroup existing = p2p.requestGroupInfo();
                if (existing != null) {
                    log("移除已存在的组: " + existing.getNetworkName());
                    p2p.removeGroup();
                }
                P2pManager.Result r = p2p.createGroup(testSsid, testPsk, true);
                log(r.ok ? "✅ 建组成功" : "❌ 建组失败: " + r);
                P2pManager.Session s = p2p.getSession();
                log(s != null ? "会话: " + s : "（会话信息尚未就绪）");
            } catch (Throwable e) {
                log("❌ 建组异常: " + e);
            }
        }}).start();
    }

    private void doConnect() {
        if (p2p == null) { log("❌ P2P 未初始化"); return; }
        if (testSsid == null) { log("❌ 请先点①建组（本机将用生成的凭据加入）"); return; }
        log("── 加入 " + testSsid + " ──");
        new Thread(new Runnable() { public void run() {
            try {
                P2pManager.Result r = p2p.connect(testSsid, testPsk);
                log(r.ok ? "✅ 加入成功" : "❌ 加入失败: " + r);
                P2pManager.Session s = p2p.getSession();
                log(s != null ? "会话: " + s : "（会话信息尚未就绪）");
            } catch (Throwable e) {
                log("❌ 加入异常: " + e);
            }
        }}).start();
    }

    private void doStatus() {
        if (p2p == null) { log("❌ P2P 未初始化"); return; }
        new Thread(new Runnable() { public void run() {
            try {
                android.net.wifi.p2p.WifiP2pGroup g = p2p.requestGroupInfo();
                if (g == null) { log("当前无 P2P 组"); return; }
                log("组: " + g.getNetworkName() + " iface=" + g.getInterface()
                        + " owner=" + g.isGroupOwner());
                P2pManager.Session s = p2p.getSession();
                if (s != null) log("会话: " + s);
            } catch (Throwable e) {
                log("❌ 查询异常: " + e);
            }
        }}).start();
    }

    // ---- M1 自检 ----

    private String selfTestM1() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("EC 公钥 ").append(BleSecurity.getEncodedPublicKey().length()).append("B");
            String pk = BleSecurity.getEncodedPublicKey();
            BleSecurity.SessionCipher c = BleSecurity.deriveSessionKey(pk);
            sb.append(c.decrypt(c.encrypt("测试")).equals("测试") ? " / 加解密✅" : " / 加解密❌");
        } catch (Throwable e) { sb.append(" / 加密❌ ").append(e); }
        try {
            boolean ws = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".equals(
                    MiniWebSocket.makeAccept("dGhlIHNhbXBsZSBub25jZQ=="));
            sb.append(ws ? " / WS✅" : " / WS❌");
        } catch (Throwable e) { sb.append(" / WS❌"); }
        sb.append(" / UUID ").append(BleUtils.SERVICE_UUID.toString().substring(0, 8));
        return sb.toString();
    }

    // ---- 工具 ----

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        int granted = 0;
        for (int r : results) if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) granted++;
        log("权限结果: " + granted + "/" + perms.length + " 已授予");
        String[] miss = Perms.missing(this);
        if (miss.length > 0) {
            for (String m : miss) log("  ❌ 未授予: " + m);
            log("⚠️ 缺少权限，P2P 可能失败");
        }
        initP2p();
    }

    private void log(final String s) {
        android.util.Log.i(TAG, s);
        ui.post(new Runnable() { public void run() {
            if (logView != null) logView.append(s + "\n");
        }});
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (p2p != null) p2p.unregister();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
