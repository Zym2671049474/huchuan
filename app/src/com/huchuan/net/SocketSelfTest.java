package com.huchuan.net;

import android.util.Log;
import java.net.ServerSocket; import java.net.Socket;

/** 自检：确认 app 有 INTERNET 权限，socket 能开（踩坑 P021）。 */
public final class SocketSelfTest {
    private static final String TAG = "HuChuan.SocketTest";
    /** 返回 null 表示正常，否则返回错误描述。 */
    public static String run() {
        ServerSocket ss = null; Socket c = null;
        try {
            ss = new ServerSocket(0);
            final int port = ss.getLocalPort();
            final ServerSocket fss = ss;
            Thread t = new Thread(new Runnable() { @Override public void run() {
                try { Socket s = fss.accept(); s.getOutputStream().write("ok".getBytes()); s.close(); }
                catch (Exception ignored) {}
            }});
            t.setDaemon(true); t.start();
            c = new Socket("127.0.0.1", port);
            byte[] b = new byte[2];
            new java.io.DataInputStream(c.getInputStream()).readFully(b);
            Log.i(TAG, "socket 自检通过: " + new String(b));
            return null;
        } catch (Throwable e) {
            Log.e(TAG, "socket 自检失败", e);
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
            try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
        }
    }
    private SocketSelfTest() {}
}
