package com.ocpilot.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Schema-tolerant value objects. Never stores provider keys or GitHub credentials. */
public final class Models {
    private Models() { }
    public interface Reader<T> { T read(JSONObject json); }
    public static String string(JSONObject j, String key) {
        return j == null || j.isNull(key) ? "" : j.optString(key, "");
    }
    public static JSONObject object(JSONObject j, String key) {
        JSONObject v = j == null ? null : j.optJSONObject(key);
        return v == null ? new JSONObject() : v;
    }
    public static JSONArray array(JSONObject j, String key) {
        JSONArray v = j == null ? null : j.optJSONArray(key);
        return v == null ? new JSONArray() : v;
    }
    public static List<String> strings(JSONArray a) {
        List<String> r = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) if (!a.isNull(i)) r.add(a.optString(i, ""));
        return r;
    }
    public static <T> List<T> list(JSONArray array, Reader<T> reader) {
        List<T> r = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject j = array.optJSONObject(i);
            if (j != null) r.add(reader.read(j));
        }
        return r;
    }
    public static long timestamp(String value) {
        if (value == null || value.isEmpty()) return 0;
        try { long n = Long.parseLong(value); return n < 100000000000L ? n * 1000L : n; }
        catch (NumberFormatException ignored) { }
        try { return Instant.parse(value).toEpochMilli(); }
        catch (Exception ignored) {
            try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); } catch (Exception ex) { return 0; }
        }
    }
    public static final class Health {
        public boolean ok, running;
        public String gateway, opencodeVersion;
        public long uptimeSec;
        public static Health fromJson(JSONObject j) {
            Health h = new Health(); h.ok = j.optBoolean("ok"); h.gateway = string(j, "gateway");
            JSONObject oc = object(j, "opencode"); h.running = oc.optBoolean("running");
            h.opencodeVersion = string(oc, "version"); h.uptimeSec = j.optLong("uptimeSec"); return h;
        }
    }
    public static final class Pairing {
        public String deviceId, deviceToken;
        public static Pairing fromJson(JSONObject j) {
            Pairing p = new Pairing(); p.deviceId = string(j, "deviceId"); p.deviceToken = string(j, "deviceToken"); return p;
        }
    }
    public static final class Rotation {
        public String deviceToken;
        public static Rotation fromJson(JSONObject j) { Rotation r = new Rotation(); r.deviceToken = string(j, "deviceToken"); return r; }
    }
    public static final class Github {
        public boolean connected;
        public String login;
        public List<String> scopes;
        public static Github fromJson(JSONObject j) {
            Github g = new Github(); g.connected = j.optBoolean("connected"); g.login = string(j, "login");
            g.scopes = strings(array(j, "scopes")); return g;
        }
    }
    public static final class Status {
        public String gatewayVersion, startedAt, opencodeVersion, lastError, node, deviceId, deviceName;
        public long uptimeSec, freeDiskMB, memTotalMB, memFreeMB;
        public int restarts;
        public boolean running, git, java, gradle, androidSdk;
        public Github github;
        public static Status fromJson(JSONObject j) {
            Status s = new Status(); JSONObject g = object(j, "gateway"), oc = object(j, "opencode");
            JSONObject system = object(j, "system"), tools = object(j, "tools"), device = object(j, "device");
            s.gatewayVersion = string(g, "version"); s.startedAt = string(g, "startedAt"); s.uptimeSec = g.optLong("uptimeSec");
            s.running = oc.optBoolean("running"); s.opencodeVersion = string(oc, "version");
            s.restarts = oc.optInt("restarts"); s.lastError = string(oc, "lastError");
            s.github = Github.fromJson(object(j, "github")); s.freeDiskMB = system.optLong("freeDiskMB");
            s.memTotalMB = system.optLong("memTotalMB"); s.memFreeMB = system.optLong("memFreeMB");
            s.git = tools.optBoolean("git"); s.java = tools.optBoolean("java"); s.gradle = tools.optBoolean("gradle");
            s.androidSdk = tools.optBoolean("androidSdk"); s.node = string(tools, "node");
            s.deviceId = string(device, "id"); s.deviceName = string(device, "name"); return s;
        }
    }
    public static final class Device {
        public String id, name, createdAt, lastSeenAt;
        public boolean current;
        public static Device fromJson(JSONObject j) {
            Device d = new Device(); d.id = string(j, "id"); d.name = string(j, "name");
            d.createdAt = string(j, "createdAt"); d.lastSeenAt = string(j, "lastSeenAt"); d.current = j.optBoolean("current"); return d;
        }
    }
    public static final class Repo {
        public String fullName, defaultBranch, description, updatedAt, cloneUrl;
        public boolean isPrivate;
        public static Repo fromJson(JSONObject j) {
            Repo r = new Repo(); r.fullName = string(j, "fullName"); r.defaultBranch = string(j, "defaultBranch");
            r.description = string(j, "description"); r.updatedAt = string(j, "updatedAt");
            r.cloneUrl = string(j, "cloneUrl"); r.isPrivate = j.optBoolean("private"); return r;
        }
    }
    public static final class Project {
        public String id, name, repoFullName, branch, createdAt, lastActivityAt;
        public boolean archived;
        public static Project fromJson(JSONObject j) {
            Project p = new Project(); p.id = string(j, "id"); p.name = string(j, "name"); p.repoFullName = string(j, "repoFullName");
            p.branch = string(j, "branch"); p.archived = j.optBoolean("archived"); p.createdAt = string(j, "createdAt");
            p.lastActivityAt = string(j, "lastActivityAt"); return p;
        }
        public JSONObject toJson() {
            JSONObject j = new JSONObject();
            try { j.put("id", id); j.put("name", name); j.put("repoFullName", repoFullName); j.put("branch", branch);
                j.put("archived", archived); j.put("createdAt", createdAt); j.put("lastActivityAt", lastActivityAt); }
            catch (org.json.JSONException ignored) { }
            return j;
        }
    }
    public static final class Artifact {
        public String path, sha256, modifiedAt, kind;
        public long size;
        public static Artifact fromJson(JSONObject j) {
            Artifact a = new Artifact(); a.path = string(j, "path"); a.sha256 = string(j, "sha256");
            a.modifiedAt = string(j, "modifiedAt"); a.kind = string(j, "kind");
            if (a.kind.isEmpty()) a.kind = "other"; a.size = Math.max(0, j.optLong("size")); return a;
        }
        public String filename() { int i = path.lastIndexOf('/'); return i < 0 ? path : path.substring(i + 1); }
        public boolean isApk() { return kind.equals("apk") || kind.equals("apk-debug") || kind.equals("apk-release"); }
        public JSONObject toJson() {
            JSONObject j = new JSONObject(); try { j.put("path", path); j.put("sha256", sha256); j.put("size", size);
                j.put("kind", kind); j.put("modifiedAt", modifiedAt); } catch (org.json.JSONException ignored) { } return j;
        }
    }
    public static final class Git {
        public String branch;
        public boolean dirty;
        public int ahead;
        public List<Commit> lastCommits;
        public static Git fromJson(JSONObject j) {
            Git g = new Git(); g.branch = string(j, "branch"); g.dirty = j.optBoolean("dirty"); g.ahead = j.optInt("ahead");
            g.lastCommits = list(array(j, "lastCommits"), Commit::fromJson); return g;
        }
    }
    public static final class Commit {
        public String sha, subject, date;
        public static Commit fromJson(JSONObject j) { Commit c = new Commit(); c.sha = string(j, "sha"); c.subject = string(j, "subject"); c.date = string(j, "date"); return c; }
    }
    public static final class MessagesPage {
        public final JSONArray entries; public final String nextCursor;
        public MessagesPage(JSONArray entries, String nextCursor) { this.entries = entries; this.nextCursor = nextCursor; }
    }
    public static final class Session {
        public String id, title;
        public long created, updated;
        public static Session fromJson(JSONObject j) {
            Session s = new Session(); s.id = string(j, "id"); s.title = string(j, "title"); JSONObject t = object(j, "time");
            s.created = t.optLong("created"); s.updated = t.optLong("updated"); return s;
        }
    }
    public static final class ModelChoice {
        public String providerID, modelID, name;
        public static List<ModelChoice> fromProviders(JSONObject j) {
            List<ModelChoice> r = new ArrayList<>(); JSONArray providers = array(j, "providers");
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i); if (p == null) continue;
                JSONObject models = object(p, "models"); Iterator<String> keys = models.keys();
                while (keys.hasNext()) {
                    String key = keys.next(); JSONObject m = models.optJSONObject(key); if (m == null) continue;
                    ModelChoice choice = new ModelChoice(); choice.providerID = string(p, "id"); choice.modelID = string(m, "id");
                    if (choice.modelID.isEmpty()) choice.modelID = key;
                    choice.name = string(m, "name"); if (choice.name.isEmpty()) choice.name = choice.modelID;
                    if (!choice.providerID.isEmpty()) r.add(choice);
                }
            }
            return r;
        }
        public JSONObject toJson() {
            JSONObject j = new JSONObject(); try { j.put("providerID", providerID); j.put("modelID", modelID); } catch (org.json.JSONException ignored) { } return j;
        }
    }
    public static final class Diff {
        public String file, patch;
        public int additions, deletions;
        public static Diff fromJson(JSONObject j) {
            Diff d = new Diff(); d.file = string(j, "file"); d.patch = string(j, "patch");
            d.additions = j.optInt("additions"); d.deletions = j.optInt("deletions"); return d;
        }
    }
}
