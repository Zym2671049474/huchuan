package com.huchuan;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把本地文件以 {@code content://} 暴露出去（P121）。
 *
 * <h2>为什么需要它</h2>
 *
 * 用户要求"点击通知直接打开文件"。做法是把已接收的文件交给系统查看器：
 *
 * <pre>
 * Intent i = new Intent(Intent.ACTION_VIEW);
 * i.setDataAndType(uri, mimeType);
 * i.addFlags(FLAG_GRANT_READ_URI_PERMISSION);
 * startActivity(i);
 * </pre>
 *
 * 但这个 {@code uri} <b>不能是 {@code file://}</b> —— Android 7（API 24）起
 * 会直接抛 {@code FileUriExposedException}。必须用 {@code content://}。
 *
 * <h2>为什么不用 androidx 的 FileProvider</h2>
 *
 * 小米互传用的是 {@code androidx.core.content.FileProvider}
 * （反编译 `com/miui/mishare/file/FileInfo.getContentUri`：
 * {@code FileProvider.h(context, "com.miui.mishare", file)}），
 * 但**我们这套构建没有 androidx 依赖** —— 只对着 {@code android.jar} 编译
 * （见 `build.sh`）。所以自己写一个最小实现，**只用 framework API**。
 *
 * 功能上等价：{@code openFile} 返回真实 fd，并支持
 * {@code OpenableColumns.DISPLAY_NAME} / {@code SIZE} 查询
 * （系统查看器和"分享"面板会读这两个字段来决定显示名和大小）。
 *
 * <h2>安全</h2>
 *
 * {@code exported="false"} + {@code grantUriPermissions="true"} ——
 * 只有拿到我们显式授权的组件才能读，跟 FileProvider 行为一致。
 */
public class FileContentProvider extends ContentProvider {

    private static final String TAG = "HuChuan.FileProvider";

    /** 与 manifest 里 android:authorities 保持一致。 */
    public static final String AUTHORITY = "com.huchuan.fileprovider";

    /**
     * 把 File 包装成可分享的 {@code content://} URI。
     *
     * @return URI；文件为 null 时返回 null
     */
    public static Uri uriFor(File f) {
        if (f == null) return null;
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("file")
                .appendQueryParameter("p", f.getAbsolutePath())
                .build();
    }

    /** 从 URI 还原出真实路径。 */
    private static String pathOf(Uri uri) {
        String p = uri.getQueryParameter("p");
        if (p != null && !p.isEmpty()) return p;
        // 兜底：路径直接拼在 path 段里
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() >= 2 && "file".equals(seg.get(0))) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < seg.size(); i++) {
                if (i > 1) sb.append('/');
                sb.append(seg.get(i));
            }
            return sb.toString();
        }
        return null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String p = pathOf(uri);
        if (p == null) throw new FileNotFoundException("无效的 URI: " + uri);
        File f = new File(p);
        if (!f.exists()) throw new FileNotFoundException("文件不存在: " + p);
        // 只读暴露 —— 我们从不通过它写文件
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * 支持 DISPLAY_NAME / SIZE 查询。
     *
     * ★这两个字段不是可选的★ —— 系统"分享"面板和很多查看器会查它们；
     * 返回空游标会导致对方显示成 "unknown" 或直接拒绝打开。
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String p = pathOf(uri);
        if (p == null) return null;
        File f = new File(p);
        if (!f.exists()) return null;

        // 只回应我们支持的两列（顺带兼容调用方传 null projection）
        String[] cols = (projection == null)
                ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }
                : projection;

        MatrixCursor c = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = c.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(f.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(f.length());
            } else {
                row.add(null);
            }
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String p = pathOf(uri);
        if (p == null) return "application/octet-stream";
        String name = new File(p).getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    // ---- 只读，其余操作一律不支持 ----

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("只读 provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读 provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读 provider");
    }
}
