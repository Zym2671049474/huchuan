package com.huchuan.net;

import android.util.Log;

import com.huchuan.core.BleUtils;
import com.huchuan.model.WsMessage;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M4 文件传输。
 *
 * 协议（逆向自 CatShare）：
 *   1. 发送方起 HTTP 服务，提供 GET /download?taskId=xxx（流式返回文件）
 *   2. 双方连上同一个 WebSocket
 *   3. 发送方发 action:0:versionNegotiation?{"version":1,"versions":[1]}
 *   4. 接收方回 ack
 *   5. 发送方发 action:1:sendRequest?{taskId,senderName,fileName,totalSize,fileCount,...}
 *   6. 接收方回 action:?:status?{"taskId":..,"type":1,"reason":"ok"}   (1=接受 3=拒绝)
 *   7. 接收方 HTTP GET /download 拉文件
 */
public final class Transfer {

    private static final String TAG = "HuChuan.Transfer";
    public static final String ACTION_VERSION_NEGOTIATION = "versionNegotiation";
    public static final String ACTION_SEND_REQUEST = "sendRequest";

    /** WS 端点路径 —— 必须与 CatShare / 小米互传一致（踩坑 P025）。 */
    public static final String WS_PATH = "/websocket";
    /** HTTP 下载端点。 */
    public static final String DOWNLOAD_PATH = "/download";

    public interface Progress {
        void onProgress(long sent, long total);
        void onDone(boolean ok, String msg);
    }

    // ================= 发送方 =================

    /** 发送方：HTTP 文件服务 + WS 服务端。 */
    public static final class Sender {
        private final File file;
        private final String taskId;
        private final String senderName;
        private java.net.ServerSocket httpServer;
        private MiniWebSocket.Conn ws;
        private volatile boolean running = true;
        private final AtomicInteger statusType = new AtomicInteger(-1);
        private final AtomicReference<String> statusReason = new AtomicReference<>("");
        private final CountDownLatch statusLatch = new CountDownLatch(1);
        private final CountDownLatch ackLatch = new CountDownLatch(1);
        private final Listener listener;

        public interface Listener {
            void onState(String msg);
            void onResult(boolean ok, String msg);
        }

        public Sender(File file, String senderName, Listener listener) {
            this.file = file;
            this.senderName = senderName;
            this.listener = listener;
            this.taskId = String.valueOf(System.currentTimeMillis());
        }

        public String getTaskId() { return taskId; }
        public int getHttpPort() { return httpServer == null ? -1 : httpServer.getLocalPort(); }

