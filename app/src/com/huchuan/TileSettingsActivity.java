package com.huchuan;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.huchuan.core.NearbyDevices;
import com.huchuan.core.ReceiverState;
import java.util.List;

/**
 * 磁贴长按打开的设置页。
 *
 * 系统在长按磁贴时会启动声明了
 * {@code android.service.quicksettings.action.QS_TILE_PREFERENCES}
 * 的 Activity —— {@link android.service.quicksettings.TileService} 本身
 * **没有**长按回调（javap 验证过），所以必须单独做一个。
 *
 * <h2>布局照抄小米互传的设置页（P110）</h2>
 *
 * 反编译证据（`com.miui.mishare.activity.MiShareSettingsActivity$a`）：
 *
 * <pre>
 * pref_key_enable_xiaomi_transfer  → CheckBoxPreference（总开关）
 * pref_key_switch_desc             → DescPreference（开关下方的说明）
 * </pre>
 *
 * 开关右侧的状态用 {@code DropDownPreference} 表达，三种取值：
 *
 * <pre>
 * O0.H1(["receive_same_account", "receive_all_device", "receive_close"])
 * //  0                        1                    2
 * </pre>
 *
 * 对应的文案（资源 id 反查自 4.12.0 的 resources.arsc）：
 *
 * <pre>
 * 0x7f1002f8 switch_mode_sameaccount     "仅同账号设备"
 * 0x7f1002f5 switch_mode_all             "所有人（%d分钟）"   ← 代码传 0xa = 10
 * 0x7f1002f7 switch_mode_close_os3       "关闭"
 * 0x7f1002ec sub_title_..._switch_none_os3 "可被附近设备发现"
 * </pre>
 *
 * ★注意 `%d` 是**硬编码 10**★ —— `const/16 v5, 0xa` 后
 * `String.format(...)`。也就是说小米互传的"10分钟"**不是用户可调的**，
 * 是写死的常量。我们照此实现。
 *
 * 小米互传没有"永久"这个选项（它只有 同账号/所有人(10分钟)/关闭）。
 * 但用户之前明确要求过"配置 10 分钟/永久"，所以我们在下拉里
 * 追加一项"始终开启"，**其余三项的措辞和顺序照小米互传**。
 */
public class TileSettingsActivity extends Activity {

    /** 下拉的三个（我们四个）选项。前三个顺序与小米互传一致。 */
    private static final int MODE_SAME_ACCOUNT = 0;   // 关闭（我们不做同账号，见下）
    private static final int MODE_ALL_10MIN    = 1;   // 所有人（10分钟）
    private static final int MODE_CLOSE        = 2;   // 关闭
    private static final int MODE_ALWAYS       = 3;   // 始终开启（我们追加）

    private Switch mainSwitch;
    private TextView descView;
    private TextView modeView;
    private LinearLayout optionRow;
    /** 附近设备列表容器（refreshNearby 里重建其子 View）。 */
    private LinearLayout nearbyList;
    /** 右侧"N 台在线"文案。 */
    private TextView nearbyCount;
    /**
     * 上次列表内容指纹 —— 相同就跳过重建，避免每秒闪烁。
     *
     * <p>★初值必须是 null 而不是 ""★：空列表算出来的指纹恰好也是空串，
     * 若初值为 ""，首次刷新会判定"没变化"直接 return，
     * 「暂无设备」的引导文案就永远渲染不出来（实测踩过）。
     */
    private String nearbySig = null;

    /** 定位未开启的警告横幅（定位正常时为 GONE）。 */
    private View locationWarn;
    /**
     * 上次的定位状态：-1=未知，0=关闭，1=开启。
     *
     * <p>★用 int 而不是 Boolean★：Boolean 字段初值为 null，
     * 与基本类型比较时会自动拆箱，首帧直接 NPE。
     * 那行 NPE 被刷新链路吞掉，表现为横幅一直不显示、日志也没有，
     * 排查很费劲（实测踩过）。哨兵值 -1 没这个问题。
     */
    private int lastLocOn = -1;

    /** 本页自己的 BLE 扫描器（页面可见时扫描附近设备）。 */
    private com.huchuan.core.GattClient nearbyScanner;
    /** 扫描是否已启动（避免重复 start）。 */
    private boolean nearbyScanning;


