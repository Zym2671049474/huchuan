package com.huchuan.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 通过 BLE CHAR_P2P 交换的连接信息。
 *
 * 字段来源：
 *   - id/ssid/psk/mac/port/key/catShare —— CatShare 协议
 *   - freq/G/M/P/transfer_mode —— **小米原生互传**额外发送（真机抓到的，见 P022）
 *
 * 注意：mac 是协议死字段（接收端从不读取），仅为协议完整性保留。
 */
public final class P2pInfo {
    public String id;
    public String ssid;
    public String psk;
    public String mac;
    public int port;
    public String key;
    public Integer catShare;

    // ---- 小米原生互传附加字段（可能不存在）----
    /** 频段 MHz：2412=2.4G ch1，5745=5G ch149 */
    public int freq = 0;
    /** 疑似 GO 的 capability 位（小米发来通常是 1）*/
    public int g = 0;
    /** 疑似 mode（小米发来通常是 1）*/
    public int m = 0;
    /** 疑似 preference（小米发来通常是 2）*/
    public int p = 0;
    /** 传输模式：0 = 普通文件 */
    public int transferMode = 0;

    public P2pInfo(String id, String ssid, String psk, String mac, int port, String key, Integer catShare) {
        this.id = id; this.ssid = ssid; this.psk = psk;
        this.mac = mac; this.port = port; this.key = key; this.catShare = catShare;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("ssid", ssid);
        o.put("psk", psk);
        o.put("mac", mac);
        o.put("port", port);
        o.put("key", key == null ? JSONObject.NULL : key);
        if (catShare != null) o.put("catShare", (int) catShare);
        // 小米原生互传会带这些；带上以提高兼容性
        if (freq > 0) o.put("freq", freq);
        o.put("transfer_mode", transferMode);
        return o;
    }

    public static P2pInfo fromJson(String text) throws JSONException {
        JSONObject o = new JSONObject(text);
        P2pInfo i = new P2pInfo(
                o.optString("id", null),
                o.getString("ssid"),
                o.getString("psk"),
                o.optString("mac", "02:00:00:00:00:00"),
                o.getInt("port"),
                o.isNull("key") ? null : o.optString("key", null),
                o.has("catShare") ? o.optInt("catShare") : null);
        // 小米原生互传的附加字段（缺失则保持默认值）
        i.freq = o.optInt("freq", 0);
        i.g = o.optInt("G", 0);
        i.m = o.optInt("M", 0);
        i.p = o.optInt("P", 0);
        i.transferMode = o.optInt("transfer_mode", 0);
        return i;
    }

    /** 日志用：把关键字段拼成一行。 */
    public String describe() {
        StringBuilder b = new StringBuilder();
        b.append("ssid=").append(ssid).append(" port=").append(port);
        if (freq > 0) b.append(" freq=").append(freq).append("MHz");
        if (g != 0 || m != 0 || p != 0) b.append(" G/M/P=").append(g).append("/").append(m).append("/").append(p);
        if (transferMode != 0) b.append(" mode=").append(transferMode);
        if (catShare != null) b.append(" catShare=").append(catShare);
        return b.toString();
    }

    @Override public String toString() { return "P2pInfo{" + describe() + "}"; }
}
