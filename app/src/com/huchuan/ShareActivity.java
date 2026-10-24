package com.huchuan;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.huchuan.core.GattClient;
import com.huchuan.core.Perms;
import com.huchuan.model.DeviceInfo;
import com.huchuan.ui.DeviceKind;
import com.huchuan.ui.KnownDevices;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从系统「分享」进来的轻量弹窗。
 *
 * 流程：扫描 BLE 设备 → 列出「圆形头像 + 设备名」→ 点头像即发送。
 *
 * 设计要点（对齐 AirDrop 的使用感受）：
 *   · 透明背景 + 底部圆角卡片，不打开完整 App 界面
 *   · 扫描 6 秒，边扫边出，设备逐个冒出来
 *   · 头像按设备名推断（手机 / 笔记本 / 平板 / 未知）
 *   · 点空白或返回键 = 取消
 */
public class ShareActivity extends Activity {

    private static final String TAG = "HuChuan.Share";
    private static final int REQ_PERM = 3001;
    /**
     * 设备"存活窗口"：超过这么久没再收到该设备的广播，就从列表移除。
     *
     * ★这是实时刷新的关键★
     * CatShare 的做法是"扫描一直开着，列表只增不减"（它靠 LifecycleResumeEffect
     * 在离开页面时才 stopScan）。但那样对方**关闭接收开关后，条目不会消失** ——
     * 因为停止广播只是"不再上报"，已经加进列表的条目没人去删。
     *
     * 我们的做法：扫描持续开着，同时给每台设备记一个"最后见到时间"，
     * 定时把超时的踢掉。这样：
     *   · 对方在线 → 一直刷新，实时可见
     *   · 对方关掉接收 → 窗口过后自动从列表消失
     */
    /** 地址 → 协议里的 deviceCode（用于判定设备类型）。 */
    private final java.util.Map<String, Integer> codes =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    /**
     * 多久没收到广播就判定设备已离开（P113：4 秒 → 2 秒）。
     *
     * 小米互传靠 `onDeviceLost` **事件**移除（由 BLE 栈/协议层通知），
     * 比我们拿秒表数快得多。我们没有那个事件，只能轮询 ——
     * 那就把窗口压短，贴近"它真走了列表立刻干净"的观感。
     *
     * 为什么是 2 秒而不是更短：
     *   · 小米互传的广播间隔约 300ms~1s（连发若干包）
     *   · 2 秒 = 至少跨 2 个广播周期，偶发丢一两个包不会误踢
     *   · 再短（如 1 秒）在信号弱时会看到设备"闪烁"
     */
    private static final long STALE_MS = 2000L;
    /**
     * 缓存设备的"确认宽限期"（P100）。
     *
     * BLE 广播不是立刻到的 —— 实测扫描开始后 1~5 秒才出现。
     * 宽限期内即使还没扫到也不清掉，避免"缓存设备刚显示就被删，
     * 真机又还没出现"的空白窗口。
     */
    private static final long CACHE_GRACE_MS = 8000L;
    /** 多久检查一次有没有设备过期。 */
    private static final long SWEEP_MS = 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout listRow;      // 头像横排
    private TextView hint;
    /**
     * 本轮是否由我们**自动打开了定位**。
     *
     * <p>两个用途：
     * <ol>
     *   <li>显示"定位已自动打开"提示 —— 我们替用户改了系统开关，
     *       必须让他知道，否则下次在别的 App 里发现"定位怎么自己开了"会困惑</li>
     *   <li>分享结束后**关回去** —— 只关我们打开的，用户自己开的不许动</li>
     * </ol>
     *
     * <p>★只有"原本关着 → 被我们打开"才置 true★。
     * 原本就开着的，这里保持 false，收尾时不会去碰它。
     */
    private boolean autoEnabledLocation;

    /**
     * 本轮是否由我们**自动打开了蓝牙**（收尾时要关回去）。
     *
     * <p>同样是"原本关着才置 true"，原本开着的绝不碰。
     */
    private boolean autoEnabledBluetooth;

    /**
     * 是否因"无 root 不能发送"而整块禁用了界面（P136）。
     *
     * <p>★必须挡住 updateHint() 和 sweep★：sweep 定时器每秒跑一次
     * updateHint()，会把拦截提示直接覆盖成"正在查找附近设备…"，
     * 用户看到的还是空列表 + 搜索中，等于拦截白做了（实测踩过）。
     */
    private boolean sendBlocked;
    private Uri pendingUri;
    private GattClient client;
    /** Activity 已销毁：所有 postDelayed 回调要提前退出，别碰已失效的 View。 */
    private volatile boolean destroyed;

