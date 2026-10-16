package com.huchuan.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简 WebSocket (RFC 6455) 实现 —— 无第三方依赖。
 *
 * 支持：文本帧、二进制帧、分片续帧(continuation)、ping/pong、close。
 * 服务端接收的帧必须带 mask，发送的帧不带 mask；客户端相反。
 *
 * 之所以手写：本机无 Gradle/Maven，无法引入 Ktor/Java-WebSocket。
 * 协议对端(CatShare/互传联盟)使用标准 WS，故必须严格符合 RFC 6455。
 */
public final class MiniWebSocket {

    public interface Handler {
        void onOpen(Conn c);
        void onMessage(Conn c, String text);
        void onBinary(Conn c, byte[] data);
        void onClose(Conn c);
        void onError(Conn c, Throwable t);
    }

    /** 一个已建立的连接。send 方法线程安全。 */
    public static final class Conn {
        final Socket sock;
        final OutputStream out;
        final InputStream in;
        public final boolean isServer;
        final Handler handler;
        private final Object sendLock = new Object();
        private volatile boolean closed;

        Conn(Socket s, boolean isServer, Handler h) throws IOException {
            this.sock = s; this.isServer = isServer; this.handler = h;
            this.out = s.getOutputStream();
            this.in = s.getInputStream();
        }

        public boolean isClosed() { return closed; }

        public void sendText(String text) throws IOException {
            android.util.Log.i("HuChuan.Wire", "TX " + text);
            sendFrame(0x1, text.getBytes("UTF-8"));
        }

        public void sendBinary(byte[] data) throws IOException {
            sendFrame(0x2, data);
        }

        public void sendPing(byte[] data) throws IOException {
            sendFrame(0x9, data == null ? new byte[0] : data);
        }

        private void sendFrame(int opcode, byte[] payload) throws IOException {
            synchronized (sendLock) {
                if (closed) throw new IOException("连接已关闭");
                ByteArrayOutputStream h = new ByteArrayOutputStream(10);
                h.write(0x80 | opcode);                 // FIN=1
                int len = payload.length;
                // 客户端必须 mask，服务端不得 mask
                int maskBit = isServer ? 0x00 : 0x80;
                if (len < 126) {
                    h.write(maskBit | len);
                } else if (len <= 0xFFFF) {
                    h.write(maskBit | 126);
                    h.write((len >>> 8) & 0xFF); h.write(len & 0xFF);
                } else {
                    h.write(maskBit | 127);
                    long L = len;
                    for (int i = 7; i >= 0; i--) h.write((int) ((L >>> (8 * i)) & 0xFF));
                }
                byte[] maskKey = null;
                if (!isServer) {
                    maskKey = new byte[4];
                    for (int i = 0; i < 4; i++) maskKey[i] = (byte) (Math.random() * 256);
                    h.write(maskKey);
                }
                out.write(h.toByteArray());
                if (maskKey != null) {
                    byte[] masked = new byte[len];
                    for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ maskKey[i & 3]);
                    out.write(masked);
                } else {
                    out.write(payload);
                }
                out.flush();
            }
        }

