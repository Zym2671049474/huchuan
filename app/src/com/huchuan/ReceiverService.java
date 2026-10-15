package com.huchuan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import com.huchuan.core.GattServer;
import com.huchuan.core.P2pManager;
import com.huchuan.model.P2pInfo;
import com.huchuan.net.Transfer;

import org.json.JSONObject;

import java.io.File;

/**
 * 接收方前台服务。
 *
 * 流程：
 *   1. 起 GATT Server + BLE 广播（等待发送方发现）
 *   2. 发送方写入 P2pInfo → 拿到 SSID/PSK/port
 *   3. 加入 WiFi Direct 组
 *   4. 连发送方的 WS → 收 sendRequest → 回 status(1=接受)
 *   5. HTTP 拉文件到 Download/互传/
 */
public class ReceiverService extends Service {

    public static final String TAG = "HuChuan.Receiver";
    private static final String CH = "huchuan_recv";
    private static final int NOTI_ID = 1002;

    private GattServer gatt;
    private P2pManager p2p;
    private Thread worker;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTI_ID, notification("等待对方连接…"));
        worker = new Thread(new Runnable() { @Override public void run() {
            try { receive(); }
            catch (Throwable t) { Log.e(TAG, "接收失败", t); state("❌ " + t); }
        }});
        worker.start();
        return START_NOT_STICKY;
    }

    private void receive() throws Exception {
        // 1. GATT server + 广播
        gatt = new GattServer(this, new GattServer.Listener() {
            @Override public void onP2pInfo(P2pInfo info) {
                Log.i(TAG, "收到 P2pInfo: " + info.ssid + " port=" + info.port);
                state("收到连接信息，准备加入组…");
                new Thread(new Runnable() { @Override public void run() {
                    try { joinAndDownload(info); }
                    catch (Throwable t) { state("❌ " + t); }
                }}).start();
            }
            @Override public void onState(String msg) { state(msg); }
        });
        gatt.start();
        state("已开始广播，等待发送方…");

        // 保持存活直到被停止
        while (!Thread.currentThread().isInterrupted()) Thread.sleep(1000);
    }

    private void joinAndDownload(P2pInfo info) throws Exception {
        // 2. 加入组
        p2p = new P2pManager(this);
        p2p.register();
        state("加入组 " + info.ssid + " …");
        P2pManager.Result r = p2p.connect(info.ssid, info.psk);
        if (!r.ok) { state("❌ 加入失败: " + r); return; }
        P2pManager.Session s = p2p.getSession();
        state("已加入: " + s);

        String host = (s != null && s.groupOwnerAddress != null)
                ? s.groupOwnerAddress : "192.168.49.1";
        state("发送方地址 " + host + ":" + info.port);

        // 3. WS 连接
        Transfer.Receiver recv = new Transfer.Receiver(recvDir(), deviceName(),
                new Transfer.Receiver.Listener() {
            @Override public void onState(String m) { state(m); }
            @Override public void onResult(boolean ok, String m) { state((ok ? "✅ " : "❌ ") + m); }
        });
        recv.connect(host, info.port, 20);
        state("已连上发送方，等待传输请求…");

        // 4. 等 sendRequest
        JSONObject req = recv.awaitSendRequest(30);
        if (req == null) { state("❌ 等待传输请求超时"); recv.close(); return; }

        String taskId = req.optString("taskId", req.optString("id"));
        String fileName = req.optString("fileName", "received.bin");
        long totalSize = req.optLong("totalSize", 0);
        String senderName = req.optString("senderName", "对方");
        state("来自 " + senderName + " 的文件: " + fileName + " (" + totalSize + " 字节)");

        // 自动接受（M5 会加确认对话框）
        recv.respond(taskId, 1, "ok");
        state("已接受，开始下载…");

        // 5. 下载
        recv.download(host, info.port, taskId, fileName, new Transfer.Progress() {
            @Override public void onProgress(long sent, long total) {
                int pct = total > 0 ? (int) (sent * 100 / total) : 0;
                state("接收中 " + pct + "%");
            }
            @Override public void onDone(boolean ok, String msg) {
                state(ok ? "✅ 已保存: " + msg : "❌ 下载失败: " + msg);
            }
        });
        recv.close();
    }

    /** 保存目录：/sdcard/Download/互传/ */
    private File recvDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "互传");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private String deviceName() {
        String m = Build.MODEL;
        return (m == null || m.isEmpty()) ? "HuChuan" : m;
    }

    private void state(String m) {
        Log.i(TAG, m);
        MainActivity.postLog("[接收] " + m);
        updateNotification(m);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { if (gatt != null) gatt.stop(); } catch (Throwable ignored) {}
        try { if (p2p != null) { p2p.removeGroup(); p2p.unregister(); } } catch (Throwable ignored) {}
        if (worker != null) worker.interrupt();
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(new NotificationChannel(CH, "互传接收",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setContentTitle("互传 · 接收中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
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
