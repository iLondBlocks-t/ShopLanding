package com.ocpilot.app.core;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;

/** Local submissions are a real queue, not simulated server progress. At most 50 entries. */
public final class TaskLog {
    public static final class Entry {
        public String id, server, project, session, prompt, state, log = "", messageId = "";
        public JSONObject model = new JSONObject();
        public long started, ended;
        public boolean build;
        JSONObject json() { return Api.json("id", id, "server", server, "project", project, "session", session, "prompt", prompt,
            "state", state, "log", log, "model", model, "started", started, "ended", ended, "build", build, "messageId", messageId); }
        static Entry parse(JSONObject j) {
            Entry e = new Entry(); e.id = Models.string(j, "id"); e.server = Models.string(j, "server");
            e.project = Models.string(j, "project"); e.session = Models.string(j, "session"); e.prompt = Models.string(j, "prompt");
            e.state = Models.string(j, "state"); e.log = Models.string(j, "log"); e.model = Models.object(j, "model");
            e.started = j.optLong("started"); e.ended = j.optLong("ended"); e.build = j.optBoolean("build");
            e.messageId = Models.string(j, "messageId"); return e;
        }
        Entry copy() { return parse(json()); }
    }
    private final List<Entry> entries = new ArrayList<>();
    private final Store store;
    private final ExecutorService executor;
    private final Object persistLock = new Object();
    public TaskLog(Store store, ExecutorService executor) {
        this.store = store; this.executor = executor;
        try {
            JSONArray array = new JSONArray(store.prefs().getString("tasks", "[]"));
            for (int i = 0; i < array.length() && entries.size() < 50; i++) {
                JSONObject j = array.optJSONObject(i); if (j != null) entries.add(Entry.parse(j));
            }
        } catch (Exception ignored) { }
    }
    public synchronized Entry add(String project, String session, String prompt, JSONObject model, boolean queued) {
        // Refuse to evict an active task: the caller can ask the user to stop or finish it first.
        while (entries.size() >= 50) {
            int removable = -1;
            for (int i = 0; i < entries.size(); i++) if (!active(entries.get(i).state)) { removable = i; break; }
            if (removable < 0) return null;
            entries.remove(removable);
        }
        Entry e = new Entry(); e.id = UUID.randomUUID().toString(); e.server = store.server(); e.project = project; e.session = session;
        e.prompt = prompt; e.model = model; e.messageId = nextMessageId(); e.state = queued ? "queued" : "working"; e.started = System.currentTimeMillis();
        entries.add(e); persist(); return e.copy();
    }
    private static long lastTimestamp;
    private static int counter;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Verified ascending Identifier algorithm, packages/schema/src/identifier.ts (OpenCode v1.18.35). */
    private static synchronized String nextMessageId() {
        long time = System.currentTimeMillis(); if (lastTimestamp != time) { lastTimestamp = time; counter = 0; }
        long value = time * 0x1000L + ++counter;
        StringBuilder result = new StringBuilder("msg_").append(String.format(java.util.Locale.ROOT, "%012x", value & 0xffffffffffffL));
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < 14; i++) result.append(alphabet.charAt(RANDOM.nextInt(256) % 62));
        return result.toString();
    }
    public synchronized void begin(String id) { for (Entry e : entries) if (e.id.equals(id)) { e.state = "working"; e.started = System.currentTimeMillis(); e.ended = 0; } persist(); }
    public static boolean active(String state) { return state.equals("working") || state.equals("queued") || state.equals("permission"); }
    public synchronized List<Entry> list(String project) {
        List<Entry> r = new ArrayList<>();
        for (Entry e : entries) if (e.server.equals(store.server()) && (project == null || e.project.equals(project))) r.add(e.copy());
        Collections.reverse(r); return r;
    }
    public synchronized void update(String id, String state) {
        for (Entry e : entries) if (e.id.equals(id)) { e.state = state; if (!active(state)) e.ended = System.currentTimeMillis(); else e.ended = 0; break; }
        persist();
    }
    public synchronized void tool(String taskId, JSONObject tool) {
        for (Entry e : entries) if (e.id.equals(taskId)) {
            String command = Models.string(Models.object(Models.object(tool, "state"), "input"), "command");
            if (Models.string(tool, "tool").equals("bash") && Events.isBuildCommand(command)) e.build = true;
            break;
        }
    }
    public synchronized void toolLog(String taskId, List<JSONObject> tools) {
        for (Entry e : entries) if (e.id.equals(taskId)) {
            StringBuilder text = new StringBuilder();
            for (JSONObject tool : tools) {
                JSONObject state = Models.object(tool, "state");
                text.append(Events.toolSummary(tool)).append('\n').append(Models.string(state, "output")).append(Models.string(state, "error")).append('\n');
            }
            e.log = text.length() <= 16000 ? text.toString() : text.substring(text.length() - 16000); break;
        }
        persist();
    }
    public synchronized void clear() { entries.clear(); persist(); }
    /** A submission is not sent or cleared from the draft until this commit succeeds. */
    public void durable(android.os.Handler main, Api.Callback<Void> result) {
        executor.execute(() -> {
            boolean ok = false;
            try {
                synchronized (persistLock) {
                    List<Entry> snapshot = new ArrayList<>();
                    synchronized (TaskLog.this) { for (Entry e : entries) snapshot.add(e.copy()); }
                    JSONArray array = new JSONArray(); for (Entry e : snapshot) array.put(e.json());
                    ok = store.prefs().edit().putString("tasks", array.toString()).commit();
                }
            } catch (Exception ex) { Log.failure(ex); }
            final boolean saved = ok;
            main.post(() -> { if (saved) result.success(null); else result.error(new ApiError(ApiError.Kind.Storage)); });
        });
    }
    private void persist() {
        executor.execute(() -> {
            // Serialize file writes on a worker, not with a disk lock held by the UI thread.
            synchronized (persistLock) {
                List<Entry> snapshot = new ArrayList<>();
                synchronized (TaskLog.this) { for (Entry e : entries) snapshot.add(e.copy()); }
                JSONArray array = new JSONArray(); for (Entry e : snapshot) array.put(e.json());
                try { store.prefs().edit().putString("tasks", array.toString()).commit(); } catch (Exception ex) { Log.failure(ex); }
            }
        });
    }
}