        /** 阻塞读循环，直到连接关闭。 */
        void readLoop() {
            ByteArrayOutputStream frag = new ByteArrayOutputStream();
            int fragOpcode = -1;
            try {
                while (!closed) {
                    int b0 = in.read();
                    if (b0 < 0) break;
                    boolean fin = (b0 & 0x80) != 0;
                    int opcode = b0 & 0x0F;
                    int b1 = in.read();
                    if (b1 < 0) break;
                    boolean masked = (b1 & 0x80) != 0;
                    long len = b1 & 0x7F;
                    if (len == 126) {
                        len = ((long) readByte() << 8) | readByte();
                    } else if (len == 127) {
                        len = 0;
                        for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                    }
                    byte[] maskKey = null;
                    if (masked) { maskKey = new byte[4]; readFully(maskKey, 4); }
                    if (len > 64L * 1024 * 1024) throw new IOException("帧过大: " + len);
                    byte[] payload = new byte[(int) len];
                    readFully(payload, (int) len);
                    if (masked) for (int i = 0; i < payload.length; i++)
                        payload[i] = (byte) (payload[i] ^ maskKey[i & 3]);

                    switch (opcode) {
                        case 0x8: // close
                            closed = true;
                            try { sendFrame(0x8, new byte[0]); } catch (IOException ignored) {}
                            return;
                        case 0x9: // ping -> pong
                            sendFrame(0xA, payload);
                            break;
                        case 0xA: // pong
                            break;
                        case 0x0: // continuation
                            frag.write(payload);
                            if (fin) { dispatch(fragOpcode, frag.toByteArray()); frag.reset(); fragOpcode = -1; }
                            break;
                        case 0x1:
                        case 0x2:
                            if (fin) { dispatch(opcode, payload); }
                            else { frag.reset(); frag.write(payload); fragOpcode = opcode; }
                            break;
                        default:
                            break;
                    }
                }
            } catch (Exception e) {
                if (!closed) handler.onError(this, e);
            } finally {
                closeQuietly();
                handler.onClose(this);
            }
        }

        private void dispatch(int opcode, byte[] data) {
            if (opcode == 0x1) android.util.Log.i("HuChuan.Wire", "RX " + new String(data, java.nio.charset.StandardCharsets.UTF_8));
            if (opcode == 0x1) handler.onMessage(this, new String(data, java.nio.charset.StandardCharsets.UTF_8));
            else if (opcode == 0x2) handler.onBinary(this, data);
        }

        private int readByte() throws IOException {
            int v = in.read();
            if (v < 0) throw new EOFException();
            return v;
        }

