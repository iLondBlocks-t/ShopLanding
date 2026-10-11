package com.ocpilot.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Session-scoped, idempotent reducer of the verified OpenCode v1 event schema. */
public final class Events {
    public enum State { CONNECTING, QUEUED, WORKING, RUNNING_TOOL, WAITING_PERMISSION, COMPLETED, FAILED, CANCELLED }
    public enum ToolLabel { COMMAND, EDIT, READ, WEB, AGENT, BUILD, TEST, OTHER }
    public static final class Part {
        public JSONObject json;
        public double revision;
        int cost; StringBuilder stream; boolean dirty;
        void flushText() { if (stream != null && dirty) try { json.put("text", stream.toString()); dirty = false; } catch (org.json.JSONException ignored) { } }
        Part(JSONObject json, double revision) { this.json = json; this.revision = revision; this.cost = json.toString().length(); }
    }
    public static final class Message {
        public JSONObject info = new JSONObject();
        int infoCost;
        public final LinkedHashMap<String, Part> parts = new LinkedHashMap<>();
    }
    public static final class Row {
        public final String key, type, role, text;
        public final JSONObject part;
        public Row(String key, String type, String role, String text, JSONObject part) {
            this.key = key; this.type = type; this.role = role; this.text = text; this.part = part;
        }
    }
    private final String sessionId;
    private final LinkedHashMap<String, Message> messages = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> permissions = new LinkedHashMap<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private List<Models.Diff> diffs = new ArrayList<>();
    private boolean live, busy, worked, statusKnown;
    private int queued, cachedChars, partCount;
    private static final int MAX_PARTS = 2000, MAX_CACHE_CHARS = 8 * 1024 * 1024;
    private State terminal = State.COMPLETED;
    private String error = "";
    public Events(String sessionId) { this.sessionId = sessionId; }
    public String sessionId() { return sessionId; }
    public void live(boolean value) { live = value; }
    public void queued(int count) { queued = Math.max(0, count); }
    public void sent() { worked = true; error = ""; terminal = State.COMPLETED; }
    public boolean busy() { return busy; }
    public boolean statusKnown() { return statusKnown; }
    public String error() { return error; }
    public boolean worked() { return worked; }
    public List<Models.Diff> diffs() { return new ArrayList<>(diffs); }
    public List<JSONObject> permissions() { return new ArrayList<>(permissions.values()); }
    public void permissionAnswered(String id) { permissions.remove(id); }
    public void setPermissions(JSONArray list) {
        permissions.clear();
        mergePermissions(list);
    }
    public void mergePermissions(JSONArray list) {
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p != null && sessionId.equals(Models.string(p, "sessionID")) && !Models.string(p, "id").isEmpty())
                permissions.put(Models.string(p, "id"), copy(p));
        }
    }
    public State state() {
        if (!permissions.isEmpty()) return State.WAITING_PERMISSION;
        if (terminal == State.FAILED || terminal == State.CANCELLED) return terminal;
        if (runningTool() != null) return State.RUNNING_TOOL;
        if (busy) return State.WORKING;
        if (queued > 0) return State.QUEUED;
        if (!live || !statusKnown) return State.CONNECTING;
        return State.COMPLETED;
    }
    public JSONObject runningTool() {
        if (!busy) return null;
        for (Message m : messages.values()) for (Part p : m.parts.values())
            if (Models.string(p.json, "type").equals("tool") && Models.string(Models.object(p.json, "state"), "status").equals("running")) return p.json;
        return null;
    }
    public void abortAcknowledged() {
        busy = false; statusKnown = true; terminal = State.CANCELLED; permissions.clear();
    }
    public void failure(String detail) { error = detail == null ? "" : detail; busy = false; terminal = State.FAILED; statusKnown = true; }
    public void status(JSONObject status) {
        String type = Models.string(status, "type");
        if (type.equals("busy") || type.equals("retry")) { busy = true; worked = true; terminal = State.COMPLETED; statusKnown = true; }
        else if (type.equals("idle")) {
            busy = false; statusKnown = true;
            if (terminal != State.FAILED && terminal != State.CANCELLED) terminal = State.COMPLETED;
            // An idle session cannot have a currently running tool or pending permission.
            permissions.clear();
        }
    }
    public boolean apply(JSONObject event) {
        if (event == null) return false;
        String type = Models.string(event, "type");
        JSONObject p = Models.object(event, "properties");
        String scope = Models.string(p, "sessionID");
        JSONObject info = p.optJSONObject("info"), part = p.optJSONObject("part");
        if (scope.isEmpty() && info != null) scope = Models.string(info, "sessionID");
        if (scope.isEmpty() && part != null) scope = Models.string(part, "sessionID");
        if (!sessionId.equals(scope)) return false;
        if (info != null && !sessionId.equals(Models.string(info, "sessionID"))) return false;
        if (part != null && !sessionId.equals(Models.string(part, "sessionID"))) return false;
        String eventId = Models.string(event, "id");
        if (!eventId.isEmpty() && !seen.add(eventId)) return false;
        if (seen.size() > 4096) { Iterator<String> it = seen.iterator(); it.next(); it.remove(); }
        switch (type) {
            case "session.status": status(Models.object(p, "status")); return true;
            case "session.idle": status(idle()); return true;
            case "session.error":
                JSONObject e = Models.object(p, "error");
                error = errorText(e); busy = false; statusKnown = true;
                terminal = Models.string(e, "name").equals("MessageAbortedError") ? State.CANCELLED : State.FAILED;
                permissions.clear(); return true;
            case "message.updated":
                if (info == null || Models.string(info, "id").isEmpty()) return false;
                putInfo(message(Models.string(info, "id")), info); trim(); return true;
            case "message.part.updated":
                return part != null && putPart(part, p.optDouble("time", 0), false);
            case "message.part.delta":
                if (!Models.string(p, "field").equals("text")) return false;
                Message m = messages.get(Models.string(p, "messageID"));
                Part existing = m == null ? null : m.parts.get(Models.string(p, "partID"));
                if (existing == null) return false; // Re-sync recovers a missing initial part; never guess its type.
                String partType = Models.string(existing.json, "type");
                if (!partType.equals("text") && !partType.equals("reasoning")) return false;
                if (existing.stream == null) { String initial = Models.string(existing.json, "text"); existing.stream = new StringBuilder(initial); existing.cost += initial.length(); cachedChars += initial.length(); }
                String delta = Models.string(p, "delta"); existing.stream.append(delta); existing.dirty = true; existing.cost += delta.length() * 2; cachedChars += delta.length() * 2; trim(); return true;
            case "message.removed": return removeMessage(Models.string(p, "messageID"));
            case "message.part.removed":
                Message parent = messages.get(Models.string(p, "messageID"));
                if (parent == null) return false; Part removed = parent.parts.remove(Models.string(p, "partID"));
                if (removed != null) { cachedChars -= removed.cost; partCount--; } return removed != null;
            case "permission.asked":
                String permission = Models.string(p, "id");
                if (permission.isEmpty()) return false;
                permissions.put(permission, copy(p)); return true;
            case "permission.replied": permissions.remove(Models.string(p, "requestID")); return true;
            case "session.diff": diffs = Models.list(Models.array(p, "diff"), Models.Diff::fromJson); return true;
            default: return false; // Includes unscoped file.edited and unrelated v2/unknown events.
        }
    }
    public void mergeHistory(JSONArray history) {
        if (history == null) return;
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i); if (entry == null) continue;
            JSONObject info = entry.optJSONObject("info");
            if (info == null || !sessionId.equals(Models.string(info, "sessionID")) || Models.string(info, "id").isEmpty()) continue;
            Message m = message(Models.string(info, "id"));
            long oldCompleted = Models.object(m.info, "time").optLong("completed");
            long completed = Models.object(info, "time").optLong("completed");
            if (completed >= oldCompleted) putInfo(m, info);
            JSONArray parts = Models.array(entry, "parts");
            for (int k = 0; k < parts.length(); k++) {
                JSONObject part = parts.optJSONObject(k);
                if (part != null) putPart(part, partTime(part), true);
            }
        }
        trim();
    }
    private void putInfo(Message message, JSONObject info) {
        JSONObject selected = new JSONObject();
        try { for (String key : new String[]{"id","sessionID","role","parentID","time","error"}) if (info.has(key)) selected.put(key, info.get(key)); } catch (org.json.JSONException ignored) { }
        JSONObject safe = copy(selected); int cost = safe.toString().length(); cachedChars += cost - message.infoCost; message.info = safe; message.infoCost = cost;
    }
    private boolean removeMessage(String id) {
        Message removed = messages.remove(id); if (removed == null) return false;
        cachedChars -= removed.infoCost; for (Part part : removed.parts.values()) { cachedChars -= part.cost; partCount--; } return true;
    }
    private Message message(String id) {
        Message m = messages.get(id);
        if (m == null) { m = new Message(); messages.put(id, m); }
        return m;
    }
    private boolean putPart(JSONObject part, double revision, boolean history) {
        if (!sessionId.equals(Models.string(part, "sessionID"))) return false;
        String id = Models.string(part, "id"), messageId = Models.string(part, "messageID");
        if (id.isEmpty() || messageId.isEmpty()) return false;
        Message m = message(messageId); Part previous = m.parts.get(id);
        if (previous != null) {
            previous.flushText();
            String oldState = Models.string(Models.object(previous.json, "state"), "status");
            String newState = Models.string(Models.object(part, "state"), "status");
            if ((oldState.equals("completed") || oldState.equals("error")) && (newState.equals("running") || newState.equals("pending"))) return false;
            String oldText = Models.string(previous.json, "text"), newText = Models.string(part, "text");
            boolean terminalSnapshot = history && (newState.equals("completed") || newState.equals("error")) && !oldState.equals("completed") && !oldState.equals("error");
            if (revision > 0 && previous.revision > revision && !terminalSnapshot && !(history && newText.startsWith(oldText) && newText.length() > oldText.length())) return false;
            if ((history || revision <= previous.revision) && !oldText.isEmpty() && newText.length() < oldText.length()) return false;
            if (part.toString().equals(previous.json.toString())) return false;
        }
        Part updated = new Part(copy(part), Math.max(revision, previous == null ? 0 : previous.revision));
        cachedChars += updated.cost - (previous == null ? 0 : previous.cost); if (previous == null) partCount++; m.parts.put(id, updated);
        if (!history && Models.string(part, "type").equals("tool") && Models.string(Models.object(part, "state"), "status").equals("running")) {
            busy = true; worked = true; statusKnown = true;
        }
        trim(); return true;
    }
    private void trim() {
        // Server history stays authoritative. Only the native in-memory window is bounded.
        while (messages.size() > 1500 || partCount > MAX_PARTS || cachedChars > MAX_CACHE_CHARS) {
            List<Map.Entry<String, Message>> ordered = ordered(); if (ordered.isEmpty()) break;
            Map.Entry<String, Message> oldest = ordered.get(0);
            if (messages.size() > 1500 || oldest.getValue().parts.isEmpty()) { removeMessage(oldest.getKey()); continue; }
            Iterator<Map.Entry<String, Part>> parts = oldest.getValue().parts.entrySet().iterator();
            Map.Entry<String, Part> first = parts.next(); cachedChars -= first.getValue().cost; partCount--; parts.remove();
        }
    }
    private static long partTime(JSONObject part) {
        JSONObject time = Models.object(part, "time");
        if (Models.string(part, "type").equals("tool")) time = Models.object(Models.object(part, "state"), "time");
        return time.optLong("end", time.optLong("start", 0));
    }
    public String firstMessageId() {
        List<Map.Entry<String, Message>> ordered = ordered(); return ordered.isEmpty() ? "" : ordered.get(0).getKey();
    }
    public int messageCount() { return messages.size(); }
    public String lastUserText() {
        String result = "";
        for (Map.Entry<String, Message> entry : ordered()) {
            Message m = entry.getValue();
            if (Models.string(m.info, "role").equals("user")) {
                StringBuilder b = new StringBuilder();
                for (Part p : m.parts.values()) { p.flushText(); if (Models.string(p.json, "type").equals("text")) b.append(Models.string(p.json, "text")); }
                if (b.length() > 0) result = b.toString();
            }
        }
        return result;
    }
    public List<JSONObject> tools() {
        List<JSONObject> r = new ArrayList<>();
        for (Map.Entry<String, Message> entry : ordered()) for (Part p : entry.getValue().parts.values())
            if (Models.string(p.json, "type").equals("tool")) r.add(p.json);
        return r;
    }
    public List<Row> rows() {
        List<Row> result = new ArrayList<>();
        for (Map.Entry<String, Message> entry : ordered()) {
            Message m = entry.getValue(); String role = Models.string(m.info, "role");
            for (Map.Entry<String, Part> item : m.parts.entrySet()) {
                item.getValue().flushText(); JSONObject p = item.getValue().json; String type = Models.string(p, "type");
                if (type.equals("text") || type.equals("reasoning") || type.equals("tool") || type.equals("patch") || type.equals("file") || type.equals("step-start") || type.equals("step-finish"))
                    result.add(new Row(item.getKey(), type, role, Models.string(p, "text"), p));
            }
            JSONObject error = m.info.optJSONObject("error");
            if (error != null) result.add(new Row(entry.getKey() + "_error", "error", role, errorText(error), error));
        }
        for (Models.Diff diff : diffs) {
            JSONObject json = new JSONObject(); try { json.put("file", diff.file); } catch (org.json.JSONException ignored) { }
            result.add(new Row("diff_" + diff.file, "change", "", diff.file, json));
        }
        if (!error.isEmpty()) result.add(new Row("session_error", terminal == State.CANCELLED ? "cancelled" : "error", "", error, null));
        return result;
    }
    public boolean hasMessage(String id) { return messages.containsKey(id); }
    public List<JSONObject> toolsForUser(String userMessageId) {
        List<JSONObject> result = new ArrayList<>();
        for (Message message : messages.values()) if (userMessageId.equals(Models.string(message.info, "parentID")))
            for (Part part : message.parts.values()) if (Models.string(part.json, "type").equals("tool")) result.add(part.json);
        return result;
    }
    public boolean assistantFinished(String userMessageId) {
        for (Message message : messages.values()) if (userMessageId.equals(Models.string(message.info, "parentID"))
            && (Models.object(message.info, "time").optLong("completed") > 0 || message.info.optJSONObject("error") != null)) return true;
        return false;
    }
    public JSONObject assistantError(String userMessageId) {
        for (Message message : messages.values()) if (userMessageId.equals(Models.string(message.info, "parentID")) && message.info.optJSONObject("error") != null) return message.info.optJSONObject("error");
        return null;
    }
    public void clearView() { messages.clear(); diffs.clear(); }
    private List<Map.Entry<String, Message>> ordered() {
        List<Map.Entry<String, Message>> list = new ArrayList<>(messages.entrySet());
        Collections.sort(list, Comparator.comparingLong(e -> Models.object(e.getValue().info, "time").optLong("created")));
        return list;
    }
    public static ToolLabel toolLabel(JSONObject part) {
        String tool = Models.string(part, "tool");
        String command = Models.string(Models.object(Models.object(part, "state"), "input"), "command");
        if (tool.equals("bash")) {
            if (Pattern.compile("(?i)(\\btest\\b|pytest|jest)").matcher(command).find()) return ToolLabel.TEST;
            if (isBuildCommand(command)) return ToolLabel.BUILD;
            return ToolLabel.COMMAND;
        }
        if (tool.equals("edit") || tool.equals("write") || tool.equals("patch") || tool.equals("apply_patch")) return ToolLabel.EDIT;
        if (tool.equals("read") || tool.equals("grep") || tool.equals("glob") || tool.equals("list")) return ToolLabel.READ;
        if (tool.equals("webfetch")) return ToolLabel.WEB;
        if (tool.equals("task")) return ToolLabel.AGENT;
        return ToolLabel.OTHER;
    }
    public static boolean isBuildCommand(String command) { return Pattern.compile("(?i)(\\bgradle(?:w)?\\b|assemble|npm\\s+run\\s+build|\\bmvn\\b)").matcher(command == null ? "" : command).find(); }
    public static String toolSummary(JSONObject part) {
        JSONObject state = Models.object(part, "state"), input = Models.object(state, "input");
        for (String key : new String[] {"command", "filePath", "path", "pattern", "url", "description"}) {
            String value = Models.string(input, key); if (!value.isEmpty()) return value;
        }
        return Models.string(state, "title");
    }
    public static boolean destructive(String command) {
        String s = command == null ? "" : command.toLowerCase(java.util.Locale.ROOT);
        return s.contains("rm -rf") || s.contains("git push --force") || s.contains("git reset --hard") || s.contains("chmod -r")
            || Pattern.compile("curl[\\s\\S]*\\|\\s*(?:ba)?sh\\b").matcher(s).find();
    }
    public static String errorText(JSONObject error) { return Models.string(Models.object(error, "data"), "message"); }
    private static JSONObject copy(JSONObject p) { try { return new JSONObject(p.toString()); } catch (org.json.JSONException ex) { return new JSONObject(); } }
    public static JSONObject idle() { JSONObject j = new JSONObject(); try { j.put("type", "idle"); } catch (org.json.JSONException ignored) { } return j; }
}