    /** 已发现设备：address -> 名字。用 LinkedHashMap 保持发现顺序。 */
    private final Map<String, String> found = new LinkedHashMap<>();
    private final List<String> addresses = new ArrayList<>();
    /**
     * 名字 → 当前地址。用于去重：BLE 随机地址每次都变，
     * 只有名字是稳定的，否则同一台设备会被列成两个。
     */
    private final Map<String, String> byName = new LinkedHashMap<>();
    /**
     * 本次扫描**真正扫到**过的名字集合（只在主线程读写）。
     *
     * 缓存条目只是"先摆上去占位"，扫描结束后若没被扫到就要撤掉 ——
     * 否则对方关了接收开关、明明不广播了，卡片上还留着它（见 P063）。
     */
    /** 地址 → 该设备在列表里的 View，便于扫描结束后撤掉未确认的缓存条目。 */
    private final Map<String, View> viewOf = new LinkedHashMap<>();
    /** 名字 → 最后一次收到它广播的时间（毫秒）。用来判断设备是否还在。 */
    private final Map<String, Long> lastSeen = new LinkedHashMap<>();
    private final java.util.Set<String> confirmed = new java.util.LinkedHashSet<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWindow();

        // 先解析分享内容，拿不到就直接退出
        pendingUri = extractUri(getIntent());
        if (pendingUri == null) {
            // ★没有文件流，但可能是纯文字/网址★（P118）
            //
            // 小米浏览器分享网页时，很多情况**只有 EXTRA_TEXT（网址）**，
            // 没有 EXTRA_STREAM。我们原来只读 EXTRA_STREAM，于是：
            //   · 有 STREAM 就发图片（浏览器给的分享卡片截图）
            //   · 纯网址 → pendingUri == null → 直接 toast"没有可发送的文件"退出
            //
            // 用户明确要求："没 STREAM 就把文字/网址当文件发"。
            // 做法：把文字写成一个 .txt 文件，走完全相同的发送流程 ——
            // 这样接收端不用任何特殊处理，还能完整保留网址。
            pendingUri = uriFromSharedText(getIntent());
        }
        if (pendingUri == null) {
            toast("没有可发送的内容");
            finish();
            return;
        }

        setContentView(buildCard());
        // 诊断：打印实际取到的配色（深色模式是框架自动选资源，
        // 出问题时"看起来没变"分不清是没打包还是选了浅色）
        int uiMode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        Log.i(TAG, "主题=" + (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        ? "深色" : "浅色")
                + " sheet_bg=" + Integer.toHexString(c(R.color.sheet_bg))
                + " sheet_title=" + Integer.toHexString(c(R.color.sheet_title))
                + " sheet_hint=" + Integer.toHexString(c(R.color.sheet_hint)));
        // ★不再摆缓存设备★（P112）
        //
        // 原来这里调 showCached()：把 7 天内的历史设备先摆上去"秒出"。
        // 用户指出这是**误导** —— 一个显示"当前可发送设备"的列表里
        // 混进了 7 天前的记录，而且那些条目**照样可点**，点了白等 20 秒。
        //
        // 实测缓存内容（TTL = 7 天）：
        //   <string name="devices">77:73:DC:13:44:34&#1;Mi 15&#1;1792140479142</string>
        //
        // 更要命的是"半透明 = 缓存"这个信号用户根本看不懂，
        // 所以"设备变亮了"看起来就像"它刚刚出现在附近"，
        // 实际可能只是扫描确认了一个 7 天前见过的设备。
        //
        // 改成：**列表里出现的每一台，都是本轮真扫到的**（与 catShare 一致）。
        // 代价是打开后有 2~4 秒空窗 —— 用一句提示补上。
        searching();
        startScanIfPossible();
    }

    /**
 * 扫描期间的占位提示（P112）。
 *
 * ★不再列缓存设备★ —— 用户指出原实现有产品问题：
 * 列表里混进了 7 天内见过的历史设备，那些条目**照样可点**，
 * 点了白等 20 秒才报"没找到"。而"半透明=缓存"这个信号用户根本看不懂，
 * 所以"设备变亮了"看起来像"它刚出现在附近"。
 *
 * 小米互传的做法（反编译 `IDiscoveryCallback.aidl` + `i2/f`）：
 *   · 接口只有 onDeviceChanged / onDeviceLost —— **纯事件驱动**
 *   · onDeviceLost 实现就是 `map.remove(deviceId)` —— 真的走了就立刻移除
 *   · **没有任何缓存**：列表里有 ⟺ 现在真的在广播
 *
 * 所以我们照做：列表里出现的每一台，都是本轮真扫到的。
 * 代价是打开后 2~4 秒空窗 —— 用一句提示补上，而不是用假数据填。
 */
