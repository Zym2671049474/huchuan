package com.huchuan.model;

import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket 文本帧：type:id:name?payload
 * 例：action:12:status?{"taskId":"abc","type":1}
 */
public final class WsMessage {
    private static final Pattern REQ = Pattern.compile("^(\\w+):(\\d+):(\\w+)(\\?(.*))?$");

    public final String type;
    public final int id;
    public final String name;
    public final JSONObject payload;

    public WsMessage(String type, int id, String name, JSONObject payload) {
        this.type = type; this.id = id; this.name = name; this.payload = payload;
    }

    public String toText() { return toText(-1); }

    public String toText(int newId) {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append(':').append(newId >= 0 ? newId : id).append(':').append(name);
        if (payload != null) sb.append('?').append(payload.toString());
        return sb.toString();
    }

    public static WsMessage fromText(String text) {
        Matcher m = REQ.matcher(text);
        if (!m.matches()) return null;
        try {
            String json = m.group(5);
            JSONObject o = (json != null && !json.isEmpty()) ? new JSONObject(json) : null;
            return new WsMessage(m.group(1), Integer.parseInt(m.group(2)), m.group(3), o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 构造 status 消息。type: 1=成功 3=用户拒绝 */
    public static WsMessage makeStatus(int id, String taskId, int type, String reason) {
        JSONObject o = new JSONObject();
        try {
            o.put("taskId", taskId);
            o.put("type", type);
            o.put("reason", reason);
            o.put("id", taskId);
        } catch (Exception ignored) {}
        return new WsMessage("action", id, "status", o);
    }
}
