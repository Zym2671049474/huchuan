// 验证 WS 文本协议帧格式与 CatShare 一致
import com.huchuan.model.WsMessage;
import org.json.JSONObject;

public class WsProtoTest {
  static int pass=0, fail=0;
  static void check(String name, boolean ok, String detail) {
    System.out.println((ok?"  ✅ ":"  ❌ ")+name+(detail.isEmpty()?"":"  "+detail));
    if(ok) pass++; else fail++;
  }
  public static void main(String[] a) throws Exception {
    System.out.println("=== WS 协议帧格式 ===");
    // 1. versionNegotiation
    JSONObject vn = new JSONObject().put("version",1).put("versions",new org.json.JSONArray().put(1));
    String t1 = new WsMessage("action",0,"versionNegotiation",vn).toText();
    check("versionNegotiation 格式", t1.startsWith("action:0:versionNegotiation?"), t1);
    check("可被解析回", WsMessage.fromText(t1) != null, "");

    // 2. sendRequest
    JSONObject sr = new JSONObject().put("taskId","123").put("senderName","Pixel")
        .put("fileName","a.txt").put("totalSize",100).put("fileCount",1);
    String t2 = new WsMessage("action",1,"sendRequest",sr).toText();
    check("sendRequest 格式", t2.startsWith("action:1:sendRequest?"), "");
    WsMessage p2 = WsMessage.fromText(t2);
    check("sendRequest 字段完整", p2!=null && "123".equals(p2.payload.optString("taskId"))
        && p2.payload.optLong("totalSize")==100, "");

    // 3. status
    String t3 = WsMessage.makeStatus(0,"123",1,"ok").toText();
    check("status 格式", t3.startsWith("action:0:status?"), t3);
    WsMessage p3 = WsMessage.fromText(t3);
    check("status type=1", p3!=null && p3.payload.optInt("type")==1, "");

    // 4. ack
    String t4 = new WsMessage("ack",0,"versionNegotiation",null).toText();
    check("ack 无 payload", "ack:0:versionNegotiation".equals(t4), t4);

    // 5. 解析官方样例
    WsMessage m = WsMessage.fromText("action:12:status?{\"taskId\":\"abc\",\"type\":1,\"reason\":\"ok\"}");
    check("解析带 JSON 的帧", m!=null && m.id==12 && "abc".equals(m.payload.optString("taskId")), "");
    check("拒绝非法帧", WsMessage.fromText("garbage")==null, "");

    // 6. 加密往返
    System.out.println("=== 加密 ===");
    com.huchuan.core.BleSecurity.SessionCipher c =
        com.huchuan.core.BleSecurity.deriveSessionKey(com.huchuan.core.BleSecurity.getEncodedPublicKey());
    String enc = c.encrypt("DIRECT-abc12345");
    check("加密后可解密", "DIRECT-abc12345".equals(c.decrypt(enc)), enc.substring(0,20)+"…");

    System.out.println("\n通过 "+pass+" / 失败 "+fail);
    System.exit(fail==0?0:1);
  }
}