        /** 启动 HTTP 文件服务，返回端口。ssl=true 时用 HTTPS（与 CatShare 一致）。 */
        public int startHttpServer(boolean ssl, javax.net.ssl.SSLContext sslCtx) throws IOException {
            if (ssl && sslCtx != null) {
                javax.net.ssl.SSLServerSocket sss = (javax.net.ssl.SSLServerSocket)
                        sslCtx.getServerSocketFactory().createServerSocket(0);
                sss.setReuseAddress(true);
                httpServer = sss;
            } else {
                httpServer = new java.net.ServerSocket(0);
            }
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    while (running && !httpServer.isClosed()) {
                        try {
                            final java.net.Socket s = httpServer.accept();
                            Thread h = new Thread(new Runnable() {
                                @Override public void run() { serveFile(s); }
                            });
                            h.setDaemon(true); h.start();
                        } catch (IOException e) {
                            if (running) Log.w(TAG, "accept 结束: " + e.getMessage());
                            return;
                        }
                    }
                }
            }, "http-file");
            t.setDaemon(true); t.start();
            Log.i(TAG, (ssl ? "HTTPS" : "HTTP") + " 文件服务已启动，端口 " + httpServer.getLocalPort());
            return httpServer.getLocalPort();
        }

        /** 兼容旧调用：明文 HTTP。 */
        public int startHttpServer() throws IOException { return startHttpServer(false, null); }

        /**
         * 极简 HTTP/1.1 响应：把文件打成 **ZIP 流**输出。
         *
         * 为什么是 ZIP：CatShare 的 /download 返回 application/zip，
         * 条目名形如 "0/文件名"；接收方用 ZipInputStream 解开（踩坑 P024）。
         * 直接吐裸文件对方解不开。
         */
        private void serveFile(java.net.Socket s) {
            OutputStream out = null;
            InputStream in = null;
            try {
                s.setTcpNoDelay(true);
                InputStream raw = s.getInputStream();
                StringBuilder sb = new StringBuilder();
                int c, last4 = 0;
                while ((c = raw.read()) >= 0) {
                    sb.append((char) c);
                    last4 = (last4 << 8) | c;
                    if ((last4 & 0xFFFFFFFFL) == 0x0D0A0D0AL) break;   // \r\n\r\n
                }
                String req = sb.toString();
                Log.i(TAG, "HTTP 请求: " + req.split("\r\n")[0]);

                out = new BufferedOutputStream(s.getOutputStream());
                // 用 chunked：ZIP 长度未知（要压缩后才知），且避免大文件全量缓冲
                String head = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/zip\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(head.getBytes("ASCII"));

                // 用 chunked 编码包一层
                ChunkedOutputStream cos = new ChunkedOutputStream(out);
                java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(cos);
                zo.setLevel(java.util.zip.Deflater.BEST_SPEED);

                in = new BufferedInputStream(new FileInputStream(file));
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("0/" + file.getName());
                entry.setTime(file.lastModified());
                zo.putNextEntry(entry);

                byte[] buf = new byte[64 * 1024];
                long sent = 0;
                final long total = file.length();
                int n;
                while ((n = in.read(buf)) > 0) {
                    zo.write(buf, 0, n);
                    sent += n;
                    if (listener != null) {
                        listener.onState("发送中 " + (sent * 100 / Math.max(1, total)) + "%");
                    }
                }
                zo.closeEntry();
                zo.finish();
                cos.finish();
                out.flush();
                Log.i(TAG, "ZIP 已发送（原始 " + sent + " 字节）");
            } catch (Throwable t) {
                Log.w(TAG, "发送文件出错: " + t);
            } finally {
                closeQuietly(in); closeQuietly(out);
                try { s.close(); } catch (IOException ignored) {}
            }
        }

        /** HTTP/1.1 chunked 传输编码包装。 */
        private static final class ChunkedOutputStream extends OutputStream {
            private final OutputStream o;
            private final byte[] hdr = new byte[16];
            ChunkedOutputStream(OutputStream o) { this.o = o; }
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (len <= 0) return;
                String h = Integer.toHexString(len) + "\r\n";
                o.write(h.getBytes("ASCII"));
                o.write(b, off, len);
                o.write('\r'); o.write('\n');
            }
            @Override public void flush() throws IOException { o.flush(); }
            void finish() throws IOException { o.write("0\r\n\r\n".getBytes("ASCII")); o.flush(); }
        }

        /**
         * 启动 WS 服务端（发送方是 WS server，与 CatShare 一致）。
         * 路径必须是 /websocket，传输必须是 TLS（wss）—— 踩坑 P023/P025。
         */
        public void startWsServer(int port, javax.net.ssl.SSLContext sslCtx) throws IOException {
            MiniWebSocket.startServer(port, WS_PATH, new MiniWebSocket.Handler() {
                @Override public void onOpen(MiniWebSocket.Conn c) {
                    Log.i(TAG, "WS 客户端已连接");
                    ws = c;
                    listener.onState("对端已连接");
                    // 立刻发起版本协商
                    try {
                        JSONObject p = new JSONObject();
                        p.put("version", 1);
                        p.put("versions", new org.json.JSONArray().put(1));
                        c.sendText(new WsMessage("action", 0, ACTION_VERSION_NEGOTIATION, p).toText());
                        Log.i(TAG, "→ versionNegotiation");
                    } catch (Throwable t) {
                        Log.e(TAG, "发送 versionNegotiation 失败", t);
                    }
                }
                @Override public void onMessage(MiniWebSocket.Conn c, String text) {
                    handleMessage(c, text);
                }
                @Override public void onBinary(MiniWebSocket.Conn c, byte[] d) {}
                @Override public void onClose(MiniWebSocket.Conn c) {
                    Log.i(TAG, "WS 已关闭");
                    listener.onState("对端断开");
                }
                @Override public void onError(MiniWebSocket.Conn c, Throwable t) {
                    Log.e(TAG, "WS 错误", t);
                }
            }, sslCtx);
            Log.i(TAG, "WS 服务端已启动，端口 " + port + " 路径 " + WS_PATH
                    + (sslCtx != null ? " (wss)" : " (ws)"));
        }

        private void handleMessage(MiniWebSocket.Conn c, String text) {
            Log.i(TAG, "← " + text);
            WsMessage m = WsMessage.fromText(text);
            if (m == null) return;

            if ("action".equals(m.type)) {
                if ("status".equals(m.name) && m.payload != null) {
                    statusType.set(m.payload.optInt("type", -1));
                    statusReason.set(m.payload.optString("reason", ""));
                    statusLatch.countDown();
                }
                // 回 ack
                try {
                    c.sendText(new WsMessage("ack", m.id, m.name, null).toText());
                } catch (IOException e) {
                    Log.w(TAG, "回 ack 失败", e);
                }
            } else if ("ack".equals(m.type)) {
                if (ACTION_VERSION_NEGOTIATION.equalsIgnoreCase(m.name)) {
                    Log.i(TAG, "版本协商完成");
                    ackLatch.countDown();
                }
            }
        }

        /** 协商完成后，发 sendRequest 并等待对方接受。 */
        public boolean requestSend(int fileCount, long totalSize, String mimeType, int timeoutSec)
                throws Exception {
            if (!ackLatch.await(timeoutSec, TimeUnit.SECONDS)) {
                listener.onResult(false, "版本协商超时");
                return false;
            }
            JSONObject task = new JSONObject();
            task.put("taskId", taskId);
            task.put("id", taskId);
            task.put("senderId", BleUtils.getSenderId());
            task.put("senderName", senderName);
            task.put("fileName", file.getName());
            task.put("mimeType", mimeType);
            task.put("fileCount", fileCount);
            task.put("totalSize", totalSize);

            ws.sendText(new WsMessage("action", 1, ACTION_SEND_REQUEST, task).toText());
            Log.i(TAG, "→ sendRequest " + task);

            if (!statusLatch.await(timeoutSec, TimeUnit.SECONDS)) {
                listener.onResult(false, "等待对方确认超时");
                return false;
            }
            int type = statusType.get();
            boolean ok = type == 1;
            listener.onResult(ok, ok ? "对方已接受" : ("对方拒绝: " + statusReason.get()));
            return ok;
        }

        public void close() {
            running = false;
            try { if (ws != null) ws.close(); } catch (Throwable ignored) {}
            try { if (httpServer != null) httpServer.close(); } catch (Throwable ignored) {}
        }
    }

    // ================= 接收方 =================

    /** 接收方：WS 客户端 + HTTP 下载。 */
    public static final class Receiver {
        private final File outDir;
        private final String localName;
        private final Listener listener;
        private MiniWebSocket.Conn ws;
        private final AtomicReference<JSONObject> sendRequest = new AtomicReference<>();
        private final CountDownLatch requestLatch = new CountDownLatch(1);

        public interface Listener {
            void onState(String msg);
            void onResult(boolean ok, String msg);
        }

        public Receiver(File outDir, String localName, Listener listener) {
            this.outDir = outDir;
            this.localName = localName;
            this.listener = listener;
        }

        /** 连接发送方的 WS（wss://，路径 /websocket）。 */
        public void connect(String host, int port, int timeoutSec,
                            javax.net.ssl.SSLContext sslCtx) throws IOException {
            ws = MiniWebSocket.connect(host, port, WS_PATH, timeoutSec * 1000,
                    new MiniWebSocket.Handler() {
                @Override public void onOpen(MiniWebSocket.Conn c) {
                    Log.i(TAG, "WS 已连接 " + host + ":" + port);
                    listener.onState("已连接发送方");
                }
                @Override public void onMessage(MiniWebSocket.Conn c, String text) {
                    handleMessage(c, text);
                }
                @Override public void onBinary(MiniWebSocket.Conn c, byte[] d) {}
                @Override public void onClose(MiniWebSocket.Conn c) {
                    listener.onState("连接关闭");
                }
                @Override public void onError(MiniWebSocket.Conn c, Throwable t) {
                    Log.e(TAG, "WS 错误", t);
                }
            }, sslCtx);
            Log.i(TAG, "WS 已连接 " + host + ":" + port + WS_PATH
                    + (sslCtx != null ? " (wss)" : " (ws)"));
        }

        private void handleMessage(MiniWebSocket.Conn c, String text) {
            Log.i(TAG, "← " + text);
            WsMessage m = WsMessage.fromText(text);
            if (m == null) return;

            if ("action".equals(m.type)) {
                if (ACTION_VERSION_NEGOTIATION.equalsIgnoreCase(m.name)) {
                    try {
                        c.sendText(new WsMessage("ack", m.id, m.name, null).toText());
                        Log.i(TAG, "→ ack versionNegotiation");
                    } catch (IOException e) { Log.w(TAG, "ack 失败", e); }
                } else if (ACTION_SEND_REQUEST.equals(m.name)) {
                    sendRequest.set(m.payload);
                    requestLatch.countDown();
                }
            }
        }

        /** 等待发送方的 sendRequest，返回其 payload。 */
        public JSONObject awaitSendRequest(int timeoutSec) throws InterruptedException {
            if (!requestLatch.await(timeoutSec, TimeUnit.SECONDS)) return null;
            return sendRequest.get();
        }

        /**
         * 回复接受/拒绝。
         * @param id 帧里的 id；CatShare 用 99（sendStatusIgnoreException）
         * @param type 1=接受 3=拒绝
         */
        public void respond(String taskId, int id, int type, String reason) throws IOException {
            if (ws == null) return;
            ws.sendText(WsMessage.makeStatus(id, taskId, type, reason).toText());
            Log.i(TAG, "→ status id=" + id + " type=" + type + " reason=" + reason);
        }

        /** 兼容旧签名：id=0。 */
        public void respond(String taskId, int type, String reason) throws IOException {
            respond(taskId, 0, type, reason);
        }

        /**
         * 从发送方拉文件（HTTPS + ZIP 解包）。
         * 对齐 CatShare：GET https://host:port/download?taskId=xxx → application/zip，
         * 条目名形如 "0/文件名"（踩坑 P023/P024）。
         */
        public boolean download(String host, int port, String taskId, String fileName,
                                Progress progress, javax.net.ssl.SSLContext sslCtx) {
            InputStream in = null;
            java.util.zip.ZipInputStream zin = null;
            File last = null;
            try {
                String urlStr = "https://" + host + ":" + port + DOWNLOAD_PATH + "?taskId=" + taskId;
                Log.i(TAG, "下载 " + urlStr);
                HttpsURLConnection conn = (HttpsURLConnection) new URL(urlStr).openConnection();
                if (sslCtx != null) conn.setSSLSocketFactory(sslCtx.getSocketFactory());
                conn.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                    @Override public boolean verify(String h, javax.net.ssl.SSLSession s) { return true; }
                });
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(120000);
                conn.connect();
                int code = conn.getResponseCode();
                Log.i(TAG, "HTTP " + code + " type=" + conn.getContentType());
                if (code != 200) {
                    listener.onResult(false, "HTTP " + code);
                    return false;
                }
                long total = conn.getContentLengthLong();

                if (!outDir.exists()) outDir.mkdirs();

                in = new BufferedInputStream(conn.getInputStream());
                zin = new java.util.zip.ZipInputStream(in);
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                java.util.zip.ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    // 条目名是 "0/文件名"，取最后一段
                    String name = e.getName();
                    int slash = name.lastIndexOf('/');
                    if (slash >= 0) name = name.substring(slash + 1);
                    File target = uniqueFile(outDir, name.isEmpty() ? fileName : name);
                    OutputStream out = new BufferedOutputStream(new FileOutputStream(target));
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        if (progress != null) progress.onProgress(got, total);
                    }
                    out.flush(); out.close();
                    zin.closeEntry();
                    last = target;
                    Log.i(TAG, "解出条目 " + e.getName() + " → " + target);
                }
                Log.i(TAG, "下载完成 " + got + " 字节 → " + last);
                if (progress != null) {
                    progress.onDone(true, last == null ? "(空)" : last.getAbsolutePath());
                }
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "下载失败", t);
                if (progress != null) progress.onDone(false, String.valueOf(t));
                return false;
            } finally {
                try { if (zin != null) zin.close(); } catch (Throwable ignored) {}
                closeQuietly(in);
            }
        }

        /** 兼容旧签名：明文 HTTP + 非 ZIP（仅用于自测）。 */
        public boolean download(String host, int port, String taskId, String fileName,
                                Progress progress) {
            return download(host, port, taskId, fileName, progress, null);
        }

        public void close() {
            try { if (ws != null) ws.close(); } catch (Throwable ignored) {}
        }
    }

    // ---- 工具 ----

    /** 避免重名覆盖：name.ext → name(1).ext */
    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            File c = new File(dir, base + "(" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return f;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) {}
    }

    private Transfer() {}
}
