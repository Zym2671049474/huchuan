package com.huchuan;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.Toast;

import com.huchuan.core.Perms;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 互传主界面。
 *
 * 发送：点「选择文件」→ 系统文件选择器 → 启动 SenderService
 * 接收：点「开始接收」→ 启动 ReceiverService，等待发送方
 */
public class MainActivity extends Activity {

    private static final String TAG = "HuChuan";
    private static final int REQ_PERM = 1001;
    private static final int REQ_PICK = 2002;

    /** 供 Service 回写日志。 */
    private static final AtomicReference<MainActivity> INSTANCE = new AtomicReference<>();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private TextView logView;
    private Button recvBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        INSTANCE.set(this);
        setContentView(buildUi());
        log("互传 v0.3");
        log("───────────────");

        String[] miss = Perms.missing(this);
        if (miss.length > 0) requestPermissions(miss, REQ_PERM);
        else log("✅ 权限已就绪");

        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    /** 处理从其他 app「分享」过来的文件。 */
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_VIEW.equals(action)) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null && Intent.ACTION_VIEW.equals(action)) uri = intent.getData();
        if (uri == null) return;
        try {
            File f = copyToCache(uri);
            log("分享文件: " + f.getName());
            startSend(f);
        } catch (Throwable t) {
            log("❌ 读取分享文件失败: " + t);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("互传");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("v0.3 · 与小米/HyperOS 互传");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setPadding(0, dp(2), 0, dp(14));
        root.addView(sub);

        Button send = new Button(this);
        send.setText("选择文件发送");
        send.setAllCaps(false);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });
        root.addView(send);

        recvBtn = new Button(this);
        recvBtn.setText("开始接收");
        recvBtn.setAllCaps(false);
        recvBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        recvBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleReceive(); }
        });
        root.addView(recvBtn);

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setGravity(Gravity.START);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setPadding(0, dp(12), 0, 0);
        ScrollView sc = new ScrollView(this);
        sc.addView(logView);
        root.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ---- 发送 ----

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择要发送的文件"), REQ_PICK);
        } catch (Throwable t) {
            log("❌ 无法打开文件选择器: " + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            File f = copyToCache(data.getData());
            log("已选择: " + f.getName() + " (" + f.length() + " 字节)");
            startSend(f);
        } catch (Throwable t) {
            log("❌ 读取文件失败: " + t);
        }
    }

    private void startSend(File f) {
        Intent i = new Intent(this, SenderService.class);
        i.putExtra(SenderService.EXTRA_PATH, f.getAbsolutePath());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        log("发送服务已启动");
    }

    /** 把 content:// 复制到私有缓存，得到真实路径。 */
    private File copyToCache(Uri uri) throws Exception {
        File dir = new File(getCacheDir(), "send");
        if (!dir.exists()) dir.mkdirs();
        String name = "file_" + System.currentTimeMillis();
        String last = uri.getLastPathSegment();
        if (last != null) {
            int slash = last.lastIndexOf('/');
            name = slash >= 0 ? last.substring(slash + 1) : last;
            int colon = name.indexOf(':');
            if (colon >= 0) name = name.substring(colon + 1);
        }
        File out = new File(dir, name);
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("openInputStream 返回 null");
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close(); in.close();
        return out;
    }

    // ---- 接收 ----

    private volatile boolean receiving = false;

    private void toggleReceive() {
        if (!receiving) {
            Intent i = new Intent(this, ReceiverService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            receiving = true;
            recvBtn.setText("停止接收");
            log("接收服务已启动，等待发送方…");
            Toast.makeText(this, "已开始接收，请让对方发送", Toast.LENGTH_SHORT).show();
        } else {
            stopService(new Intent(this, ReceiverService.class));
            receiving = false;
            recvBtn.setText("开始接收");
            log("接收已停止");
        }
    }

    // ---- 日志 ----

    /** 供 Service 调用。 */
    public static void postLog(final String s) {
        final MainActivity a = INSTANCE.get();
        if (a == null) return;
        UI.post(new Runnable() { @Override public void run() { a.appendLog(s); } });
    }

    private void appendLog(String s) {
        if (logView != null) logView.append(s + "\n");
    }

    private void log(final String s) {
        android.util.Log.i(TAG, s);
        appendLog(s);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        int ok = 0;
        for (int r : results) if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) ok++;
        log("权限 " + ok + "/" + perms.length + " 已授予");
        String[] miss = Perms.missing(this);
        for (String m : miss) log("  ❌ 缺少: " + m);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        INSTANCE.compareAndSet(this, null);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