private void searching() {
    if (hint != null) hint.setText("正在搜索附近设备…");
}



    /**
     * 地址 → **原始大小写**的设备名（用于界面显示）。
     *
     * `byName` 的 key 是小写的（`nameKey()` 归一化过），
     * 拿它显示会变成 "mi 15" —— 之前就是这个问题（P086）。
     * 显示必须用这张表里的原始名字。
     */
    private final java.util.Map<String, String> displayName = new java.util.HashMap<>();

    /** 名字归一化，作为"同一台设备"的判断依据。 */
    private static String nameKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 回到前台 → 扫描切到高灵敏（P113）。
     *
     * 照小米互传的两档切换（`ble/central/a` 里 LOW_LATENCY ↔ LOW_POWER）。
     * 用户回到卡片就是要看设备列表，这时候不能漏包。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (client != null) {
            client.setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY);
        }
        // ★回到前台要重算提示★（P133）
        //
        // 用户很可能是"点提示词去开定位、再返回"—— 这条路径回来时
        // 定位已经开了，但 hint 还停在警告文案上。
        // 必须刷新一次，否则卡片看起来像卡住了。
        updateHint();
        ui.removeCallbacks(sweep);
        ui.post(sweep);      // 立刻恢复"每秒踢过期设备"的巡检
    }

    /**
     * 切到后台 → 扫描降到 LOW_POWER 省电（P113）。
     *
     * 此时用户看不到卡片，没必要高灵敏 —— 但**不能停扫**，
     * 否则用户切回来时列表是空的、要重新等 2~4 秒。
     * 降到低功耗档可以继续收广播，只是上报频率低一些。
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (client != null) {
            client.setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri u = extractUri(intent);
        if (u != null && !found.isEmpty()) {
            pendingUri = u;
            toast("已更新待发送文件");
        }
    }

    // ---------- 窗口：悬浮卡片，不压暗原界面 ----------

    private void setupWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        // 不要 FLAG_DIM_BEHIND：那会把底下的内容压暗，用户就看不清原来在干什么了。
        // 悬浮窗的观感 = 原内容照常显示，卡片浮在上面（带阴影）。
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        w.setDimAmount(0f);

        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.BOTTOM;
        // 悬浮感：卡片与屏幕边缘留出间距，四角圆润
        lp.y = dp(16);
        w.setAttributes(lp);

        // 系统栏也保持透明，避免出现黑边
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
    }

    // ---------- 界面 ----------

    private View buildCard() {
        // 外层：完全透明，点空白处取消
        FrameLayout root = new FrameLayout(this);
        root.setPadding(dp(12), dp(12), dp(12), dp(16));
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundDrawable(roundBg());
        card.setPadding(dp(20), dp(18), dp(20), dp(14));
        // 悬浮窗观感：白色卡片 + 阴影浮在原界面之上
        card.setElevation(dp(12));
        // 阻止点击穿透到 root
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* 吞掉 */ }
        });

        TextView title = new TextView(this);
        title.setText("发送到");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(c(R.color.sheet_title));
        card.addView(title);

        hint = new TextView(this);
        hint.setText("正在查找附近设备…");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(c(R.color.sheet_hint));
        hint.setPadding(0, dp(4), 0, dp(14));
        card.addView(hint);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        listRow = new LinearLayout(this);
        listRow.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(listRow);
        card.addView(hsv);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cancel.setTextColor(c(R.color.accent));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(14), 0, dp(4));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        card.addView(cancel);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        root.addView(card, clp);
        return root;
    }

    private Drawable roundBg() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(c(R.color.sheet_bg));
        g.setCornerRadius(dp(20));
        return g;
    }

    // ---------- 扫描 ----------

    private void startScanIfPossible() {
        String[] miss = Perms.missing(this);
        if (miss.length > 0) {
            requestPermissions(miss, REQ_PERM);
            hint.setText("需要蓝牙权限…");
            return;
        }
        // ★无 root 直接拦住，连扫描都不用做★（P136）
        //
        // 但 hasRoot() 要真的 exec 试 su（可能卡 2.5 秒），不能放主线程。
        // 所以先查一次"已经探测过吗"：探测过就同步判断（微秒级），
        // 没探测过就先显示"正在检查…"再丢后台。
        if (com.huchuan.core.SystemToggles.isRootProbed()) {
            if (blockIfNoRoot()) return;
        } else {
            hint.setText("正在检查运行环境…");
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean noRoot = !com.huchuan.core.SystemToggles.hasRoot();
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (destroyed) return;
                            if (noRoot && blockIfNoRoot()) return;
                            ensureRadioOn();
                        }
                    });
                }
            }, "check-root").start();
            return;
        }

        // ★先确保蓝牙/定位是开着的★（P134）
        //
        // 这两个开关关着时扫描会**静默失败**（见 P133）。与其让用户看到
        // "一直在搜索…"，不如在扫描之前就自动把它们打开。
        // 具体能自动到什么程度取决于有没有 root，交给 SystemToggles 判断。
        ensureRadioOn();
    }

    /**
     * 自动打开蓝牙和定位，然后再开始扫描（P134）。
     *
     * <p>分两步走，因为能力不同：
     * <ol>
     *   <li><b>有 root</b>：直接开（{@code svc bluetooth enable} /
     *       {@code settings put secure location_mode 3}），开完立即扫，
     *       用户零操作</li>
     *   <li><b>没 root</b>：蓝牙弹系统对话框（Android 13+ 第三方 App
     *       不能直接开），定位只能跳设置页 —— 定位没有公开 API</li>
     * </ol>
     *
     * <p>开蓝牙要等适配器真的变成 ON（1~3 秒）才能扫，否则扫不到任何东西。
     * 所以这里放到后台线程等待，期间界面上给一句"正在打开蓝牙…"。
     */
    /**
     * 无 root 时**不能发送**，直接拦住并说明原因（P136）。
     *
     * <h3>为什么无 root 发不出去</h3>
     *
     * 互传协议里，发送方要把自己的 p2p0 MAC 告诉对端，对端拿它来连我们。
     * 而这个地址在 Android 10+ 对普通 App **读不到**：
     *
     * <pre>
     * FileNotFoundException: /sys/class/net/p2p0/address:
     *     open failed: EACCES (Permission denied)
     * </pre>
     *
     * p2p0 属于 SELinux 的 {@code sysfs_net} 域，{@code untrusted_app} 域被
     * 明确拒绝读取（app 进程上下文 {@code u:r:untrusted_app_34:s0}）。
     * 拿不到就只能填假地址 02:00:00:00:00:00 → 对端连不上 →
     * 卡在版本协商超时（P058），表现为"文件发出去了但对方没过来"。
     *
     * <p>所以与其让用户等 20 秒后失败，不如**一开始就说清楚**。
     * 接收方向不受影响（那是对方连我们，不需要我们读自己的 MAC）。
     *
     * @return true 表示已被拦住，不要继续扫描
     */
    private boolean blockIfNoRoot() {
        if (com.huchuan.core.SystemToggles.hasRoot()) return false;

        found.clear();
        if (listRow != null) listRow.removeAllViews();
        hint.setTextColor(c(R.color.sheet_hint));
        hint.setOnClickListener(null);
        hint.setText("当前设备未获得 root 权限，无法发送文件\n"
                + "发送需要读取 p2p0 网卡地址，普通应用权限不足。\n"
                + "接收文件可以正常使用。");
        sendBlocked = true;
        ui.removeCallbacks(sweep);     // 停掉每秒巡检，界面不会再变
        Log.i(TAG, "无 root，已禁用发送");
        return true;
    }

    private void ensureRadioOn() {
        if (com.huchuan.core.SystemToggles.allReady(this)) {
            beginScan();          // 都开着，直接扫
            return;
        }

        // ★全部丢到后台线程★
        //
        // hasRoot() 要 exec 试 su（最长 2.5 秒），开蓝牙还要轮询等适配器
        // 上电（1~3 秒）。这些一旦跑在主线程，卡片就直接卡死不动了 ——
        // 分享卡片本身是个 Activity，ANR 门槛只有 5 秒。
        hint.setText("正在打开蓝牙…");
        new Thread(new Runnable() {
            @Override public void run() {
                boolean hasRoot = com.huchuan.core.SystemToggles.hasRoot();
                final boolean bt;
                final boolean loc;
                // 先记下"动手之前"的状态 —— 后面靠它判断是不是我们打开的
                final boolean locWasOn = com.huchuan.core.SystemToggles
                        .isLocationOn(ShareActivity.this);
                final boolean btWasOn = com.huchuan.core.SystemToggles
                        .isBluetoothOn(ShareActivity.this);
                if (hasRoot) {
                    bt = com.huchuan.core.SystemToggles
                            .tryEnableBluetoothByRoot(ShareActivity.this);
                    loc = com.huchuan.core.SystemToggles
                            .tryEnableLocationByRoot(ShareActivity.this);
                    Log.i(TAG, "root 自动开启: 蓝牙=" + bt + " 定位=" + loc);
                    // ★只有"原本没开、被我们打开"才标记★
                    // 原本就开着的保持 false —— 收尾时不会去关它。
                    autoEnabledLocation = loc && !locWasOn;
                    autoEnabledBluetooth = bt && !btWasOn;
                } else {
                    bt = com.huchuan.core.SystemToggles.isBluetoothOn(ShareActivity.this);
                    loc = com.huchuan.core.SystemToggles.isLocationOn(ShareActivity.this);
                    Log.i(TAG, "无 root，只能提示: 蓝牙=" + bt + " 定位=" + loc);
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        if (!hasRoot) {
                            // 没 root：蓝牙弹系统对话框（唯一合规路径），
                            // 定位没有公开 API，只能靠 updateHint 里的提示
                            if (!bt) {
                                com.huchuan.core.SystemToggles
                                        .requestBluetoothViaDialog(ShareActivity.this);
                            }
                        }
                        beginScan();
                        updateHint();
                    }
                });
            }
        }, "enable-radio").start();
    }

    @SuppressWarnings("MissingPermission")
    private void beginScan() {
        try {
            client = new GattClient(this, new GattClient.Listener() {
                @Override public void onFound(final android.bluetooth.BluetoothDevice device,
                                              int rssi, final String parsedName) {
                    final String addr = device.getAddress();
                    // ★带上协议里的设备类别★（P104）—— 比按名字猜准
                    GattClient.AdvInfo adv = client != null ? client.getAdvInfo(addr) : null;
                    final int code = adv != null ? adv.deviceCode : -1;
                    // ★名字由回调直接带过来★（P100）
                    //
                    // 原来写的是 `client.getNameOf(addr)` —— 但扫描回调在
                    // `new GattClient(...)` 返回**之前**就可能触发，
                    // 那时 client 字段还是 null，反查必然失败，
                    // 于是回落到 device.getName()（BLE 随机地址下为 null）
                    // → 界面显示成一串 MAC："未识别的设备"。
                    // 现在直接用回调参数；反查只作为额外兜底。
                    String parsed = parsedName;
                    if (parsed == null || parsed.isEmpty()) {
                        parsed = client != null ? client.getNameOf(addr) : null;
                    }
                    final String name = (parsed != null && !parsed.isEmpty())
                            ? parsed : safeName(device);
                    final String nk = nameKey(name);
                    ui.post(new Runnable() { @Override public void run() {
                        if (destroyed) return;
                        // 真扫到了 → 刷新存活时间 + 标记已确认（两者都只在主线程读写）
                        lastSeen.put(nk, System.currentTimeMillis());
                        confirmed.add(nk);
                        // 扫描到的 (地址,名字) 也登记，供后续按名字兜底
                        if (client != null) client.rememberName(addr, name);
                        codes.put(addr, code);
                        addDevice(addr, name);
                        updateHint();
                    }});
                }
                @Override public void onDeviceInfo(android.bluetooth.BluetoothDevice d, DeviceInfo info) { }
                @Override public void onState(String msg) { Log.i(TAG, msg); }
            });
            client.startScan();
            hint.setText("正在查找附近设备…");
        } catch (Throwable t) {
            Log.e(TAG, "扫描失败", t);
            hint.setText("扫描失败：" + t.getMessage());
            return;
        }
        // 扫描持续开着，靠定时 sweep 把"不再广播"的设备踢掉（实时刷新）
        ui.postDelayed(sweep, SWEEP_MS);
    }

    /**
     * 周期性清理：把超过 STALE_MS 没再收到广播的设备从列表移除。
     *
     * 这样对方**关闭接收开关**后，它的条目会在窗口过后自己消失，
     * 而不是一直挂在卡片上（P063/P065）。
     */
    private final Runnable sweep = new Runnable() {
        @Override public void run() {
            final long now = System.currentTimeMillis();
            final java.util.List<String> dead = new ArrayList<>();
            for (java.util.Map.Entry<String, Long> e : lastSeen.entrySet()) {
                if (now - e.getValue() > STALE_MS) dead.add(e.getKey());
            }
            for (String key : dead) removeByName(key);

            if (destroyed) return;
            updateHint();
            ui.postDelayed(this, SWEEP_MS);
        }
    };

    /** 按名字移除一台设备（界面 + 所有映射）。 */
    private void removeByName(String key) {
        lastSeen.remove(key);
        confirmed.remove(key);
        String addr = byName.remove(key);
        if (addr == null) return;
        found.remove(addr);
        addresses.remove(addr);
        View v = viewOf.remove(addr);
        if (v != null) listRow.removeView(v);
        Log.i(TAG, "设备已离线，移出列表: " + key);
    }

    private void updateHint() {
        if (destroyed) return;
        if (sendBlocked) return;      // 已被"无 root"拦住，别覆盖提示（P136）
        if (!found.isEmpty()) {
            hint.setText("找到 " + found.size() + " 个设备");
            return;
        }
        // ★空列表要区分"正在找"和"根本扫不了"★（P133）
        //
        // 定位关着时 BLE 扫描会**静默失败**：权限都在、startScan 不报错、
        // 日志也有"开始扫描"，但一个广播都收不到。用户只看到"正在查找…"
        // 干等，完全无从判断 —— 实测踩过，排查一圈还以为禁用了 Google 组件。
        // 所以这里直接把原因说出来（只提示，不跳转）。
        boolean locOn = Perms.isLocationEnabled(this);
        if (locOn) {
            // ★自动开过定位要说一声★
            //
            // 我们替用户改了系统开关，必须让他知道 —— 否则下次在别的 App
            // 里发现"定位怎么自己开了"会困惑。这句只在"原本没开、我们打开"
            // 的那一轮出现，用户自己开的情况不显示。
            if (autoEnabledLocation) {
                hint.setText("定位已自动打开，正在查找附近设备…\n请确认对方已开启「接收」");
            } else {
                hint.setText("正在查找附近设备…\n请确认对方已开启「接收」");
            }
            // ★复原配色与点击事件★ —— 定位可能是刚打开、也可能是上一轮
            // 从关闭状态切回来的，不复原的话那句"正在查找"会一直是强调色。
            hint.setTextColor(c(R.color.sheet_hint));
            hint.setOnClickListener(null);
            hint.setClickable(false);
        } else {
            // ★只提示，不跳转★
            //
            // 之前的做法是这里挂一个点击事件直接跳系统设置页。用户反馈
            // 那样很突兀 —— 分享卡片本来是个浮层，突然被整页设置盖掉，
            // 回来还得重新触发一次分享。
            // 改成纯文字说明：用户看到"未开启定位"自己从控制中心拉一下就行，
            // 整个过程不离开当前界面。
            hint.setText("定位未开启，暂时搜不到设备\n请从控制中心打开「定位」，返回后自动继续搜索");
            hint.setTextColor(c(R.color.accent));
            hint.setOnClickListener(null);
            hint.setClickable(false);
        }
    }

    private void stopScan() {
        if (client != null) { try { client.stopScan(); } catch (Throwable ignored) {} }
        dropUnconfirmed();
    }

    /**
     * 移除本次扫描未再发现的缓存条目。
     *
     * 缓存的意义是"先把上次见过的摆上去，避免第一眼空白"，
     * 但它**必须**在扫描结束后被证伪 —— 否则对方关了接收开关、
     * 已经不再广播，卡片上却还留着它，点下去必然失败。
     */
    private void dropUnconfirmed() {
        final java.util.List<String> dead = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : byName.entrySet()) {
            if (!confirmed.contains(e.getKey())) dead.add(e.getKey());
        }
        if (dead.isEmpty()) return;
        for (String k : dead) {
            String addr = byName.remove(k);
            if (addr != null) {
                found.remove(addr);
                addresses.remove(addr);
                final View v = viewOf.remove(addr);
                if (v != null) listRow.removeView(v);
            }
            Log.i(TAG, "移除未确认的缓存设备: " + k);
        }
    }

    /** BLE 广播里的名字可能是 null，回落到地址。 */
    @SuppressWarnings("MissingPermission")
    private String safeName(android.bluetooth.BluetoothDevice d) {
        try {
            String n = d.getName();
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Throwable ignored) { }
        String a = d.getAddress();
        return a == null ? "未知设备" : a;
    }

    /**
     * 收到一台设备（可能是缓存占位被证实，也可能是新出现）。
     *
     * 以**名字**为主键：BLE 随机地址每次扫描都会变，
     * 只有名字（来自广播 service data）是稳定的。
     * 地址变了就把旧地址相关的映射整体迁到新地址，避免出现
     * "界面上一台、found 里两台"或"点击时地址对不上"的错位（P064）。
     */
    private void addDevice(final String addr, String name) {
        final String key = nameKey(name);
        final String oldAddr = byName.get(key);

        if (oldAddr != null) {
            // 已存在这台（缓存占位或本轮已加过）→ 更新，不新增条目
            if (!oldAddr.equalsIgnoreCase(addr)) {
                int idx = addresses.indexOf(oldAddr);
                if (idx >= 0) addresses.set(idx, addr);
                found.remove(oldAddr);
                displayName.remove(oldAddr);
                byName.put(key, addr);
                View v = viewOf.remove(oldAddr);
                if (v != null) viewOf.put(addr, v);
            }
            found.put(addr, name);                 // 地址变了也要保证新地址有名字
            displayName.put(addr, name);           // 原始大小写，用于显示（P086）
            // 头像点亮：缓存占位是半透明的，被证实后变实心
            View v = viewOf.get(addr);
            if (v instanceof LinearLayout) {
                View avatar = ((LinearLayout) v).getChildAt(0);
                if (avatar != null) avatar.setAlpha(1f);
            }
            KnownDevices.remember(this, addr, name);
            return;
        }

        // 新设备
        byName.put(key, addr);
        found.put(addr, name);
        displayName.put(addr, name);
        addresses.add(addr);
        addDeviceView(addr, name, true);
        KnownDevices.remember(this, addr, name);
        hint.setText("找到 " + found.size() + " 个设备");
    }

    /** 按 found 里的顺序整体重建列表（名字更新后用）。 */
    private void rebuildList() {
        listRow.removeAllViews();
        for (String a : addresses) {
            addDeviceView(a, found.get(a), true);
        }
    }

    private void addDeviceView(final String addr, final String name, boolean fresh) {
        // ★优先按协议 deviceCode 判定，拿不到才按名字猜★（P104）
        Integer c = codes.get(addr);
        DeviceKind.Kind kind = DeviceKind.of(name, c == null ? -1 : c);
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(10), 0, dp(10), 0);

        ImageView avatar = new ImageView(this);
        int size = dp(72);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        avatar.setImageResource(iconFor(kind));
        avatar.setAlpha(fresh ? 1f : 0.55f);   // 缓存设备先半透明，扫到后变实
        // ★点击时**重新解析**当前地址，不要直接用闭包里的 addr★（P099）
        //
        // 小米15 的 BLE 地址每轮扫描都变。addDevice() 在扫到真机后会把
        // addresses/found/displayName 里的**旧地址换成新地址**，
        // 但这个 onClick 是建 View 时就绑好的，捕获的还是**旧地址**。
        // 结果：点下去用的是缓存里的过期地址 →
        //   · displayName.get(旧地址) == null → 通知显示成一串 MAC
        //   · getFound(旧地址) 找不到 → 20 秒后"没找到"
        // 所以这里按**名字**（本轮恒定）反查地址。
        avatar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String live = resolveAddress(name, addr);
                Log.i(TAG, "点击设备 name=" + name + " 记录地址=" + addr + " 当前地址=" + live);
                sendTo(live);
            }
        });
        item.addView(avatar);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(c(R.color.sheet_title));
        label.setGravity(Gravity.CENTER);
        label.setMaxWidth(dp(88));
        label.setMaxLines(2);
        label.setPadding(0, dp(8), 0, 0);
        item.addView(label);

        listRow.addView(item);
        viewOf.put(addr, item);          // 记住 View，扫描结束后可撤掉未确认条目
    }

    /**
     * 按名字反查**当前**有效地址。
     *
     * 名字在本轮扫描里是稳定的，地址不是（小米15 每轮都变）。
     * 优先用 byName 里最新的地址；查不到才退回传进来的旧地址。
     */
    private String resolveAddress(String name, String fallback) {
        String cur = byName.get(nameKey(name));
        if (cur != null && found.containsKey(cur)) return cur;
        // 列表里还有这个旧地址（没被换掉）也可以直接用
        if (found.containsKey(fallback)) return fallback;
        return cur != null ? cur : fallback;
    }

    /** 按地址反查名字（byName 是 name→address，这里反向找）。 */
    private String nameOfAddress(String address) {
        if (address == null) return null;
        for (java.util.Map.Entry<String, String> e : byName.entrySet()) {
            if (address.equalsIgnoreCase(e.getValue())) {
                // byName 的 key 是小写的，去 displayName 里取原始大小写
                for (java.util.Map.Entry<String, String> d : displayName.entrySet()) {
                    if (nameKey(d.getValue()).equals(e.getKey())) return d.getValue();
                }
                return e.getKey();
            }
        }
        return null;
    }

    private int iconFor(DeviceKind.Kind k) {
        int id = getResources().getIdentifier(
                "ic_dev_" + DeviceKind.iconSuffix(k), "drawable", getPackageName());
        return id != 0 ? id : android.R.drawable.ic_menu_help;
    }

    // ---------- 发送 ----------

    private void sendTo(String address) {
        try {
            // ★先把"地址 → 名字"告诉扫描器★（P083）
            // 小米15 的 BLE 地址每次扫描都变，按地址找永远找不到；
            // 登记名字后 getFound 能按名字兜底，第一次发送就能成功。
            if (client != null) {
                String nm = found.get(address);
                if (nm == null) {
                    for (java.util.Map.Entry<String, String> e : byName.entrySet()) {
                        if (address.equalsIgnoreCase(e.getValue())) { nm = e.getKey(); break; }
                    }
                }
                if (nm != null) client.rememberName(address, nm);
            }
            File f = copyToCache(pendingUri);
            Intent i = new Intent(this, SenderService.class);
            i.setAction(SenderService.ACTION_SEND_NOW);
            i.putExtra(SenderService.EXTRA_PATH, f.getAbsolutePath());
            i.putExtra(SenderService.EXTRA_TARGET, address);
            // 把显示名一起传过去，服务端不需要再猜（P086 + P099）
            //
            // ★三重兜底★：地址可能是过期的（小米15 每轮换 MAC）。
            //   displayName → found → 反查 byName
            // 任何一层拿到名字，都不会把 MAC 显示到通知上。
            String nm = displayName.get(address);
            if (nm == null) nm = found.get(address);
            if (nm == null) nm = nameOfAddress(address);
            if (nm != null) i.putExtra(SenderService.EXTRA_TARGET_NAME, nm);
            startForegroundService(i);
            // 名字取原始大小写版本（displayName），不要再拿 byName 的 key
            // —— 那是小写的，会显示成 "mi 15"（P086）。
            String label = displayName.get(address);
            if (label == null) label = found.get(address);
            if (label == null) label = nameOfAddress(address);
            toast("正在发送到 " + (label == null ? "设备" : label));
            finish();
        } catch (Throwable t) {
            Log.e(TAG, "发送失败", t);
            toast("发送失败：" + t.getMessage());
        }
    }

    /** 把分享进来的 content:// 复制到 cache，拿到真实文件。 */
    private File copyToCache(Uri uri) throws Exception {
        String name = "share_" + System.currentTimeMillis();
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) name = n;
                }
                c.close();
            }
        } catch (Throwable ignored) { }

        // ★file:// 没有 OpenableColumns，名字要自己从路径取★（P118）
        //
        // 上面那个 query 只对 content:// 有效。对 file://（比如我们自己
        // 刚写出来的 `分享内容_<ts>.txt`）query 会抛异常被吞掉，
        // 于是回落到 "share_<13位时间戳>" —— **后缀 .txt 丢了**。
        //
        // 后果：对方收到一个没有扩展名的文件，双击打不开、也不知道是什么。
        // 这里补一条：回落路径下如果 Uri 带路径，就用它的文件名。
        if (name.startsWith("share_") && "file".equals(uri.getScheme())) {
            String p = uri.getPath();
            if (p != null) {
                int slash = p.lastIndexOf('/');
                String base = slash >= 0 ? p.substring(slash + 1) : p;
                if (!base.isEmpty()) name = base;
            }
        }

        File out = new File(getCacheDir(), name);
        // ★绝不能覆盖自己正在读的那个文件★（P120）
        //
        // 上面的文件名解析对 content:// 是必要的。但对**我们自己刚写出来的
        // file:// 缓存**（`分享内容_<ts>.txt`）它是致命的：源文件就在
        // getCacheDir() 里，而这里 `new FileOutputStream(out)` 会先
        // **把文件截断成 0 字节**，再去读同一个文件 —— 结果恒定 0 字节。
        //
        // 实测（用户报"是个 txt 但是为空"）：
        //   cache/分享内容_1792144402730.txt  →  0 字节
        //   sendRequest: {"fileName":"分享内容_1792144402730.txt","totalSize":0}
        //
        // 修法：目标路径与源路径相同（canonical）时，**原地返回源文件**，
        // 不做任何复制 —— 它本来就已经是我们要发送的那个文件。
        try {
            if ("file".equals(uri.getScheme())
                    && out.getCanonicalPath().equals(new File(uri.getPath()).getCanonicalPath())) {
                Log.i(TAG, "分享内容已在缓存中，直接使用: " + out.getName());
                return out;
            }
        } catch (Throwable ignored) { }

        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("无法打开分享的文件");
        FileOutputStream fos = new FileOutputStream(out);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            try { fos.close(); } catch (Throwable ignored) { }
            try { in.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    /**
     * 把分享进来的纯文字/网址写成缓存文件，返回它的 Uri（P118）。
     *
     * 为什么走"写成文件"这条路，而不是给发送流程加一条"文字专用通道"：
     *   · 发送链路（BLE 广播 → P2P → WS → ZIP）**只认文件**，
     *     为文字单开一条路要改协议两端，成本和风险都大
     *   · 写成 .txt 后，**接收端不需要任何改动**就能收到，
     *     而且网址是完整原文，不会像"分享卡片截图"那样丢失
     *
     * 取名 `分享内容_<时间>.txt` —— 接收端会按 DISPLAY_NAME 落盘，
     * 一眼能看出这是什么。
     *
     * @return 写好后的 Uri；没有可发的文字则返回 null
     */
    private Uri uriFromSharedText(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action)) return null;

        CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (cs == null) return null;
        String text = cs.toString();
        if (text.trim().isEmpty()) return null;

        // 有的浏览器把网址放在 EXTRA_SUBJECT / EXTRA_TITLE，一并带上更完整
        CharSequence subj = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        String subject = subj == null ? "" : subj.toString().trim();

        // ★必须带 .txt 后缀★
        // 接收端按文件名保存；没有后缀的话对方打开是一坨未知文件。
        // 同时也让发送端的 MIME 判定（按扩展名）能得出 text/plain。
        String name = "分享内容_" + System.currentTimeMillis() + ".txt";
        File out = new File(getCacheDir(), name);
        try {
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(out), "UTF-8");
            try {
                if (!subject.isEmpty() && !subject.equals(text)) {
                    w.write(subject);
                    w.write("\n\n");
                }
                w.write(text);
                w.write("\n");
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "写分享文字失败", t);
            return null;
        }
        Log.i(TAG, "分享的是文字/网址（" + text.length() + " 字），已写成 " + name);
        return Uri.fromFile(out);
    }

    private Uri extractUri(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (Intent.ACTION_VIEW.equals(action)) {
            return intent.getData();
        }
        return null;
    }

    // ---------- 杂项 ----------

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        String[] miss = Perms.missing(this);
        if (miss.length == 0) beginScan();
        else hint.setText("缺少权限，无法扫描");
    }

    @Override
    protected void onDestroy() {
        destroyed = true;               // 让 sweep 等回调提前退出
        ui.removeCallbacks(sweep);
        super.onDestroy();
        stopScan();
        restoreRadio();
    }

    /**
     * 分享结束后，把我们**自动打开**的开关关回去（P134）。
     *
     * <p>★只关我们打开的★ —— 调用前必须先看 {@link #autoEnabledBluetooth} /
     * {@link #autoEnabledLocation}，这两个标志只在"原本关着 → 被我们打开"
     * 时才是 true。用户本来就开着的开关，这里绝不碰。
     *
     * <p>为什么拖 800ms：{@code onDestroy} 紧跟在用户点完"发送"之后，
     * 这时候传输可能还在收尾（WiFi Direct 建组、对端连进来）。
     * 立刻关蓝牙会把正在进行的连接一起掐掉。等一小会儿再关，
     * 既不影响收尾，也不会让开关一直开着。
     *
     * <p>放后台线程：su 命令是阻塞的，主线程调用会卡住退出动画。
     */
    private void restoreRadio() {
        if (!autoEnabledBluetooth && !autoEnabledLocation) return;   // 没动过系统开关

        final boolean needBt = autoEnabledBluetooth;
        final boolean needLoc = autoEnabledLocation;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(800);       // 等传输收尾
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (needBt) {
                    boolean ok = com.huchuan.core.SystemToggles
                            .tryDisableBluetoothByRoot();
                    Log.i(TAG, "恢复蓝牙为关闭: " + ok);
                }
                if (needLoc) {
                    boolean ok = com.huchuan.core.SystemToggles
                            .tryDisableLocationByRoot();
                    Log.i(TAG, "恢复定位为关闭: " + ok);
                }
            }
        }, "restore-radio").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * 取颜色资源（跟随深色模式）。
     *
     * <p>API 23 起 getColor(id) 已废弃且不理会主题，必须走 getColor(id, theme)，
     * 否则拿到的永远是浅色那套值。调用点有 5 处，统一走这个方法。
     */
    @SuppressWarnings("deprecation")
    private int c(int colorRes) {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            return getResources().getColor(colorRes, getTheme());
        }
        return getResources().getColor(colorRes);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
