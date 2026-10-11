package com.ocpilot.app.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import org.json.JSONTokener;

/** Blocking transport: Api runs it on the shared executor, never on the UI thread. */
public final class HttpCall {
    public interface Network { boolean available(); }
    public static final class Response {
        public final int status; public final Object json; public final String nextCursor;
        Response(int status, Object json, String nextCursor) { this.status = status; this.json = json; this.nextCursor = nextCursor == null ? "" : nextCursor; }
    }
    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;
    private volatile Future<?> future;
    public boolean isDone() { Future<?> f = future; return cancelled || f != null && f.isDone(); }
    public boolean isCancelled() { return cancelled; }
    public void future(Future<?> value) { future = value; if (cancelled) value.cancel(true); }
    public synchronized void attach(HttpURLConnection c) throws ApiError {
        if (cancelled) { c.disconnect(); throw new ApiError(ApiError.Kind.Cancelled); }
        connection = c;
    }
    public void cancel() {
        cancelled = true; HttpURLConnection c = connection; if (c != null) c.disconnect();
        Future<?> f = future; if (f != null) f.cancel(true);
    }
    public Response execute(String base, String path, String method, String body, String token,
                            String projectId, String userAgent, int timeoutMs, Network network) throws ApiError {
        if (cancelled) throw new ApiError(ApiError.Kind.Cancelled);
        if (!network.available()) throw new ApiError(ApiError.Kind.Network);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection(); attach(c);
            c.setInstanceFollowRedirects(false); c.setUseCaches(false);
            c.setConnectTimeout(10000); c.setReadTimeout(timeoutMs); c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", userAgent);
            if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            if (projectId != null && !projectId.isEmpty()) c.setRequestProperty("X-Project-Id", projectId);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int code = c.getResponseCode();
            String text;
            try { text = read(code >= 400 ? c.getErrorStream() : c.getInputStream(), 8 * 1024 * 1024); }
            catch (Exception ex) { if (code == 401) throw ApiError.http(401, "", c.getHeaderField("Retry-After")); throw ex; }
            if (cancelled) throw new ApiError(ApiError.Kind.Cancelled);
            if (code < 200 || code >= 300) throw ApiError.http(code, text, c.getHeaderField("Retry-After"));
            if (code == 204 || text.trim().isEmpty()) return new Response(code, null, cursor(c));
            try {
                JSONTokener tokener = new JSONTokener(text); Object json = tokener.nextValue();
                if (tokener.nextClean() != 0) throw new ApiError(ApiError.Kind.Parse);
                return new Response(code, json, cursor(c));
            } catch (org.json.JSONException ex) { throw new ApiError(ApiError.Kind.Parse); }
        } catch (ApiError ex) { throw ex; }
        catch (IOException | SecurityException ex) { throw new ApiError(cancelled ? ApiError.Kind.Cancelled : ApiError.Kind.Network); }
        catch (Exception | StackOverflowError ex) { throw new ApiError(ApiError.Kind.Parse); }
        finally { if (c != null) c.disconnect(); connection = null; }
    }
    private static String cursor(HttpURLConnection c) {
        String next = c.getHeaderField("X-Next-Cursor");
        return next == null || next.isEmpty() ? UrlUtil.cursorFromLink(c.getHeaderField("Link")) : next;
    }
    public static String read(InputStream input, int maxBytes) throws IOException, ApiError {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n, count = 0;
            while ((n = in.read(buffer)) != -1) {
                count += n; if (count > maxBytes) throw new ApiError(ApiError.Kind.Parse);
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
