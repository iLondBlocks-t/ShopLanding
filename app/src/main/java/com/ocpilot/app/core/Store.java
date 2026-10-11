package com.ocpilot.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class Store {
    private final SharedPreferences prefs;
    private final Object credentialWrites = new Object(), credentialState = new Object();
    private final AtomicLong generation = new AtomicLong();
    private volatile String credential = "";
    public Store(Context c) { prefs = c.getSharedPreferences("ocpilot", Context.MODE_PRIVATE); }
    public SharedPreferences prefs() { return prefs; }
    public long generation() { return generation.get(); }
    /** Called on the executor during Application initialization. */
    public void restoreCredential() throws GeneralSecurityException {
        long epoch = generation.get(); String encrypted = prefs.getString("credential", "");
        String token = encrypted.isEmpty() ? "" : Crypto.decrypt(encrypted);
        synchronized (credentialState) { if (epoch == generation.get()) credential = token; }
    }
    public String token() { return credential; }
    public boolean paired() { return !credential.isEmpty(); }
    public String server() { return prefs.getString("server", ""); }
    public String deviceId() { return prefs.getString("device_id", ""); }
    public String deviceName() { return prefs.getString("device_name", ""); }
    /** Encryption + durability check run on a worker. A concurrent disconnect cannot resurrect a credential. */
    public void saveCredentials(String server, String deviceId, String deviceName, String token, long expected) throws GeneralSecurityException {
        synchronized (credentialWrites) {
            if (expected != generation.get()) throw new GeneralSecurityException("credential_operation_cancelled");
            if (token == null || token.isEmpty() || deviceId == null || deviceId.isEmpty()) throw new GeneralSecurityException("empty_credential");
            String encrypted = Crypto.encrypt(token);
            if (expected != generation.get()) throw new GeneralSecurityException("credential_operation_cancelled");
            SharedPreferences.Editor edit = prefs.edit().putString("server", server).putString("device_id", deviceId)
                .putString("device_name", deviceName).putString("credential", encrypted);
            if (!server.equals(server())) edit.remove("selected_project");
            boolean saved = edit.commit();
            synchronized (credentialState) {
                if (!saved || expected != generation.get()) {
                    if (encrypted.equals(prefs.getString("credential", ""))) prefs.edit().remove("credential").apply();
                    throw new GeneralSecurityException("credential_storage_failed");
                }
                credential = token; generation.incrementAndGet();
            }
        }
    }
    public void rotate(String token, long expected) throws GeneralSecurityException { saveCredentials(server(), deviceId(), deviceName(), token, expected); }
    public void clearCredential() {
        synchronized (credentialState) {
            generation.incrementAndGet(); credential = "";
            prefs.edit().remove("credential").remove("device_id").remove("device_name").remove("selected_project").apply();
        }
        clearLists();
    }
    public String theme() { return prefs.getString("theme", "dark"); }
    // Editor.apply() changes memory immediately and delegates disk writes to Android QueuedWork.
    public void theme(String mode) { prefs.edit().putString("theme", mode).apply(); }
    public boolean notifications() { return prefs.getBoolean("notifications", false); }
    public void notifications(boolean enabled) { prefs.edit().putBoolean("notifications", enabled).apply(); }
    public int reconnectCap() { return prefs.getInt("reconnect_cap", 30); }
    public void reconnectCap(int seconds) { prefs.edit().putInt("reconnect_cap", seconds).apply(); }
    public int readTimeout() { return prefs.getInt("read_timeout", 30); }
    public void readTimeout(int seconds) { prefs.edit().putInt("read_timeout", seconds).apply(); }
    public String selectedProject() { return prefs.getString("selected_project", ""); }
    public void selectedProject(String id) { prefs.edit().putString("selected_project", id).apply(); }
    private static String scope(String server) { return Base64.encodeToString(server.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP); }
    private String projectKey(String project, String suffix) { return "project_" + scope(server()) + "_" + project + "_" + suffix; }
    public String session(String project) { return prefs.getString(projectKey(project, "session"), ""); }
    public void session(String project, String session) { prefs.edit().putString(projectKey(project, "session"), session).apply(); }
    public String draft(String project) { return prefs.getString(projectKey(project, "draft"), ""); }
    public void draft(String project, String draft) { prefs.edit().putString(projectKey(project, "draft"), draft).apply(); }
    public JSONObject model(String project) {
        try { return new JSONObject(prefs.getString(projectKey(project, "model"), "{}")); } catch (Exception ex) { return new JSONObject(); }
    }
    public void model(String project, JSONObject model) { prefs.edit().putString(projectKey(project, "model"), model.toString()).apply(); }
    public void cacheProjects(String server, List<Models.Project> list) {
        JSONArray array = new JSONArray(); for (Models.Project p : list) array.put(p.toJson());
        prefs.edit().putString("projects_cache_" + scope(server), array.toString()).apply();
    }
    public List<Models.Project> cachedProjects() {
        try { return Models.list(new JSONArray(prefs.getString("projects_cache_" + scope(server()), "[]")), Models.Project::fromJson); }
        catch (Exception ex) { return new java.util.ArrayList<>(); }
    }
    public void clearLists() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith("projects_cache_")) editor.remove(key);
        editor.apply();
    }
    public void crash(Throwable ex) {
        // No exception messages, stack arguments, URLs, bodies, codes or tokens.
        prefs.edit().putString("crash_note", ex.getClass().getSimpleName() + "@" + System.currentTimeMillis()).apply();
    }
    public String crashNote() { return prefs.getString("crash_note", ""); }
    public void dismissCrash() { prefs.edit().remove("crash_note").apply(); }
}
