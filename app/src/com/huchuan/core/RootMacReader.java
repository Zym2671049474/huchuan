package com.huchuan.core;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读网络接口 MAC —— 逐条照搬 CatShare 的 {@code RootMacReader}。
 *
 * <p>为什么不直接用 {@code NetworkInterface.getHardwareAddress()}：
 * Android 10+ 的 {@code untrusted_app} 拿不到 p2p0 的硬件地址
 * （SELinux 拒绝 sysfs_net，ioctl 也返回 null）。
 * CatShare 是靠 <b>root</b> 跑命令拿到的，本机有 root，所以照做。
 *
 * <p>CatShare 的原始回退顺序（反编译自 classes3.dex）：
 * <ol>
 *   <li>无 root → {@code readViaSysfsPlain}（直接读 /sys/class/net/X/address）</li>
 *   <li>有 root → {@code su -c "ip addr show X"}  取 "link/ether "</li>
 *   <li>　　　　{@code su -c "cat /sys/class/net/X/address"}</li>
 *   <li>　　　　{@code su -c "ip link show X"}     取 "link/ether "</li>
 *   <li>　　　　{@code su -c "ifconfig X"}         取 "HWaddr "</li>
 * </ol>
 * 每一级都用 {@link #isValidMac} 校验，无效就继续下一级。
 */
public final class RootMacReader {

    private static final String TAG = "HuChuan.Mac";

    /** su 的候选路径，顺序与 CatShare 一致。 */
    private static final String[] SU_CANDIDATES = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
    };

    private static final Pattern MAC_RE =
            Pattern.compile("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}");

    private static volatile String suPath = null;
    private static volatile boolean rootProbed = false;
    private static volatile boolean rootAvailable = false;

    private RootMacReader() {}

    /** 最近一次失败原因（排查用）。 */
    private static volatile String lastError = null;
    public static String getLastError() { return lastError; }

    // ---------------- 对外 ----------------

    /** 读某个接口的 MAC，读不到返回 null。 */
    public static String getMacAddressByName(String name) {
        if (name == null || name.isEmpty()) {
            lastError = "接口名为空";
            return null;
        }

        if (!isRootAvailable()) {
            // 无 root：直接读 sysfs 试试（部分设备/ROM 允许）
            String v = readViaSysfsPlain(name);
            if (isValidMac(v)) {
                lastError = null;
                return v.toLowerCase(Locale.ROOT);
            }
            return null;
        }

        String su = suPath != null ? suPath : "su";

        // 1) ip addr show
        String v = parseFromCommand(su + " -c \"ip addr show " + name + "\"", "link/ether ");
        if (isValidMac(v)) { lastError = null; return v.toLowerCase(Locale.ROOT); }

        // 2) cat /sys/class/net/X/address
        v = parseFromCommand(su + " -c \"cat /sys/class/net/" + name + "/address\"", null);
        if (isValidMac(v)) { lastError = null; return v.toLowerCase(Locale.ROOT); }

        // 3) ip link show
        v = parseFromCommand(su + " -c \"ip link show " + name + "\"", "link/ether ");
        if (isValidMac(v)) { lastError = null; return v.toLowerCase(Locale.ROOT); }

        // 4) ifconfig（老工具）
        v = parseFromCommand(su + " -c \"ifconfig " + name + "\"", "HWaddr ");
        if (isValidMac(v)) { lastError = null; return v.toLowerCase(Locale.ROOT); }

        lastError = "root 已获取，但读不到 " + name + " 的 MAC";
        Log.w(TAG, lastError);
        return null;
    }

    /** p2p0 的 MAC（互传协议用这个）。 */
    public static String getP2pMacAddress() {
        return getMacAddressByName("p2p0");
    }

    /** 有没有可用的 root。结果会缓存。 */
    public static boolean isRootAvailable() {
        if (!rootProbed) probeRoot();
        return rootAvailable;
    }

    /** 清掉 root 探测缓存（root 状态可能变化）。 */
    public static void resetRootCache() {
        rootProbed = false;
        rootAvailable = false;
        suPath = null;
    }

    // ---------------- 内部 ----------------

    private static void probeRoot() {
        synchronized (RootMacReader.class) {
            if (rootProbed) return;
            for (String c : SU_CANDIDATES) {
                String out = execCapture(c + " -c id", 15000);
                if (out != null && out.contains("uid=0")) {
                    suPath = c;
                    rootAvailable = true;
                    rootProbed = true;
                    Log.i(TAG, "root 可用，su = " + c);
                    return;
                }
            }
            rootAvailable = false;
            rootProbed = true;
            Log.i(TAG, "没有 root，将退回 sysfs 直读");
        }
    }

    /** 跑 root 命令，按前缀取 MAC；prefix 为 null 时直接在输出里找 MAC。 */
    private static String parseFromCommand(String command, String prefix) {
        String out = execCapture(command, 15000);
        if (out == null) return null;
        if (prefix != null) {
            int i = out.indexOf(prefix);
            if (i < 0) return null;
            return extractMacAt(out, i + prefix.length());
        }
        Matcher m = MAC_RE.matcher(out);
        return m.find() ? m.group() : null;
    }

    private static String extractMacAt(String s, int from) {
        int end = Math.min(s.length(), from + 17);
        if (end <= from) return null;
        return s.substring(from, end).trim();
    }

    /** 无 root 时直接读 sysfs。 */
    private static String readViaSysfsPlain(String name) {
        try {
            BufferedReader r = new BufferedReader(
                    new java.io.FileReader("/sys/class/net/" + name + "/address"));
            try {
                String s = r.readLine();
                return s == null ? null : s.trim();
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "sysfs 直读 " + name + " 失败: " + t);
            return null;
        }
    }

    /** MAC 合法性：必须是 xx:xx:xx:xx:xx:xx 且不是全零。 */
    public static boolean isValidMac(String mac) {
        if (mac == null) return false;
        String m = mac.trim();
        if (m.length() != 17) return false;
        if (!MAC_RE.matcher(m).matches()) return false;
        return !"00:00:00:00:00:00".equalsIgnoreCase(m);
    }

    /** 执行命令并抓取全部输出；失败返回 null。 */
    private static String execCapture(String command, long timeoutMs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            final Process proc = p;

            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    InputStream in = proc.getInputStream();
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(in));
                        String line;
                        while ((line = r.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();

            // stderr 也要读，否则 su 输出多了会把管道堵死
            final StringBuilder err = new StringBuilder();
            Thread errReader = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(proc.getErrorStream()));
                        String line;
                        while ((line = r.readLine()) != null) err.append(line).append('\n');
                    } catch (Throwable ignored) {
                    }
                }
            });
            errReader.setDaemon(true);
            errReader.start();

            boolean done = waitFor(p, timeoutMs);
            if (!done) {
                try { p.destroy(); } catch (Throwable ignored) { }
                return null;
            }
            reader.join(1000);
            if (p.exitValue() != 0) return null;
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "执行失败: " + command + " → " + t);
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
            return null;
        }
    }

    /** Java 8 兼容的等待（没有 Process.waitFor(timeout)）。 */
    private static boolean waitFor(final Process p, long timeoutMs) {
        final boolean[] exited = {false};
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { p.waitFor(); exited[0] = true; } catch (Throwable ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
        try { t.join(timeoutMs); } catch (InterruptedException ignored) { }
        return exited[0];
    }
}
