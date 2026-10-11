package com.ocpilot.app.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import com.ocpilot.app.BuildConfig;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Locale;

public final class ShareProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    public static Uri uri(File file) { return new Uri.Builder().scheme("content").authority(BuildConfig.APPLICATION_ID + ".share").appendPath(file.getName()).build(); }
    private File resolve(Uri uri) {
        try {
            List<String> segments = uri.getPathSegments();
            if (segments.size() != 1 || uri.getQuery() != null || uri.getFragment() != null) throw new SecurityException("invalid_file");
            String name = segments.get(0);
            if (name.contains("..") || name.contains("/") || name.contains("\\") || name.endsWith(".part") || name.isEmpty()) throw new SecurityException("invalid_file");
            Context c = getContext(); if (c == null) throw new SecurityException("invalid_context");
            File root = Downloads.directory(c).getCanonicalFile(); File file = new File(root, name).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile()) throw new SecurityException("invalid_file");
            return file;
        } catch (SecurityException ex) { throw ex; }
        catch (Exception ex) { throw new SecurityException("invalid_file"); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        String[] requested = projection == null ? new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE } : projection;
        MatrixCursor cursor = new MatrixCursor(requested, 1); Object[] values = new Object[requested.length];
        for (int i = 0; i < requested.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(requested[i])) values[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(requested[i])) values[i] = file.length();
        }
        cursor.addRow(values); return cursor;
    }
    public static String mime(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".log") || name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
    @Override public String getType(Uri uri) { return mime(resolve(uri)); }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read_only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read_only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read_only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read_only"); }
}
