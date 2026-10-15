package com.huchuan.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 发送方通过 BLE CHAR_P2P 写入的连接信息。
 * 注意：mac 字段是协议死字段（接收端从不读取），此处仅为协议完整性保留。
 */
public final class P2pInfo {
    public String id;
    public String ssid;
    public String psk;
    public String mac;
    public int port;
    public String key;
    public Integer catShare;

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
        return o;
    }

    public static P2pInfo fromJson(String text) throws JSONException {
        JSONObject o = new JSONObject(text);
        return new P2pInfo(
                o.optString("id", null),
                o.getString("ssid"),
                o.getString("psk"),
                o.optString("mac", "02:00:00:00:00:00"),
                o.getInt("port"),
                o.isNull("key") ? null : o.optString("key", null),
                o.has("catShare") ? o.optInt("catShare") : null);
    }
}