    /** 当前选中的模式（下拉里的值）。 */
    private int mode = MODE_CLOSE;
    /**
     * 正在**程序化**改动 Switch 时置 true。
     *
     * ★这个守卫是必需的★（P110）：{@code Switch.setChecked()} 会回调
     * {@code onCheckedChanged}，而我们的监听器里会把 mode 重置成
     * "10分钟"。于是从下拉选"始终开启"时：
     *   1. onClick 设 mode = ALWAYS(3)
     *   2. mainSwitch.setChecked(true) → 触发监听器 → mode 被改回 ALL_10MIN(1)
     *   3. applyState() 看到的是 mode=1 → 又设成定时 10 分钟
     * 实测日志：
     *   下拉选项被点击 mode=3
     *   applyState switchOn=true mode=1    ← 被监听器覆盖了
     */
    private boolean programmaticSwitch;
    /** 总开关本体（小米互传里 CheckBoxPreference 控制"接收状态"是否生效）。 */
    private boolean switchOn;

    private static final int REQ_PERM = 3001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());

        // ★权限申请搬到这里★（P111）
        //
        // 原来由 MainActivity 申请（它是桌面入口）。桌面入口去掉后，
        // 这个页面就是**唯一**的入口，必须自己兜住权限 ——
        // 否则一旦权限被系统重置（重装/长期不用），用户会看到
        // "打开了但搜不到/广播不出去"，却没有任何入口去授权。
        String[] miss = com.huchuan.core.Perms.missing(this);
        if (miss.length > 0) {
            android.util.Log.i("HuChuan.TileSettings", "缺少权限 " + miss.length + " 项，申请中");
            requestPermissions(miss, REQ_PERM);
        }

        // 从当前真实状态反推界面初始值
        boolean running = ReceiverState.isRunning();
        switchOn = running;
        if (!running) {
            mode = MODE_CLOSE;
        } else {
            mode = ReceiverState.isPermanent() ? MODE_ALWAYS : MODE_ALL_10MIN;
        }
        mainSwitch.setChecked(switchOn);
        refreshUi();
        logThemeColors();
    }

    /**
     * 打印实际取到的配色（诊断用）。
     *
     * <p>为什么留这条日志：深色模式是**框架按配置自动选资源**的行为，
     * 出问题时"看起来没变"可能是三种原因（没打包 / 选了浅色 / 被缓存），
     * 光看界面分不清。把实际取到的 ARGB 打出来，一眼能定位到哪一步断的。
     * 正常情况两行对比就能确认深浅色都工作。
     */
    private void logThemeColors() {
        int uiMode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        String night = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES ? "深色" : "浅色";
        android.util.Log.i("HuChuan.TileSettings",
                "主题=" + night
                + " page_bg=" + Integer.toHexString(c(R.color.page_bg))
                + " card_bg=" + Integer.toHexString(c(R.color.card_bg))
                + " text_primary=" + Integer.toHexString(c(R.color.text_primary)));
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(c(R.color.page_bg));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(0), dp(8), dp(0), dp(24));
        scroll.addView(root);

        // ── 标题栏 ──
        TextView bar = new TextView(this);
        bar.setText("互传");
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        bar.setTextColor(c(R.color.text_primary));
        bar.setTypeface(Typeface.DEFAULT_BOLD);
        bar.setPadding(dp(24), dp(20), dp(24), dp(12));
        root.addView(bar);

        // ── 定位未开启的警告横幅（P133）──
        //
        // 放在最上面：这是"用不了"级别的阻断问题，必须比开关更显眼。
        // 平时（定位开着）这条是 GONE，不占位。
        locationWarn = locationWarningView();
        root.addView(locationWarn);

        // ── 卡片：主开关 + 说明 + 模式 ──
        // 对应小米互传的 CheckBoxPreference + DescPreference + DropDownPreference
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(c(R.color.card_bg));
        cardBg.setCornerRadius(dp(16));
        card.setBackgroundDrawable(cardBg);
        card.setPadding(dp(20), dp(6), dp(20), dp(6));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.leftMargin = dp(16);
        cardLp.rightMargin = dp(16);
        card.setLayoutParams(cardLp);
        root.addView(card);

        // 第一行：左边"小米互传"文字，右边 Switch（小米互传 CheckBox 在右侧）
        card.addView(switchRow());

        // 第二行：说明文字（小米互传 DescPreference = "可被附近设备发现"）
        descView = new TextView(this);
        descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        descView.setTextColor(c(R.color.text_secondary));
        descView.setPadding(dp(2), 0, dp(2), dp(14));
        card.addView(descView);

        // 分隔线
        card.addView(divider());

        // 第三行：模式选择（小米互传 DropDownPreference，点击弹菜单）
        optionRow = (LinearLayout) optionRowView();
        card.addView(optionRow);

        // 分隔线
        card.addView(divider());

        // ── 附近设备卡片（扫描到的手机）──
        //
        // 数据来自 NearbyDevices（GattClient 扫描时顺手记的全局缓存），
        // 这里只**读**、不自己开扫描 —— 免得跟分享页抢扫描器。
        root.addView(nearbyCard());

        // ── 底部提示 ──
        TextView foot = new TextView(this);
        foot.setText("长按磁贴可重新打开此页。\n"
                + "开启后，附近的安卓设备可以找到本机并发送文件。");
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        foot.setTextColor(c(R.color.text_tertiary));
        foot.setPadding(dp(28), dp(18), dp(28), 0);
        foot.setLineSpacing(0, 1.35f);
        root.addView(foot);

        return scroll;
    }

    /** 主开关那一行。 */
    private View switchRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(4));

        TextView label = new TextView(this);
        label.setText("接收文件");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        label.setTextColor(c(R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(lp);
        row.addView(label);

        mainSwitch = new Switch(this);
        // 注意：这里不能用 lambda —— 编译器推断不出
        // CompoundButton.OnCheckedChangeListener（Switch 有多个同名重载来源），
        // 必须写显式匿名类。
        mainSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton v, boolean checked) {
                // 程序化改动（下拉里选的）：mode 已定好，别覆盖
                if (programmaticSwitch) return;
                switchOn = checked;
                mode = checked ? MODE_ALL_10MIN : MODE_CLOSE;
                applyState();
                refreshUi();
            }
        });
        row.addView(mainSwitch);
        return row;
    }

    /** 模式那一行：左边标题+当前值，右边箭头。点击弹出下拉。 */
    private View optionRowView() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, dp(16));
        row.setClickable(true);
        // row 不是 effectively final（下面还要 addView），所以用匿名类。
        final LinearLayout self = row;
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showModeMenu(self); }
        });

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(tlp);

        TextView t = new TextView(this);
        t.setText("接收范围");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(c(R.color.text_primary));
        texts.addView(t);

        modeView = new TextView(this);
        modeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        modeView.setTextColor(c(R.color.text_secondary));
        modeView.setPadding(0, dp(3), 0, 0);
        texts.addView(modeView);

        row.addView(texts);

        TextView arrow = new TextView(this);
        arrow.setText("▾");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        arrow.setTextColor(c(R.color.text_arrow));
        row.addView(arrow);

        return row;
    }

    /**
     * 定位未开启时的警告横幅（P133）。
     *
     * <p>★为什么需要它★：Android 的 BLE 扫描强制依赖系统定位服务。
     * 定位关着时，权限全都授予了、{@code startScan()} 也不报错，
     * 但**一个广播都收不到** —— 界面表现就是"一直在搜索但什么都没有"，
     * 用户完全无从判断。实测踩过：找了一圈以为是禁用了 Google 组件，
     * 真正原因是定位被关了。
     *
     * <p>做成醒目的橙色底 + 按钮直接跳系统设置，而不是一句灰色小字：
     * 这是"功能完全不可用"级别的阻断问题。
     */
    private View locationWarningView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFF3E0);          // 浅橙底（深色模式下也够醒目）
        bg.setCornerRadius(dp(14));
        box.setBackgroundDrawable(bg);
        box.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(12);
        box.setLayoutParams(lp);

        TextView msg = new TextView(this);
        msg.setText("定位服务未开启\n蓝牙搜索必须打开定位才能发现设备");
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        msg.setTextColor(0xFF8A4B00);     // 深橙字，浅底上对比够
        msg.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        msg.setLayoutParams(mlp);
        box.addView(msg);

        TextView go = new TextView(this);
        go.setText("去开启");
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        go.setTextColor(0xFF1A73E8);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setPadding(dp(12), dp(6), dp(4), dp(6));
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 直接跳到系统的"位置信息"总开关页。
                // 各 ROM 的 Settings 页类名不同，优先用系统标准的
                // ACTION_LOCATION_SOURCE_SETTINGS（几乎所有 ROM 都支持）。
                try {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Throwable t) {
                    android.util.Log.w("HuChuan.TileSettings", "跳转定位设置失败: " + t);
                }
            }
        });
        box.addView(go);
        return box;
    }

    /**
     * 「附近的手机」卡片 —— 显示 BLE 扫描到的设备。
     *
     * <p>只读 {@link com.huchuan.core.NearbyDevices} 的全局缓存，
     * **不自己开扫描**：设置页只是旁观，扫出来的设备是分享流程顺手记的。
     * 没有设备时显示一句引导文案，而不是空白卡片。
     */
    private View nearbyCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(R.color.card_bg));
        bg.setCornerRadius(dp(16));
        card.setBackgroundDrawable(bg);
        card.setPadding(dp(20), dp(16), dp(20), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.topMargin = dp(14);
        card.setLayoutParams(lp);

        // 卡片标题行：左边"附近的手机"，右边在线数量
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("附近的手机");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(c(R.color.text_primary));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tlp);
        head.addView(title);

        nearbyCount = new TextView(this);
        nearbyCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        nearbyCount.setTextColor(c(R.color.text_secondary));
        head.addView(nearbyCount);

        card.addView(head);

        // 列表容器（每次刷新 clear + 重建，简单可靠）
        nearbyList = new LinearLayout(this);
        nearbyList.setOrientation(LinearLayout.VERTICAL);
        nearbyList.setPadding(0, dp(10), 0, 0);
        card.addView(nearbyList);

        return card;
    }

    /**
     * 重画设备列表。
     *
     * <p>只在内容**变化**时才重建 View —— 每秒无条件重建会让正在看的用户
     * 觉得列表在闪，也白白消耗 measure/layout。
     */
    /**
     * 开始扫描附近设备（页面可见时调用）。
     *
     * <p>★为什么设置页要自己扫★：{@code GattServer}（接收）**只广播不扫描**，
     * 只有分享流程才会扫。所以接收开着的时候，列表永远是空的 ——
     * 那样这个卡片就没有意义。这里补一路扫描，页面不可见就停。
     *
     * <p>失败（蓝牙关 / 无权限）只记日志，不打扰用户：
     * 列表显示「暂无设备」即可，不必弹错。
     */
    private void startNearbyScan() {
        if (nearbyScanning) return;
        if (!com.huchuan.core.Perms.allGranted(this)) return;   // 没权限扫了也白扫
        try {
            if (nearbyScanner == null) {
                nearbyScanner = new com.huchuan.core.GattClient(this,
                        new com.huchuan.core.GattClient.Listener() {
                    @Override public void onFound(android.bluetooth.BluetoothDevice d,
                                                  int rssi, String name) {
                        // 设备已被 GattClient 记进 NearbyDevices，这里不用重复处理；
                        // 但自己记一笔，防止别的 GattClient 把缓存清掉。
                        NearbyDevices.onDiscovered(d, rssi, name, null);
                    }
                    @Override public void onDeviceInfo(android.bluetooth.BluetoothDevice d,
                                                       com.huchuan.model.DeviceInfo info) { }
                    @Override public void onState(String msg) { }
                });
            }
            nearbyScanner.startScan();
            nearbyScanning = true;
            android.util.Log.i("HuChuan.TileSettings", "附近设备扫描已启动");
        } catch (Throwable t) {
            nearbyScanning = false;
            android.util.Log.w("HuChuan.TileSettings", "启动附近扫描失败: " + t);
        }
    }

    /** 停止扫描（页面不可见时调用，省电且不占扫描器）。 */
    private void stopNearbyScan() {
        if (!nearbyScanning) return;
        nearbyScanning = false;
        try { if (nearbyScanner != null) nearbyScanner.stopScan(); } catch (Throwable ignored) { }
        android.util.Log.i("HuChuan.TileSettings", "附近设备扫描已停止");
    }

    private void refreshNearby() {
        if (nearbyList == null) return;

        // ★先处理定位开关（P133）★ —— 这是"能不能扫到设备"的前提，
        // 关着时列表永远是空的，必须在列表上方明确告诉用户原因。
        // 用 View.VISIBLE/GONE 而不是增删 View：避免每次刷新都重新 measure。
        if (locationWarn != null) {
            boolean locOn = com.huchuan.core.Perms.isLocationEnabled(this);
            locationWarn.setVisibility(locOn ? View.GONE : View.VISIBLE);
            int now = locOn ? 1 : 0;
            if (now != lastLocOn) {     // 仅状态变化时打日志，避免每秒刷屏
                lastLocOn = now;
                android.util.Log.i("HuChuan.TileSettings",
                        "定位服务=" + (locOn ? "开启" : "关闭") + " → 警告横幅"
                        + (locOn ? "隐藏" : "显示"));
            }
        }

        List<NearbyDevices.Device> list = NearbyDevices.snapshot();

        // 生成一个"内容指纹"，与上次相同就直接返回
        StringBuilder sig = new StringBuilder();
        for (NearbyDevices.Device d : list) {
            sig.append(d.address).append('|').append(d.name)
               .append('|').append(NearbyDevices.signalBars(d.rssi)).append(';');
        }
        String now = sig.toString();
        if (now.equals(nearbySig)) return;
        nearbySig = now;

        nearbyList.removeAllViews();

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无设备。\n在另一台手机打开「互传」并长按磁贴开启接收，即可在此看到。");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setTextColor(c(R.color.text_tertiary));
            empty.setLineSpacing(0, 1.3f);
            nearbyList.addView(empty);
            nearbyCount.setText("");
            return;
        }

        nearbyCount.setText(list.size() + " 台在线");

        for (int i = 0; i < list.size(); i++) {
            if (i > 0) nearbyList.addView(divider());
            nearbyList.addView(deviceRow(list.get(i)));
        }
    }

    /** 一行设备：名字 + MAC/信号 + 右侧信号格。 */
    private View deviceRow(NearbyDevices.Device dev) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));

        // 左侧竖排：名字 / 地址
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(tlp);

        TextView name = new TextView(this);
        String shown = (dev.name != null && !dev.name.isEmpty())
                ? dev.name : "未识别的设备";
        name.setText(shown);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTextColor(c(R.color.text_primary));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);

        TextView sub = new TextView(this);
        sub.setText(dev.address + "  ·  " + dev.rssi + " dBm");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(c(R.color.text_tertiary));
        sub.setPadding(0, dp(3), 0, 0);
        texts.addView(sub);

        row.addView(texts);

        TextView bars = new TextView(this);
        bars.setText(signalBarsText(NearbyDevices.signalBars(dev.rssi)));
        bars.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bars.setTextColor(c(R.color.signal_ok));
        row.addView(bars);

        return row;
    }

    /** 信号格文字（用方块字符，避免引入图标资源）。 */
    private static String signalBarsText(int bars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) sb.append(i <= bars ? '\u25A0' : '\u25A1');
        return sb.toString();
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(c(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f) > 0 ? dp(0.7f) : 1));
        lp.height = Math.max(1, (int) (0.7f * getResources().getDisplayMetrics().density + 0.5f));
        v.setLayoutParams(lp);
        return v;
    }

    // ------------------------------------------------------------------
    // 三种状态（照小米互传 DropDownPreference）
    // ------------------------------------------------------------------

    /**
     * 当前模式的文案。
     *
     * 对照小米互传 4.12.0：
     * <ul>
     *   <li>所有人：{@code switch_mode_all} = "所有人（%d分钟）"}，%d 传 10</li>
     *   <li>关闭：  {@code switch_mode_close_os3} = "关闭"</li>
     * </ul>
     * 我们额外加了"始终开启"（小米互传没有此项，是用户要求的）。
     */
    private String modeLabel(int m) {
        switch (m) {
            case MODE_ALL_10MIN: return "所有人（10分钟）";
            case MODE_ALWAYS:    return "始终开启";
            case MODE_SAME_ACCOUNT: return "仅同账号设备";
            default:             return "关闭";
        }
    }

    /** 下拉里可选的项（顺序照小米互传：所有人 → 关闭；"始终开启"追加在最后）。 */
    private int[] modeOrder() {
        return new int[] { MODE_ALL_10MIN, MODE_ALWAYS, MODE_CLOSE };
    }

    private void showModeMenu(final View anchor) {
        final LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(R.color.card_bg));
        bg.setCornerRadius(dp(14));
        menu.setBackgroundDrawable(bg);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));

        final PopupWindow pw = new PopupWindow(menu,
                dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        pw.setElevation(dp(8));
        pw.setOutsideTouchable(true);

        for (final int m : modeOrder()) {
            TextView item = new TextView(this);
            item.setText(modeLabel(m));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setTextColor(m == mode ? c(R.color.accent) : c(R.color.text_primary));
            item.setPadding(dp(16), dp(14), dp(16), dp(14));
            if (m == mode) item.setTypeface(Typeface.DEFAULT_BOLD);
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    mode = m;
                    switchOn = (m != MODE_CLOSE);
                    // ★置守卫再改 Switch★，否则监听器会把 mode 覆盖回去（见字段注释）
                    programmaticSwitch = true;
                    mainSwitch.setChecked(switchOn);
                    programmaticSwitch = false;
                    applyState();
                    refreshUi();
                    pw.dismiss();
                }
            });
            menu.addView(item);
        }
        pw.showAsDropDown(anchor, -dp(40), dp(4));
    }

    /** 把当前 mode/switchOn 落到真实状态上。 */
    private void applyState() {
        int effective = switchOn ? mode : MODE_CLOSE;
        switch (effective) {
            case MODE_ALL_10MIN:
                ReceiverTile.enableTimed(this);
                break;
            case MODE_ALWAYS:
                ReceiverTile.enablePermanent(this);
                break;
            default:
                ReceiverState.disable(this);
                stopService(new android.content.Intent(this, ReceiverService.class));
                break;
        }
        ReceiverTile.requestRefresh(this);
    }

    /** 刷新说明文字和模式行。 */
    private void refreshUi() {
        boolean on = mainSwitch.isChecked();
        descView.setText(on ? "可被附近设备发现" : "已关闭，附近设备找不到本机");

        if (!on) {
            modeView.setText("关闭");
            return;
        }
        if (mode == MODE_ALWAYS) {
            modeView.setText("始终开启");
            return;
        }
        // ★带倒计时★ —— 小米互传的开关下方会显示"互传%d分钟后自动关闭"，
        // 我们把剩余时间实时显示出来，比只写"10分钟"更有用。
        long left = ReceiverState.remainingMs();
        if (left > 0) {
            long min = left / 60000, sec = (left % 60000) / 1000;
            modeView.setText("所有人（10分钟）· 剩余 "
                    + (min > 0 ? min + " 分 " : "") + sec + " 秒");
        } else {
            modeView.setText(modeLabel(mode));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private final android.os.Handler ticker = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshUi();
            // 附近设备列表也每秒跟一次（内部有内容指纹，
            // 没变化时不会重建 View，不会闪）。
            refreshNearby();
            ticker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        // 页面可见 → 开始扫附近设备（不可见时停，省电）
        startNearbyScan();
        refreshNearby();
        ticker.postDelayed(tick, 1000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopNearbyScan();
        ticker.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ticker.removeCallbacks(tick);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        int ok = 0;
        for (int r : results) {
            if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) ok++;
        }
        android.util.Log.i("HuChuan.TileSettings", "权限 " + ok + "/" + perms.length + " 已授予");
        refreshUi();
    }

    /**
     * 取颜色资源。
     *
     * <p>★为什么不直接用 {@code getResources().getColor()}★：
     * 那个方法在 API 23 起被废弃，且**不理会主题**；API 23+ 要用
     * {@code getColor(id, theme)}。这里统一走 ContextCompat 风格的兼容写法，
     * 保证深色模式下取到 values-night 里的那套值。
     *
     * <p>做成一行辅助方法而不是到处写三元判断：调用点太多（20+ 处），
     * 每处都写兼容逻辑既啰嗦又容易漏。
     */
    @SuppressWarnings("deprecation")
    private int c(int colorRes) {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            return getResources().getColor(colorRes, getTheme());
        }
        return getResources().getColor(colorRes);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
