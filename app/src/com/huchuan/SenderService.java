package com.huchuan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.huchuan.core.BleSecurity;
import com.huchuan.core.BleUtils;
import com.huchuan.core.GattClient;
import com.huchuan.core.P2pManager;
import com.huchuan.model.DeviceInfo;
import com.huchuan.model.P2pInfo;
import com.huchuan.net.Transfer;

import org.json.JSONObject;

import java.io.File;

/**
 * 发送方前台服务。
 *
 * 完整流程：
 *   1. 起 HTTP 文件服务（拿端口）
 *   2. 建 WiFi Direct 组（拿 SSID/PSK）
 *   3. 起 WS 服务端（发送方是 server）
 *   4. BLE 扫描 → 连接接收方 → 读 DeviceInfo 拿公钥
 *   5. 加密 P2pInfo(ssid/psk/port) 写入 CHAR_P2P
 *   6. 等接收方连上 WS → 版本协商 → sendRequest → 传输
 */
public class SenderService extends Service {

    public static final String TAG = "HuChuan.Sender";
    private static final String CH = "huchuan_send";
    private static final int NOTI_ID = 1001;

    public static final String EXTRA_PATH = "path";

    private Transfer.Sender transfer;
    private P2pManager p2p;
    private GattClient client;
    private Thread worker;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTI_ID, notification("准备发送…"));
        final String path = intent != null ? intent.getStringExtra(EXTRA_PATH) : null;
        if (path == null) { stopSelf(); return START_NOT_STICKY; }

        worker = new Thread(new Runnable() { @Override public void run() {
            try { send(new File(path)); }
            catch (Throwable t) { Log.e(TAG, "发送失败", t); state("❌ " + t); }
            finally { cleanup(); stopSelf(); }
        }});
        worker.start();
        return START_NOT_STICKY;
    }

    private void send(File file) throws Exception {
        if (!file.exists()) { state("❌ 文件不存在: " + file); return; }
        state("文件: " + file.getName() + " (" + file.length() + " 字节)");

        // 1. HTTP
        transfer = new Transfer.Sender(file, deviceName(), new Transfer.Sender.Listener() {
            @Override public void onState(String m) { state(m); }
            @Override public void onResult(boolean ok, String m) { state((ok ? "✅ " : "❌ ") + m); }
        });
        int httpPort = transfer.startHttpServer();
        state("HTTP 端口 " + httpPort);

        // 2. WiFi Direct 建组
        p2p = new P2pManager(this);
        p2p.register();
        android.net.wifi.p2p.WifiP2pGroup g = p2p.requestGroupInfo();
        if (g != null) { state("移除旧组…"); p2p.removeGroup(); }
        String ssid = P2pManager.randomSsid();
        String psk = P2pManager.randomPsk();
        state("建组 " + ssid + " …");
        P2pManager.Result r = p2p.createGroup(ssid, psk, true);
        if (!r.ok) { state("❌ 建组失败: " + r); return; }
        P2pManager.Session s = p2p.getSession();
        state("组已建立: " + s);

        // 3. WS 服务端（用 httpPort+1 附近找个空闲端口）
        int wsPort = findFreePort();
        transfer.startWsServer(wsPort);
        state("WS 端口 " + wsPort);

        // 4. BLE 扫描找接收方
        client = new GattClient(this, new GattClient.Listener() {
            @Override public void onFound(android.bluetooth.BluetoothDevice d, int rssi) { state("发现 " + d.getAddress()); }
            @Override public void onDeviceInfo(android.bluetooth.BluetoothDevice d, DeviceInfo info) { state("读到设备公钥"); }
            @Override public void onState(String m) { state(m); }
        });
        state("扫描接收方…");
        client.startScan();

        // 等扫描到设备（最多 20 秒）
        android.bluetooth.BluetoothDevice target = null;
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            target = client.getLastFound();
            if (target != null) break;
            Thread.sleep(300);
        }
        if (target == null) { state("❌ 未扫描到接收方（请确认对方已开启「接收」）"); return; }

        DeviceInfo info = client.connectAndRead(target, 15);
        state("对端公钥长度 " + (info.key == null ? 0 : info.key.length()));

        // 5. 加密 P2pInfo 并写入
        JSONObject o = new JSONObject();
        if (info.key != null) {
            BleSecurity.SessionCipher cipher = BleSecurity.deriveSessionKey(info.key);
            o.put("id", BleUtils.getSenderId());
            o.put("ssid", cipher.encrypt(ssid));
            o.put("psk", cipher.encrypt(psk));
            o.put("mac", cipher.encrypt("02:00:00:00:00:00"));
            o.put("port", wsPort);
            o.put("key", BleSecurity.getEncodedPublicKey());
            o.put("catShare", 7);
        } else {
            o.put("id", BleUtils.getSenderId());
            o.put("ssid", ssid);
            o.put("psk", psk);
            o.put("mac", "02:00:00:00:00:00");
            o.put("port", wsPort);
            o.put("catShare", 7);
        }
        client.writeP2pInfo(o.toString(), 20);
        state("已把连接信息发给对方，等待其接入…");

        // 6. 等对端连上 WS 并完成协商
        boolean ok = transfer.requestSend(1, file.length(), guessMime(file), 60);
        state(ok ? "传输结束" : "对方未接受");
    }

    private int findFreePort() throws Exception {
        java.net.ServerSocket s = new java.net.ServerSocket(0);
        int p = s.getLocalPort();
        s.close();
        return p;
    }

    private static String guessMime(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".txt")) return "text/plain";
        return "*/*";
    }

    private String deviceName() {
        String m = Build.MODEL;
        return (m == null || m.isEmpty()) ? "HuChuan" : m;
    }

    private void state(String m) {
        Log.i(TAG, m);
        MainActivity.postLog("[发送] " + m);
        updateNotification(m);
    }

    private void cleanup() {
        try { if (transfer != null) transfer.close(); } catch (Throwable ignored) {}
        try { if (client != null) client.close(); } catch (Throwable ignored) {}
        try { if (p2p != null) { p2p.removeGroup(); p2p.unregister(); } } catch (Throwable ignored) {}
    }

    // ---- 通知 ----

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(new NotificationChannel(CH, "互传发送",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setContentTitle("互传 · 发送中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTI_ID, notification(text));
        } catch (Throwable ignored) {}
    }
}
