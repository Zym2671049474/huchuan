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
        final ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        // 注意：不使用 lambda / 方法引用 —— 严格 android.jar 引导类路径下缺少
        // java.lang.invoke.LambdaMetafactory，javac 会报 "Unable to find method metafactory"。
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (!ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        s.setTcpNoDelay(true);
                        Thread c = new Thread(new Runnable() {
                            @Override public void run() { handleServerConn(s, path, handler); }
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
        return ss;
    }

    private static void handleServerConn(Socket s, String path, Handler handler) {
        try {
            InputStream in = s.getInputStream();
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

    /** 连接远程 WS 服务端。 */
    public static Conn connect(String host, int port, String path, int timeoutMs, Handler handler) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
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
