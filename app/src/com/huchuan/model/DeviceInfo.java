package com.huchuan.model;

import org.json.JSONException;
import org.json.JSONObject;

/** 接收方通过 BLE CHAR_STATUS 暴露的设备信息。 */
public final class DeviceInfo {
    public int state;
    public String key;
    public String mac;
    public Integer catShare;

    public DeviceInfo(int state, String key, String mac, Integer catShare) {
        this.state = state; this.key = key; this.mac = mac; this.catShare = catShare;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("state", state);
        o.put("key", key == null ? JSONObject.NULL : key);
        o.put("mac", mac);
        if (catShare != null) o.put("catShare", (int) catShare);
        return o;
    }

    public static DeviceInfo fromJson(String text) throws JSONException {
        JSONObject o = new JSONObject(text);
        return new DeviceInfo(
                o.optInt("state", 0),
                o.isNull("key") ? null : o.optString("key", null),
                o.optString("mac", "02:00:00:00:00:00"),
                o.has("catShare") ? o.optInt("catShare") : null);
    }
}
