package com.ocpilot.app.core;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import com.ocpilot.app.App;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Downloads {
    public interface Listener { void changed(); }
    public interface Result { void success(File file); void error(ApiError error); }
    public static final class Job {
        public volatile long bytes, total;
        public volatile boolean active = true, cancelled, verifying;
        public volatile ApiError error;
        volatile HttpURLConnection connection;
        public void cancel() { cancelled = true; HttpURLConnection c = connection; if (c != null) c.disconnect(); }
    }
    private final App app;
    private File root;
    private final Map<String, Job> jobs = new HashMap<>(); // main-thread confined
    private final Map<String, File> verified = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new ArrayList<>(); // main-thread confined
    private static final Pattern RANGE = Pattern.compile("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$");
    public Downloads(App app) { this.app = app; }
    public void initialize() throws IOException { root = directory(app); if (!root.exists() && !root.mkdirs()) throw new IOException("downloads_unavailable"); }
    public static File directory(Context c) {
        try { File external = c.getExternalFilesDir("downloads"); if (external != null) return external; } catch (Exception ex) { Log.failure(ex); }
        return new File(c.getFilesDir(), "downloads");
    }
    public File root() { return root; }
    private static String key(String server, String project, Models.Artifact a) { return server + "\n" + project + "\n" + a.path + "\n" + a.sha256; }
    private String key(String project, Models.Artifact a) { return key(app.store.server(), project, a); }
    private File fileForKey(String key, Models.Artifact a) {
        try {
            String prefix = Hashing.hex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
            String name = a.filename().replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_"); if (name.length() > 100) name = name.substring(name.length() - 100);
            if (name.isEmpty() || name.equals(".")) name = "artifact";
            if (root == null) throw new IllegalStateException("downloads_unavailable"); return new File(root, prefix + "_" + name);
        } catch (Exception ex) { throw new IllegalStateException("file_name_failed"); }
    }
    public File file(String project, Models.Artifact a) { return fileForKey(key(project, a), a); }
    public File verifiedFile(String project, Models.Artifact a) { return verified.get(key(project, a)); }
    public Job job(String project, Models.Artifact a) { return jobs.get(key(project, a)); }
    public void observe(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void unobserve(Listener l) { listeners.remove(l); }
    private void changed() { for (Listener l : new ArrayList<>(listeners)) app.safe(l::changed); }
    private void postChanged() { app.main.post(this::changed); }
    public void scan(String project, List<Models.Artifact> artifacts) {
        final String server = app.store.server(); final List<Models.Artifact> snapshot = new ArrayList<>(artifacts);
        app.requests.execute(() -> {
            for (Models.Artifact a : snapshot) {
                String key = key(server, project, a);
                try { File f = fileForKey(key, a); if (f.isFile()) { validate(f, a, null); verified.put(key, f); } }
                catch (Exception ex) { verified.remove(key); }
            }
            postChanged();
        });
    }
    public Job start(String project, Models.Artifact artifact) {
        final String key = key(project, artifact); Job old = jobs.get(key); if (old != null && old.active) return old;
        final Job job = new Job(); job.total = artifact.size; jobs.put(key, job); changed();
        final String server = app.store.server(), device = app.store.deviceId(), url = app.api.downloadUrl(project, artifact.path), token = app.store.token();
        final File target;
        try { target = fileForKey(key, artifact); }
        catch (Exception ex) { job.active = false; job.error = new ApiError(ApiError.Kind.Storage); changed(); return job; }
        app.requests.execute(() -> {
            File part = new File(target.getPath() + ".part");
            try {
                if (!artifact.sha256.matches("(?i)[0-9a-f]{64}") || artifact.path.isEmpty()) throw new ApiError(ApiError.Kind.Parse);
                if (token.isEmpty()) throw new ApiError(ApiError.Kind.Unauthorized);
                if (!app.networkAvailable()) throw new ApiError(ApiError.Kind.Network);
                long lastUpdate = 0; boolean transferDone = false;
                for (int attempt = 0; attempt < 2 && !transferDone; attempt++) {
                    if (job.cancelled) throw new ApiError(ApiError.Kind.Cancelled);
                    long offset = part.isFile() ? part.length() : 0;
                    if (offset > artifact.size) { deletePart(part); offset = 0; }
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); job.connection = c;
                    try {
                        c.setInstanceFollowRedirects(false); c.setUseCaches(false); c.setConnectTimeout(10000); c.setReadTimeout(60000);
                        c.setRequestProperty("Authorization", "Bearer " + token); c.setRequestProperty("User-Agent", Api.userAgent());
                        c.setRequestProperty("Accept", "application/octet-stream"); c.setRequestProperty("Accept-Encoding", "identity");
                        if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
                        if (job.cancelled) throw new ApiError(ApiError.Kind.Cancelled);
                        int code = c.getResponseCode();
                        if (code == 416 && offset == artifact.size && offset > 0) { job.bytes = offset; transferDone = true; break; }
                        if (code == 416) { deletePart(part); continue; }
                        if (code != 200 && code != 206) throw ApiError.http(code, HttpCall.read(c.getErrorStream(), 65536), c.getHeaderField("Retry-After"));
                        long end = artifact.size - 1;
                        if (code == 206) {
                            Matcher m = RANGE.matcher(String.valueOf(c.getHeaderField("Content-Range")));
                            if (!m.matches()) { deletePart(part); continue; }
                            long start = Long.parseLong(m.group(1)), actualEnd = Long.parseLong(m.group(2)), total = Long.parseLong(m.group(3));
                            if (start != offset || total != artifact.size || actualEnd < start || actualEnd != total - 1) { deletePart(part); continue; }
                            end = actualEnd;
                        } else offset = 0; // A server ignoring Range must never append the entire file twice.
                        String length = c.getHeaderField("Content-Length");
                        if (length != null && Long.parseLong(length) != end - offset + 1) throw new ApiError(ApiError.Kind.Client, 0, "hash_mismatch", "", 0);
                        job.bytes = offset; postChanged();
                        InputStream in = c.getInputStream();
                        try (InputStream source = in; FileOutputStream out = new FileOutputStream(part, offset > 0)) {
                            byte[] buffer = new byte[32768]; int n;
                            while ((n = source.read(buffer)) != -1) {
                                if (job.cancelled) throw new ApiError(ApiError.Kind.Cancelled);
                                try { out.write(buffer, 0, n); } catch (IOException ex) { throw new ApiError(ApiError.Kind.Storage); }
                                job.bytes += n; if (job.bytes > artifact.size) throw new ApiError(ApiError.Kind.Client, 0, "hash_mismatch", "", 0);
                                long now = System.nanoTime(); if (now - lastUpdate >= 250000000L) { lastUpdate = now; postChanged(); }
                            }
                            try { out.getFD().sync(); } catch (IOException ex) { throw new ApiError(ApiError.Kind.Storage); }
                        } catch (java.io.FileNotFoundException ex) { throw new ApiError(ApiError.Kind.Storage); }
                        transferDone = true;
                    } finally { c.disconnect(); job.connection = null; }
                }
                if (!transferDone) throw new ApiError(ApiError.Kind.Network);
                job.verifying = true; postChanged(); validate(part, artifact, job);
                if (job.cancelled) throw new ApiError(ApiError.Kind.Cancelled);
                try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                verified.put(key, target);
            } catch (Exception ex) {
                ApiError error = ex instanceof ApiError ? (ApiError) ex : new ApiError(job.cancelled ? ApiError.Kind.Cancelled : job.verifying ? ApiError.Kind.Storage : ex instanceof IOException ? ApiError.Kind.Network : ApiError.Kind.Parse);
                job.error = error; if (error.code.equals("hash_mismatch")) { part.delete(); verified.remove(key); }
                if (error.kind == ApiError.Kind.Unauthorized) app.main.post(() -> { if (server.equals(app.store.server()) && device.equals(app.store.deviceId())) app.unauthorized(token); });
            } finally { job.active = false; job.verifying = false; postChanged(); }
        }); return job;
    }
    private static void deletePart(File file) throws ApiError { if (file.exists() && !file.delete()) throw new ApiError(ApiError.Kind.Storage); }
    private void validate(File file, Models.Artifact artifact, Job job) throws IOException, ApiError {
        if (!artifact.sha256.matches("(?i)[0-9a-f]{64}")) throw new ApiError(ApiError.Kind.Parse);
        String hash;
        try (InputStream in = new FilterInputStream(new FileInputStream(file)) {
            @Override public int read(byte[] b, int off, int len) throws IOException { if (job != null && job.cancelled) throw new IOException("cancelled"); return super.read(b, off, len); }
        }) { hash = Hashing.sha256(in); }
        if (file.length() != artifact.size || !hash.equalsIgnoreCase(artifact.sha256)) { file.delete(); throw new ApiError(ApiError.Kind.Client, 0, "hash_mismatch", "", 0); }
    }
    /** Re-verify before every install/share/export, including after returning from Android settings. */
    public void verified(String project, Models.Artifact artifact, Result result) {
        final String key = key(project, artifact); final File target = fileForKey(key, artifact);
        app.requests.execute(() -> {
            try { validate(target, artifact, null); verified.put(key, target); app.main.post(() -> app.safe(() -> result.success(target))); }
            catch (Exception ex) { verified.remove(key); ApiError e = ex instanceof ApiError ? (ApiError) ex : new ApiError(ApiError.Kind.Storage); app.main.post(() -> app.safe(() -> result.error(e))); }
        });
    }
    public void delete(String project, Models.Artifact a, Api.Callback<Void> result) {
        Job job = job(project, a); if (job != null && job.active) job.cancel(); final String key = key(project, a); final File target = fileForKey(key, a);
        app.requests.execute(() -> {
            try { File part = new File(target.getPath() + ".part"); boolean ok = (!target.exists() || target.delete()) && (!part.exists() || part.delete()); verified.remove(key);
                app.main.post(() -> { changed(); app.safe(() -> { if (ok) result.success(null); else result.error(new ApiError(ApiError.Kind.Storage)); }); });
            } catch (Exception ex) { app.main.post(() -> app.safe(() -> result.error(new ApiError(ApiError.Kind.Storage)))); }
        });
    }
    public void cancelAll() { for (Job j : jobs.values()) if (j.active) j.cancel(); }
    public boolean anyActive() { for (Job j : jobs.values()) if (j.active) return true; return false; }
    public void clearAll() throws IOException { verified.clear(); File[] files = root.listFiles(); if (files != null) for (File f : files) if (!f.delete()) throw new IOException("cache_delete_failed"); app.main.post(() -> { jobs.clear(); changed(); }); }
    public void exportPublic(File file, Models.Artifact artifact, Api.Callback<Void> cb) {
        app.requests.execute(() -> {
            Uri uri = null; File partial = null;
            try {
                validate(file, artifact, null);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME, artifact.filename()); values.put(MediaStore.Downloads.MIME_TYPE, ShareProvider.mime(file));
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OCPilot"); values.put(MediaStore.Downloads.IS_PENDING, 1);
                    uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values); if (uri == null) throw new IOException("mediastore_insert_failed");
                    try (InputStream in = new FileInputStream(file); OutputStream out = app.getContentResolver().openOutputStream(uri)) { if (out == null) throw new IOException("mediastore_open_failed"); copy(in, out); }
                    ContentValues ready = new ContentValues(); ready.put(MediaStore.Downloads.IS_PENDING, 0); if (app.getContentResolver().update(uri, ready, null, null) == 0) throw new IOException("mediastore_publish_failed");
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); if (!dir.exists() && !dir.mkdirs()) throw new IOException("public_downloads_failed");
                    File target = new File(dir, "ocpilot_" + System.currentTimeMillis() + "_" + artifact.filename().replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_")); partial = new File(target.getPath() + ".part");
                    try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(partial)) { copy(in, out); } validate(partial, artifact, null);
                    if (!partial.renameTo(target)) throw new IOException("public_downloads_move_failed");
                    MediaScannerConnection.scanFile(app, new String[]{target.getAbsolutePath()}, new String[]{ShareProvider.mime(file)}, null);
                }
                app.main.post(() -> app.safe(() -> cb.success(null)));
            } catch (Exception ex) {
                if (Build.VERSION.SDK_INT >= 29 && uri != null) try { app.getContentResolver().delete(uri, null, null); } catch (Exception cleanup) { Log.failure(cleanup); }
                if (partial != null) partial.delete(); ApiError error = ex instanceof ApiError ? (ApiError) ex : new ApiError(ApiError.Kind.Storage); app.main.post(() -> app.safe(() -> cb.error(error)));
            }
        });
    }
    private static void copy(InputStream in, OutputStream out) throws IOException { byte[] buffer = new byte[32768]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); }
}
