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

import com.huchuan.core.ReceiverState;

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
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF7F8FA);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(0), dp(8), dp(0), dp(24));
        scroll.addView(root);

        // ── 标题栏 ──
        TextView bar = new TextView(this);
        bar.setText("小米互传");
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        bar.setTextColor(0xFF1A1A1A);
        bar.setTypeface(Typeface.DEFAULT_BOLD);
        bar.setPadding(dp(24), dp(20), dp(24), dp(12));
        root.addView(bar);

        // ── 卡片：主开关 + 说明 + 模式 ──
        // 对应小米互传的 CheckBoxPreference + DescPreference + DropDownPreference
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
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
        descView.setTextColor(0xFF8A8A8E);
        descView.setPadding(dp(2), 0, dp(2), dp(14));
        card.addView(descView);

        // 分隔线
        card.addView(divider());

        // 第三行：模式选择（小米互传 DropDownPreference，点击弹菜单）
        optionRow = (LinearLayout) optionRowView();
        card.addView(optionRow);

        // ── 底部提示 ──
        TextView foot = new TextView(this);
        foot.setText("长按磁贴可重新打开此页。\n"
                + "开启后，附近的小米/安卓设备可以找到本机并发送文件。");
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        foot.setTextColor(0xFF9AA0A6);
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
        label.setTextColor(0xFF1A1A1A);
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
        t.setTextColor(0xFF1A1A1A);
        texts.addView(t);

        modeView = new TextView(this);
        modeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        modeView.setTextColor(0xFF8A8A8E);
        modeView.setPadding(0, dp(3), 0, 0);
        texts.addView(modeView);

        row.addView(texts);

        TextView arrow = new TextView(this);
        arrow.setText("▾");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        arrow.setTextColor(0xFFB0B0B5);
        row.addView(arrow);

        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFEDEEF0);
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
        bg.setColor(Color.WHITE);
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
            item.setTextColor(m == mode ? 0xFF1A73E8 : 0xFF1A1A1A);
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
            ticker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        ticker.postDelayed(tick, 1000);
    }

    @Override
    protected void onStop() {
        super.onStop();
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

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