        private void readFully(byte[] buf, int len) throws IOException {
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) throw new EOFException();
                off += n;
            }
        }

        public void close() {
            closed = true;
            closeQuietly();
        }

        private void closeQuietly() {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    /** 启动 WS 服务端；每个连接一个线程。 */
    public static ServerSocket startServer(int port, String path, Handler handler) throws IOException {
        return startServer(port, path, handler, null);
    }

    /**
     * 在一个**已经 accept 出来的 socket** 上完成 WS 握手，然后进入读循环。
     *
     * 用于「单端口同时提供 /websocket 和 /download」的场景（CatShare 就是这么做的：
     * Ktor 的 embeddedServer 一个端口挂两个路由，P2pInfo 里只有一个 port）。
     * 调用方负责先窥探请求行、判断这是 WS 升级请求。
     *
     * @param in  已经读走请求行之外内容也没关系，本方法会重新解析完整请求头
     * @param out socket 的输出流
     */
    public static void upgradeAccepted(Socket s, Handler handler) throws IOException {
        handleServerConn(s, s.getInputStream(), handler);
    }

    /**
     * 同上，但使用调用方已经缓冲（并回推过首行）的流。
     *
     * 单端口路由场景下必须用这个重载：路由层已经从流里读了请求行并 unread 回
     * PushbackInputStream，如果再走 socket.getInputStream() 会把首行丢掉。
     */
    public static void upgradeAccepted(Socket s, InputStream bufferedIn, Handler handler)
            throws IOException {
        handleServerConn(s, bufferedIn, handler);
    }

    /** 直接在一个 ServerSocket 上跑 accept 循环（与 startServer 共用 handleServerConn）。 */
    public static void acceptLoop(final ServerSocket ss, final String path, final Handler handler) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (!ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        s.setTcpNoDelay(true);
                        Thread c = new Thread(new Runnable() {
                            @Override public void run() { handleServerConn(s, null, handler); }
                        });
                        c.setDaemon(true);
                        c.start();
                    } catch (IOException e) {
                        if (ss.isClosed()) return;
                    }
                }
            }
        }, "ws-accept");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 启动 WS 服务端。
     * @param sslContext 非 null 时使用 TLS（wss://）—— 与 CatShare/小米互传一致。
     */
    public static ServerSocket startServer(int port, String path, Handler handler,
                                           javax.net.ssl.SSLContext sslContext) throws IOException {
        final ServerSocket ss;
        if (sslContext != null) {
            javax.net.ssl.SSLServerSocketFactory f = sslContext.getServerSocketFactory();
            javax.net.ssl.SSLServerSocket sss =
                    (javax.net.ssl.SSLServerSocket) f.createServerSocket();
            sss.setReuseAddress(true);
            sss.bind(new InetSocketAddress(port));
            // 自签名证书没有 CA 链，必须显式允许，否则握手失败
            try {
                sss.setNeedClientAuth(false);
                sss.setWantClientAuth(false);
            } catch (Throwable ignored) {}
            ss = sss;
        } else {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
        }
        acceptLoop(ss, path, handler);
        return ss;
    }

    private static void handleServerConn(Socket s, InputStream bufferedIn, Handler handler) {
        try {
            InputStream in = (bufferedIn != null) ? bufferedIn : s.getInputStream();
            String req = readHttpRequest(in);
            Map<String, String> hdrs = new HashMap<>();
            String[] lines = req.split("\r\n");
            for (String line : lines) {
                int i = line.indexOf(':');
                if (i > 0) hdrs.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            String key = hdrs.get("sec-websocket-key");
            if (key == null) { s.close(); return; }
            String accept = makeAccept(key);
            String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            s.getOutputStream().write(resp.getBytes("ASCII"));
            s.getOutputStream().flush();
            Conn conn = new Conn(s, true, handler);
            handler.onOpen(conn);
            conn.readLoop();
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    /** 连接远程 WS 服务端（明文）。 */
    public static Conn connect(String host, int port, String path, int timeoutMs, Handler handler) throws IOException {
        return connect(host, port, path, timeoutMs, handler, null);
    }

    /**
     * 连接远程 WS 服务端。
     * @param sslContext 非 null 时使用 TLS（wss://）。
     */
    public static Conn connect(String host, int port, String path, int timeoutMs, Handler handler,
                               javax.net.ssl.SSLContext sslContext) throws IOException {
        Socket s;
        if (sslContext != null) {
            javax.net.ssl.SSLSocketFactory f = sslContext.getSocketFactory();
            javax.net.ssl.SSLSocket sss = (javax.net.ssl.SSLSocket) f.createSocket();
            sss.connect(new InetSocketAddress(host, port), timeoutMs);
            // 对齐 CatShare 的 hostnameVerifier{ _, _ -> true }：不校验主机名
            try {
                javax.net.ssl.SSLParameters p = sss.getSSLParameters();
                p.setEndpointIdentificationAlgorithm(null);
                sss.setSSLParameters(p);
            } catch (Throwable ignored) {}
            sss.startHandshake();
            s = sss;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
        }
        s.setTcpNoDelay(true);
        byte[] nonce = new byte[16];
        for (int i = 0; i < 16; i++) nonce[i] = (byte) (Math.random() * 256);
        String key = base64(nonce);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        s.getOutputStream().write(req.getBytes("ASCII"));
        s.getOutputStream().flush();
        InputStream in = s.getInputStream();
        String head = readHttpRequest(in);
        if (!head.startsWith("HTTP/1.1 101") && !head.contains(" 101 ")) {
            s.close();
            throw new IOException("WS 握手失败: " + head.split("\r\n")[0]);
        }
        Conn conn = new Conn(s, false, handler);
        Thread t = new Thread(new Runnable() {
            @Override public void run() { conn.readLoop(); }
        }, "ws-read");
        t.setDaemon(true);
        t.start();
        handler.onOpen(conn);
        return conn;
    }

    private static String readHttpRequest(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException("HTTP 头未结束");
            buf.write(c);
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') { state++; if (state == 4) break; }
            else state = (c == '\r') ? 1 : 0;
            if (buf.size() > 65536) throw new IOException("HTTP 头过大");
        }
        return new String(buf.toByteArray(), "ASCII");
    }

    public static String makeAccept(String key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return java.util.Base64.getEncoder().encodeToString(md.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("ASCII")));
    }


    static String base64(byte[] b) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }


    private MiniWebSocket() {}
}
