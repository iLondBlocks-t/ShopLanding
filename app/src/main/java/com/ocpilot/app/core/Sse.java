package com.ocpilot.app.core;

import com.ocpilot.app.App;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** One dedicated thread per live project. All observers are dispatched on the main Handler. */
public final class Sse {
    public enum State { CONNECTING, LIVE, RECONNECTING, STOPPED }
    public interface Listener {
        void state(State state, int attempt);
        void event(JSONObject event);
        void error(ApiError error);
        void connected(boolean reconnected);
    }
    private final App app;
    private final String project, server, device;
    private final Listener listener;
    private final Object sleepLock = new Object();
    private volatile boolean stopped;
    private volatile HttpURLConnection connection;
    private Thread thread;
    private String lastId = "";
    public Sse(App app, String project, Listener listener) { this.app = app; this.project = project; this.listener = listener; this.server = app.store.server(); this.device = app.store.deviceId(); }
    public synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this::loop, "ocpilot-sse"); thread.setDaemon(true); thread.start();
    }
    public void stop() {
        stopped = true; HttpURLConnection c = connection; if (c != null) c.disconnect();
        synchronized (sleepLock) { sleepLock.notifyAll(); }
        Thread t = thread; if (t != null) t.interrupt();
    }
    private void notifyState(State state, int attempt) { app.main.post(() -> app.safe(() -> listener.state(state, attempt))); }
    private void loop() {
        Backoff backoff = new Backoff(app.store.reconnectCap()); boolean hadConnection = false;
        while (!stopped) {
            notifyState(hadConnection || backoff.attempts() > 0 ? State.RECONNECTING : State.CONNECTING, backoff.attempts());
            HttpURLConnection c = null; String token = app.store.token(); long liveSince = 0;
            try {
                if (token.isEmpty()) throw new ApiError(ApiError.Kind.Unauthorized);
                if (!app.networkAvailable()) throw new ApiError(ApiError.Kind.Network);
                c = (HttpURLConnection) new URL(server + "/oc/event").openConnection(); connection = c;
                if (stopped) break;
                c.setInstanceFollowRedirects(false); c.setUseCaches(false); c.setConnectTimeout(10000); c.setReadTimeout(0);
                c.setRequestProperty("Accept", "text/event-stream"); c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("User-Agent", Api.userAgent()); c.setRequestProperty("X-Project-Id", project);
                if (!lastId.isEmpty()) c.setRequestProperty("Last-Event-ID", lastId);
                int code = c.getResponseCode();
                if (code != 200) {
                    String detail = ""; try { detail = HttpCall.read(c.getErrorStream(), 65536); } catch (Exception ex) { if (code != 401) throw ex; }
                    throw ApiError.http(code, detail, c.getHeaderField("Retry-After"));
                }
                String contentType = c.getContentType();
                if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) throw new ApiError(ApiError.Kind.Parse);
                liveSince = System.nanoTime(); notifyState(State.LIVE, 0);
                boolean reconnect = hadConnection; hadConnection = true;
                app.main.post(() -> { if (!stopped) app.safe(() -> listener.connected(reconnect)); });
                SseParser parser = new SseParser(frame -> {
                    lastId = frame.id;
                    try {
                        JSONObject event = new JSONObject(frame.data);
                        app.main.post(() -> { if (!stopped) app.safe(() -> listener.event(event)); });
                    } catch (Exception | StackOverflowError ignored) {
                        app.main.post(() -> { if (!stopped) app.safe(() -> listener.error(new ApiError(ApiError.Kind.Parse))); });
                    }
                }, lastId);
                try (InputStream stream = c.getInputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while (!stopped && (n = stream.read(buffer)) != -1) {
                        parser.feed(buffer, 0, n); lastId = parser.lastId();
                        if (System.nanoTime() - liveSince >= 30000000000L) backoff.reset();
                    }
                    parser.end(); lastId = parser.lastId();
                }
            } catch (Exception | StackOverflowError ex) {
                if (stopped) break;
                ApiError error = ex instanceof ApiError ? (ApiError) ex : new ApiError(ex instanceof java.io.IOException ? ApiError.Kind.Network : ApiError.Kind.Parse);
                if (error.kind == ApiError.Kind.Unauthorized) {
                    stopped = true; app.main.post(() -> { if (server.equals(app.store.server()) && device.equals(app.store.deviceId())) app.unauthorized(token); });
                }
                app.main.post(() -> app.safe(() -> listener.error(error)));
            } finally {
                if (c != null) c.disconnect(); connection = null;
            }
            if (stopped) break;
            if (liveSince != 0 && System.nanoTime() - liveSince >= 30000000000L) backoff.reset();
            long delay = backoff.nextMillis(); notifyState(State.RECONNECTING, backoff.attempts());
            try { synchronized (sleepLock) { if (!stopped) sleepLock.wait(delay); } }
            catch (InterruptedException ex) { if (stopped) break; }
        }
        notifyState(State.STOPPED, 0);
    }
}
