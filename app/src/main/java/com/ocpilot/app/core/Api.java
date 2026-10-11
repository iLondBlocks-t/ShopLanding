package com.ocpilot.app.core;

import com.ocpilot.app.App;
import com.ocpilot.app.BuildConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Api {
    public interface Callback<T> { void success(T result); void error(ApiError error); }
    private interface Decoder<T> { T decode(Object json) throws Exception; }
    private interface ResponseDecoder<T> { T decode(HttpCall.Response response) throws Exception; }
    private final App app;
    private final String overrideServer;
    public Api(App app) { this(app, null); }
    public Api(App app, String server) { this.app = app; this.overrideServer = server; }
    private String base() { return overrideServer == null ? app.store.server() : overrideServer; }
    public static String encode(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (Exception ex) { throw new IllegalArgumentException("invalid_path"); }
    }
    public String downloadUrl(String project, String path) { return base() + "/gateway/projects/" + encode(project) + "/download?path=" + encode(path); }
    public static String userAgent() { return "OCPilot/" + BuildConfig.VERSION_NAME; }
    private static JSONObject obj(Object o) throws ApiError { if (!(o instanceof JSONObject)) throw new ApiError(ApiError.Kind.Parse); return (JSONObject) o; }
    private static JSONArray arr(Object o) throws ApiError { if (!(o instanceof JSONArray)) throw new ApiError(ApiError.Kind.Parse); return (JSONArray) o; }
    private <T> HttpCall request(String method, String path, JSONObject body, boolean auth, String project,
                                 int timeout, Decoder<T> decoder, Callback<T> callback) {
        return requestResponse(method, path, body, auth, project, timeout, response -> decoder.decode(response.json), callback);
    }
    private <T> HttpCall requestResponse(String method, String path, JSONObject body, boolean auth, String project,
                                 int timeout, ResponseDecoder<T> decoder, Callback<T> callback) {
        final HttpCall call = new HttpCall();
        final String server = base(), token = auth ? app.store.token() : "", device = auth ? app.store.deviceId() : "", payload = body == null ? null : body.toString();
        call.future(app.requests.submit(() -> {
            try {
                if (auth && token.isEmpty()) throw new ApiError(ApiError.Kind.Unauthorized);
                HttpCall.Response response = call.execute(server, path, method, payload, token, project, userAgent(), timeout, app::networkAvailable);
                T decoded = decoder.decode(response);
                app.main.post(() -> { if (auth && (!app.store.paired() || !server.equals(app.store.server()) || !device.equals(app.store.deviceId()))) return; if (!call.isCancelled()) app.safe(() -> callback.success(decoded)); });
            } catch (Exception | StackOverflowError ex) {
                ApiError error = ex instanceof ApiError ? (ApiError) ex : new ApiError(ApiError.Kind.Parse);
                app.main.post(() -> {
                    if (auth && (!server.equals(app.store.server()) || !device.equals(app.store.deviceId()))) return;
                    if (auth && error.kind == ApiError.Kind.Unauthorized) app.unauthorized(token);
                    if (!call.isCancelled()) app.safe(() -> callback.error(auth && error.kind == ApiError.Kind.Unauthorized && app.store.paired() && !token.equals(app.store.token()) ? new ApiError(ApiError.Kind.Client, 0, "credential_changed", "", 0) : error));
                });
            }
        }));
        return call;
    }
    private static boolean headerToken(String token) { if (token.isEmpty()) return false; for (int i = 0; i < token.length(); i++) if (token.charAt(i) <= 32 || token.charAt(i) >= 127) return false; return true; }
    private int timeout() { return app.store.readTimeout() * 1000; }
    public HttpCall health(Callback<Models.Health> cb) { return request("GET", "/health", null, false, null, timeout(), o -> Models.Health.fromJson(obj(o)), cb); }
    public HttpCall redeem(String code, String name, Callback<Models.Pairing> cb) {
        return request("POST", "/pair/redeem", json("code", code, "deviceName", name), false, null, timeout(), o -> {
            Models.Pairing p = Models.Pairing.fromJson(obj(o));
            if (p.deviceId.isEmpty() || !headerToken(p.deviceToken)) throw new ApiError(ApiError.Kind.Parse); return p;
        }, cb);
    }
    public HttpCall status(Callback<Models.Status> cb) { return request("GET", "/gateway/status", null, true, null, timeout(), o -> Models.Status.fromJson(obj(o)), cb); }
    public HttpCall devices(Callback<List<Models.Device>> cb) { return request("GET", "/gateway/devices", null, true, null, timeout(), o -> Models.list(arr(o), Models.Device::fromJson), cb); }
    public HttpCall revokeDevice(String id, Callback<Void> cb) { return request("DELETE", "/gateway/devices/" + encode(id), null, true, null, timeout(), o -> null, cb); }
    public HttpCall rotate(Callback<Models.Rotation> cb) {
        return request("POST", "/gateway/devices/rotate", json(), true, null, timeout(), o -> {
            Models.Rotation r = Models.Rotation.fromJson(obj(o)); if (!headerToken(r.deviceToken)) throw new ApiError(ApiError.Kind.Parse); return r;
        }, cb);
    }
    public HttpCall github(Callback<Models.Github> cb) { return request("GET", "/gateway/github", null, true, null, timeout(), o -> Models.Github.fromJson(obj(o)), cb); }
    public HttpCall saveGithub(String token, Callback<Models.Github> cb) { return request("PUT", "/gateway/github", json("token", token), true, null, timeout(), o -> Models.Github.fromJson(obj(o)), cb); }
    public HttpCall removeGithub(Callback<Void> cb) { return request("DELETE", "/gateway/github", null, true, null, timeout(), o -> null, cb); }
    public HttpCall repos(int page, Callback<List<Models.Repo>> cb) { return request("GET", "/gateway/github/repos?page=" + Math.max(1, page), null, true, null, timeout(), o -> Models.list(arr(o), Models.Repo::fromJson), cb); }
    public HttpCall createRepo(String name, boolean isPrivate, String description, Callback<Models.Repo> cb) {
        return request("POST", "/gateway/github/repos", json("name", name, "private", isPrivate, "description", description), true, null, timeout(), o -> Models.Repo.fromJson(obj(o)), cb);
    }
    public HttpCall projects(Callback<List<Models.Project>> cb) { return request("GET", "/gateway/projects", null, true, null, timeout(), o -> Models.list(arr(o), Models.Project::fromJson), cb); }
    public HttpCall createProject(String name, String repo, Boolean createPrivate, Callback<Models.Project> cb) {
        JSONObject body = json("name", name);
        try { if (repo != null && !repo.isEmpty()) body.put("repoFullName", repo);
            if (createPrivate != null) body.put("createRepo", json("private", createPrivate)); }
        catch (org.json.JSONException ignored) { }
        return request("POST", "/gateway/projects", body, true, null, timeout(), o -> Models.Project.fromJson(obj(o)), cb);
    }
    public HttpCall updateProject(String id, String name, Boolean archived, Callback<Models.Project> cb) {
        JSONObject body = json();
        try { if (name != null) body.put("name", name); if (archived != null) body.put("archived", archived); } catch (org.json.JSONException ignored) { }
        return request("PATCH", "/gateway/projects/" + encode(id), body, true, null, timeout(), o -> Models.Project.fromJson(obj(o)), cb);
    }
    public HttpCall deleteProject(String id, Callback<Void> cb) { return request("DELETE", "/gateway/projects/" + encode(id) + "?confirm=true", null, true, null, timeout(), o -> null, cb); }
    public HttpCall git(String id, Callback<Models.Git> cb) { return request("GET", "/gateway/projects/" + encode(id) + "/git", null, true, null, timeout(), o -> Models.Git.fromJson(obj(o)), cb); }
    public HttpCall artifacts(String id, Callback<List<Models.Artifact>> cb) { return request("GET", "/gateway/projects/" + encode(id) + "/artifacts", null, true, null, timeout(), o -> Models.list(arr(o), Models.Artifact::fromJson), cb); }
    public HttpCall oc(String method, String path, String project, JSONObject body, int readTimeout, Callback<Object> cb) {
        if (!path.startsWith("/") || path.contains("://") || path.contains("..")) throw new IllegalArgumentException("invalid_oc_path");
        return request(method, "/oc" + path, body, true, project, readTimeout, o -> o, cb);
    }
    public HttpCall sessions(String project, Callback<List<Models.Session>> cb) { return request("GET", "/oc/session", null, true, project, timeout(), o -> Models.list(arr(o), Models.Session::fromJson), cb); }
    public HttpCall createSession(String project, Callback<Models.Session> cb) { return request("POST", "/oc/session", json(), true, project, timeout(), o -> Models.Session.fromJson(obj(o)), cb); }
    public HttpCall session(String project, String session, Callback<Models.Session> cb) { return request("GET", sessionPath(session), null, true, project, timeout(), o -> Models.Session.fromJson(obj(o)), cb); }
    public HttpCall sessionStatuses(String project, Callback<JSONObject> cb) { return request("GET", "/oc/session/status", null, true, project, timeout(), Api::obj, cb); }
    public HttpCall messages(String project, String session, String before, Callback<JSONArray> cb) {
        String path = sessionPath(session) + "/message?limit=400" + (before == null || before.isEmpty() ? "" : "&before=" + encode(before));
        return request("GET", path, null, true, project, timeout(), Api::arr, cb);
    }
    /** before is the opaque X-Next-Cursor header, NOT a message ID. */
    public HttpCall messagesPage(String project, String session, String before, Callback<Models.MessagesPage> cb) {
        String path = sessionPath(session) + "/message?limit=400" + (before == null || before.isEmpty() ? "" : "&before=" + encode(before));
        return requestResponse("GET", path, null, true, project, timeout(), r -> new Models.MessagesPage(arr(r.json), r.nextCursor), cb);
    }
    private JSONObject promptBody(String prompt, JSONObject model, String messageId) {
        JSONArray parts = new JSONArray().put(json("type", "text", "text", prompt)); JSONObject body = json("parts", parts);
        try { if (model != null && !Models.string(model, "providerID").isEmpty() && !Models.string(model, "modelID").isEmpty()) body.put("model", json("providerID", Models.string(model, "providerID"), "modelID", Models.string(model, "modelID"))); }
        catch (org.json.JSONException ignored) { }
        try { if (messageId != null && !messageId.isEmpty()) body.put("messageID", messageId); } catch (org.json.JSONException ignored) { }
        return body;
    }
    public HttpCall promptAsync(String project, String session, String prompt, JSONObject model, String messageId, Callback<Void> cb) {
        return request("POST", sessionPath(session) + "/prompt_async", promptBody(prompt, model, messageId), true, project, timeout(), o -> null, cb);
    }
    public HttpCall promptSync(String project, String session, String prompt, JSONObject model, String messageId, Callback<JSONObject> cb) {
        return request("POST", sessionPath(session) + "/message", promptBody(prompt, model, messageId), true, project, 0, Api::obj, cb);
    }
    public HttpCall abort(String project, String session, Callback<Boolean> cb) { return request("POST", sessionPath(session) + "/abort", json(), true, project, timeout(), o -> {
        if (!(o instanceof Boolean)) throw new ApiError(ApiError.Kind.Parse); return (Boolean) o;
    }, cb); }
    public HttpCall pendingPermissions(String project, Callback<JSONArray> cb) { return request("GET", "/oc/permission", null, true, project, timeout(), Api::arr, cb); }
    public HttpCall permission(String project, String request, String reply, Callback<Boolean> cb) {
        return request("POST", "/oc/permission/" + encode(request) + "/reply", json("reply", reply), true, project, timeout(), o -> {
            if (!(o instanceof Boolean)) throw new ApiError(ApiError.Kind.Parse); return (Boolean) o;
        }, cb);
    }
    public HttpCall permissionLegacy(String project, String session, String permission, String response, Callback<Boolean> cb) {
        return request("POST", sessionPath(session) + "/permissions/" + encode(permission), json("response", response), true, project, timeout(), o -> {
            if (!(o instanceof Boolean)) throw new ApiError(ApiError.Kind.Parse); return (Boolean) o;
        }, cb);
    }
    public HttpCall diff(String project, String session, Callback<List<Models.Diff>> cb) { return request("GET", sessionPath(session) + "/diff", null, true, project, timeout(), o -> Models.list(arr(o), Models.Diff::fromJson), cb); }
    public HttpCall providers(Callback<List<Models.ModelChoice>> cb) { return request("GET", "/oc/config/providers", null, true, null, timeout(), o -> Models.ModelChoice.fromProviders(obj(o)), cb); }
    private static String sessionPath(String id) { return "/oc/session/" + encode(id); }
    public static JSONObject json(Object... pairs) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i + 1 < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), pairs[i + 1]); }
        catch (org.json.JSONException ignored) { }
        return result;
    }
}
