package com.ocpilot.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.ocpilot.app.App;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.R;
import com.ocpilot.app.core.Api;
import com.ocpilot.app.core.ApiError;
import com.ocpilot.app.core.Events;
import com.ocpilot.app.core.HttpCall;
import com.ocpilot.app.core.Models;
import com.ocpilot.app.core.Notifier;
import com.ocpilot.app.core.Sse;
import com.ocpilot.app.core.TaskLog;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WorkspaceScreen extends Screen {
    /** Application-owned controller: Views detach while foreground work and SSE continue. */
    public static final class Engine {
        public interface Listener { void changed(); }
        public static final class HistoryPage {
            public final List<Events.Row> rows; public final String cursor;
            HistoryPage(List<Events.Row> rows, String cursor) { this.rows = rows; this.cursor = cursor; }
        }
        private final App app;
        public final Models.Project project;
        public String sessionId, latestCursor = "";
        public Events events;
        public List<Models.Session> sessions = new ArrayList<>();
        public List<Models.ModelChoice> models = new ArrayList<>();
        public Sse.State connection = Sse.State.STOPPED;
        public int reconnectAttempt;
        public ApiError error;
        public boolean ready, syncing, sending, journaling;
        private boolean closed, sessionsLoading, modelsLoaded, accepted, sawBusy, watching, syncAgain, uncertain, refreshScheduled, serverIdleConfirmed;
        private int sessionEpoch, streamEpoch, statusRevision, permissionRevision, pendingSync, unknownIdleSnapshots;
        private boolean syncOk;
        private Sse stream;
        private TaskLog.Entry current;
        private final ArrayDeque<TaskLog.Entry> queue = new ArrayDeque<>();
        private final List<Listener> listeners = new ArrayList<>();
        private final List<HttpCall> calls = new ArrayList<>();
        private final Set<String> durableTasks = new HashSet<>();
        private final Set<String> permissionNotifications = new HashSet<>(), answeredPermissions = new HashSet<>(), replying = new HashSet<>();
        private long lastLogWrite, observedStarted;
        private final Runnable flush = new Runnable() { @Override public void run() { refreshScheduled = false; if (!closed) for (Listener l : new ArrayList<>(listeners)) app.safe(l::changed); } };
        private final Runnable reconcileLater = new Runnable() { @Override public void run() { if (!closed && current != null) { sync(); app.main.postDelayed(this, 5000); } } };
        public Engine(App app, Models.Project project) {
            this.app = app; this.project = project; sessionId = app.store.session(project.id); events = new Events(sessionId); restoreTasks();
        }
        public void attach(Listener listener) {
            if (!listeners.contains(listener)) listeners.add(listener);
            String stored = app.store.session(project.id);
            if (!isActive() && !stored.equals(sessionId)) chooseSession(stored);
            loadSessions(); if (!modelsLoaded) loadModels(); ensureStream(); changed();
        }
        public void detach(Listener listener) { listeners.remove(listener); if (listeners.isEmpty() && !hasServerWork()) stopStream(); }
        public void pause() { if (!hasServerWork()) stopStream(); notifyPermission(); }
        public void resume() { ensureStream(); if (current != null) sync(); }
        public boolean isActive() { return hasServerWork() || !queue.isEmpty(); }
        private boolean hasServerWork() { return sending || current != null || events.busy() || !events.permissions().isEmpty(); }
        public TaskLog.Entry current() { return current; }
        public int queuedCount() { return queue.size(); }
        public boolean replying(String id) { return replying.contains(id); }
        public long started() { return current == null ? observedStarted : current.started; }
        private void restoreTasks() {
            queue.clear(); durableTasks.clear(); current = null;
            List<TaskLog.Entry> entries = app.tasks.list(project.id); Collections.reverse(entries);
            for (TaskLog.Entry e : entries) if (e.session.equals(sessionId)) {
                if (e.state.equals("queued")) { queue.add(e); durableTasks.add(e.id); }
                else if (e.state.equals("working") || e.state.equals("waiting_permission") || e.state.equals("connecting")) current = e;
            }
            uncertain = current != null && current.state.equals("connecting"); accepted = current != null && !uncertain; sawBusy = false; unknownIdleSnapshots = 0; events.queued(queue.size());
        }
        private void changed() {
            events.queued(queue.size());
            if (!refreshScheduled) { refreshScheduled = true; app.main.postDelayed(flush, 100); }
        }
        private <T> Api.Callback<T> scoped(java.util.function.Consumer<T> success) {
            int epoch = sessionEpoch;
            return new Api.Callback<T>() {
                @Override public void success(T result) { if (!closed && epoch == sessionEpoch) { success.accept(result); changed(); } }
                @Override public void error(ApiError e) { if (!closed && epoch == sessionEpoch) { error = e; changed(); } }
            };
        }
        private HttpCall track(HttpCall call) { if (calls.size() >= 64) calls.removeIf(HttpCall::isDone); calls.add(call); return call; }
        public void loadSessions() {
            if (closed || sessionsLoading) return; sessionsLoading = true;
            track(app.api.sessions(project.id, new Api.Callback<List<Models.Session>>() {
                @Override public void success(List<Models.Session> list) {
                    sessionsLoading = false; if (closed) return; sessions = list;
                    if (sessionId.isEmpty() && !list.isEmpty()) chooseSession(list.get(0).id);
                    else if (!sessionId.isEmpty()) { ensureStream(); sync(); }
                    changed();
                }
                @Override public void error(ApiError e) { sessionsLoading = false; if (!closed) { error = e; changed(); } }
            }));
        }
        private void loadModels() {
            track(app.api.providers(new Api.Callback<List<Models.ModelChoice>>() {
                @Override public void success(List<Models.ModelChoice> result) { if (!closed) { models = result; modelsLoaded = true; changed(); } }
                @Override public void error(ApiError e) { if (!closed) { error = e; changed(); } }
            }));
        }
        public void createSession() {
            if (isActive()) { error = new ApiError(ApiError.Kind.Client, 0, "busy_session", "", 0); changed(); return; }
            track(app.api.createSession(project.id, scoped(s -> {
                if (s.id.isEmpty()) { error = new ApiError(ApiError.Kind.Parse); return; }
                sessions.add(0, s); chooseSession(s.id);
            })));
        }
        public void chooseSession(String id) {
            if (isActive()) { error = new ApiError(ApiError.Kind.Client, 0, "busy_session", "", 0); changed(); return; }
            stopStream(); for (HttpCall call : calls) call.cancel(); calls.clear(); app.main.removeCallbacks(reconcileLater);
            sessionsLoading = false;
            sessionEpoch++; sessionId = id; app.store.session(project.id, id); events = new Events(id); ready = false; syncing = false;
            sending = false; serverIdleConfirmed = false; syncAgain = false; latestCursor = ""; error = null; permissionRevision = 0; statusRevision = 0; answeredPermissions.clear(); permissionNotifications.clear(); replying.clear();
            restoreTasks(); loadModels(); ensureStream(); if (!id.isEmpty()) sync(); changed();
        }
        private void ensureStream() {
            if (closed || stream != null || sessionId.isEmpty() || !app.store.paired()) return;
            if (listeners.isEmpty() && !hasServerWork()) return;
            if (!app.foreground && !app.hasWatch(project.id)) return;
            int epoch = ++streamEpoch;
            stream = new Sse(app, project.id, new Sse.Listener() {
                private boolean currentStream() { return !closed && epoch == streamEpoch; }
                @Override public void state(Sse.State state, int attempt) {
                    if (!currentStream()) return; connection = state; reconnectAttempt = attempt; events.live(state == Sse.State.LIVE);
                    if (state != Sse.State.LIVE) ready = false; changed();
                }
                @Override public void connected(boolean reconnect) { if (currentStream()) { error = null; sync(); } }
                @Override public void error(ApiError e) { if (currentStream()) { error = e; changed(); } }
                @Override public void event(JSONObject event) {
                    if (!currentStream()) return;
                    String type = Models.string(event, "type"); List<JSONObject> previousPermissions = events.permissions(); boolean previousBusy = events.busy(); boolean applied = events.apply(event);
                    if (applied) {
                        if (previousBusy != events.busy()) statusRevision++;
                        if (!previousPermissions.isEmpty() && events.permissions().isEmpty() && (type.equals("session.error") || type.equals("session.idle") || type.equals("session.status"))) {
                            permissionRevision++; for (JSONObject permission : previousPermissions) answeredPermissions.add(Models.string(permission,"id"));
                        }
                        if (type.equals("session.status") || type.equals("session.idle") || type.equals("session.error")) statusRevision++;
                        if (type.equals("session.idle")) serverIdleConfirmed = true;
                        if (type.equals("session.status")) serverIdleConfirmed = Models.string(Models.object(Models.object(event, "properties"), "status"), "type").equals("idle");
                        if (type.equals("permission.asked") || type.equals("permission.replied")) { permissionRevision++; statusRevision++; }
                        if (type.equals("permission.replied")) answeredPermissions.add(Models.string(Models.object(event, "properties"), "requestID"));
                        if (type.equals("session.error") || type.equals("session.idle") || type.equals("session.status")) { reconcile(); if (type.equals("session.error")) sync(); }
                        else updateObserved();
                        changed();
                    } else if (type.equals("message.part.delta") && sessionId.equals(Models.string(Models.object(event, "properties"), "sessionID"))) sync();
                }
            }); stream.start();
        }
        private void stopStream() { ++streamEpoch; if (stream != null) stream.stop(); stream = null; connection = Sse.State.STOPPED; events.live(false); ready = false; }
        public void restartStream() { stopStream(); ensureStream(); }
        public void retry() { error = null; loadSessions(); modelsLoaded = false; loadModels(); restartStream(); sync(); changed(); }
        public void sync() {
            if (closed || sessionId.isEmpty()) return;
            if (syncing) { syncAgain = true; return; }
            syncing = true; pendingSync = 4; syncOk = true; final int epoch = sessionEpoch, revision = statusRevision, permissionVersion = permissionRevision;
            track(app.api.session(project.id, sessionId, syncCallback(epoch, false, s -> { boolean found = false; for (int i = 0; i < sessions.size(); i++) if (sessions.get(i).id.equals(s.id)) { sessions.set(i, s); found = true; break; } if (!found && !s.id.isEmpty()) sessions.add(0, s); })));
            track(app.api.sessionStatuses(project.id, syncCallback(epoch, false, statuses -> {
                if (revision == statusRevision) { JSONObject status = statuses.optJSONObject(sessionId); events.status(status == null ? Events.idle() : status); serverIdleConfirmed = status == null || Models.string(status, "type").equals("idle"); }
            })));
            track(app.api.messagesPage(project.id, sessionId, null, syncCallback(epoch, false, page -> { events.mergeHistory(page.entries); latestCursor = page.nextCursor; })));
            track(app.api.pendingPermissions(project.id, syncCallback(epoch, true, list -> {
                JSONArray safe = new JSONArray();
                for (int i = 0; i < list.length(); i++) { JSONObject p = list.optJSONObject(i); if (p != null && !answeredPermissions.contains(Models.string(p, "id"))) safe.put(p); }
                if (permissionVersion == permissionRevision) events.setPermissions(safe); else events.mergePermissions(safe);
            })));
        }
        private <T> Api.Callback<T> syncCallback(int epoch, boolean optional, java.util.function.Consumer<T> result) {
            return new Api.Callback<T>() {
                @Override public void success(T value) { if (closed || epoch != sessionEpoch) return; result.accept(value); finishSync(); }
                @Override public void error(ApiError e) {
                    if (closed || epoch != sessionEpoch) return;
                    if (!(optional && (e.status == 404 || e.status == 405))) { error = e; syncOk = false; }
                    finishSync();
                }
            };
        }
        private void finishSync() {
            if (--pendingSync > 0) return;
            syncing = false;
            if (syncOk && uncertain && current != null) {
                if (events.hasMessage(current.messageId) || events.assistantFinished(current.messageId) || events.assistantError(current.messageId) != null) { uncertain = false; accepted = true; unknownIdleSnapshots = 0; }
                else if (serverIdleConfirmed && ++unknownIdleSnapshots >= 2) { uncertain = false; events.failure(app.getString(R.string.submission_unconfirmed)); finishCurrent("failed"); }
                else if (!serverIdleConfirmed) unknownIdleSnapshots = 0;
            }
            ready = syncOk && connection == Sse.State.LIVE; if (syncOk) error = null;
            reconcile(); changed(); if (syncAgain) { syncAgain = false; sync(); }
        }
        public void earlier(String cursor, Api.Callback<HistoryPage> result) {
            if (cursor == null || cursor.isEmpty()) { result.success(new HistoryPage(new ArrayList<>(), "")); return; }
            int epoch = sessionEpoch;
            track(app.api.messagesPage(project.id, sessionId, cursor, new Api.Callback<Models.MessagesPage>() {
                @Override public void success(Models.MessagesPage page) {
                    if (closed || epoch != sessionEpoch) return;
                    Events history = new Events(sessionId); history.mergeHistory(page.entries); result.success(new HistoryPage(history.rows(), page.nextCursor));
                }
                @Override public void error(ApiError e) { if (!closed && epoch == sessionEpoch) result.error(e); }
            }));
        }
        public boolean submit(String prompt) { return submit(prompt, null); }
        public boolean submit(String prompt, Runnable persisted) {
            if (sessionId.isEmpty() || prompt.trim().isEmpty() || closed || journaling) return false;
            if (prompt.length() > 131072) { error = new ApiError(ApiError.Kind.Client, 0, "payload_too_large", "", 0); changed(); return false; }
            TaskLog.Entry entry = app.tasks.add(project.id, sessionId, prompt, app.store.model(project.id), true);
            if (entry == null) { error = new ApiError(ApiError.Kind.Client, 0, "queue_full", "", 0); changed(); return false; }
            queue.add(entry); journaling = true; changed(); app.taskUpdated();
            app.tasks.durable(app.main, new Api.Callback<Void>() {
                @Override public void success(Void ignored) {
                    if (closed) return; journaling = false; durableTasks.add(entry.id); if (persisted != null) app.safe(persisted); reconcile(); changed();
                }
                @Override public void error(ApiError e) {
                    if (closed) return; journaling = false; queue.remove(entry); app.tasks.update(entry.id, "failed"); error = e; changed(); app.taskUpdated();
                }
            });
            return true;
        }
        private void dispatch(TaskLog.Entry entry) {
            current = entry; current.started = System.currentTimeMillis(); current.state = "working"; app.tasks.begin(entry.id);
            sending = true; accepted = false; uncertain = false; unknownIdleSnapshots = 0; sawBusy = false; serverIdleConfirmed = false; events.sent(); startWatch(); final int epoch = sessionEpoch;
            Api.Callback<Void> acceptedCallback = new Api.Callback<Void>() {
                @Override public void success(Void ignored) {
                    if (closed || epoch != sessionEpoch || current == null || !current.id.equals(entry.id)) return;
                    sending = false; accepted = true; changed(); app.main.removeCallbacks(reconcileLater); app.main.postDelayed(reconcileLater, 1000);
                }
                @Override public void error(ApiError e) {
                    if (closed || epoch != sessionEpoch) return;
                    if (e.status == 404 || e.status == 405) {
                        track(app.api.promptSync(project.id, sessionId, entry.prompt, entry.model, entry.messageId, new Api.Callback<JSONObject>() {
                            @Override public void success(JSONObject response) {
                                if (closed || epoch != sessionEpoch) return; events.mergeHistory(new JSONArray().put(response)); sending = false; accepted = true; sawBusy = true; sync();
                            }
                            @Override public void error(ApiError err) { submissionFailed(entry, err); }
                        }));
                    } else submissionFailed(entry, e);
                }
            };
            track(app.api.promptAsync(project.id, sessionId, entry.prompt, entry.model, entry.messageId, acceptedCallback));
        }
        private void submissionFailed(TaskLog.Entry entry, ApiError e) {
            if (closed || current == null || !current.id.equals(entry.id)) return;
            sending = false; error = e;
            if (events.hasMessage(entry.messageId)) { accepted = true; sync(); app.main.removeCallbacks(reconcileLater); app.main.postDelayed(reconcileLater, 1000); }
            else if (e.kind == ApiError.Kind.Network || e.kind == ApiError.Kind.Server || e.kind == ApiError.Kind.Parse) { uncertain = true; unknownIdleSnapshots = 0; setTaskState("connecting"); sync(); app.main.removeCallbacks(reconcileLater); app.main.postDelayed(reconcileLater, 1000); }
            else { events.failure(e.message(app)); finishCurrent("failed"); }
            changed();
        }
        private void setTaskState(String state) {
            if (current != null && !current.state.equals(state)) { current.state = state; app.tasks.update(current.id, state); app.taskUpdated(); }
        }
        private void updateObserved() {
            if (events.busy()) { serverIdleConfirmed = false; sawBusy = true; if (observedStarted == 0) observedStarted = System.currentTimeMillis(); startWatch(); }
            if (!events.permissions().isEmpty()) { setTaskState("waiting_permission"); notifyPermission(); } else if (events.busy()) setTaskState("working");
            if (current != null) {
                List<JSONObject> tools = events.toolsForUser(current.messageId);
                for (JSONObject tool : tools) app.tasks.tool(current.id, tool);
                if (System.currentTimeMillis() - lastLogWrite >= 1000) { lastLogWrite = System.currentTimeMillis(); app.tasks.toolLog(current.id, tools); app.taskUpdated(); }
            }
        }
        private void reconcile() {
            if (closed) return; updateObserved();
            if (current != null) {
                JSONObject assistantError = events.assistantError(current.messageId);
                if (assistantError != null) {
                    if (Models.string(assistantError, "name").equals("MessageAbortedError")) events.abortAcknowledged(); else events.failure(Events.errorText(assistantError));
                }
                Events.State state = events.state();
                if (state == Events.State.FAILED) finishCurrent("failed");
                else if (state == Events.State.CANCELLED) finishCurrent("cancelled");
                else if (!sending && accepted && !events.busy() && events.permissions().isEmpty() && events.statusKnown()
                        && (sawBusy || events.assistantFinished(current.messageId))) finishCurrent("completed");
            }
            if (current == null && !sending && !events.busy() && events.permissions().isEmpty()) {
                if (ready && serverIdleConfirmed && !queue.isEmpty() && durableTasks.contains(queue.peekFirst().id) && (app.foreground || app.hasWatch(project.id))) dispatch(queue.removeFirst());
                else if (watching && queue.isEmpty()) { Notifier.update(app, project.id, sessionId, project.name, events.state() == Events.State.FAILED ? R.string.notification_failed : events.state() == Events.State.CANCELLED ? R.string.notification_cancelled : R.string.notification_completed); watching = false; app.watch(project.id, sessionId, project.name, false); observedStarted = 0; }
            }
            if ((!app.foreground || listeners.isEmpty()) && !hasServerWork() && queue.isEmpty()) stopStream();
        }
        private void finishCurrent(String state) {
            if (current == null) return;
            app.tasks.toolLog(current.id, events.toolsForUser(current.messageId)); app.tasks.update(current.id, state);
            Notifier.update(app, project.id, sessionId, project.name, state.equals("failed") ? R.string.notification_failed : state.equals("completed") ? R.string.notification_completed : R.string.notification_cancelled);
            current = null; sending = false; accepted = false; uncertain = false; app.main.removeCallbacks(reconcileLater); app.taskUpdated();
            if (queue.isEmpty() && !events.busy()) { watching = false; app.watch(project.id, sessionId, project.name, false); }
        }
        private void startWatch() { if (!watching && (app.foreground || app.hasWatch(project.id))) { watching = app.watch(project.id, sessionId, project.name, true); if (!watching) { error = new ApiError(ApiError.Kind.Client, 0, "foreground_unavailable", "", 0); changed(); } } }
        private void notifyPermission() {
            if (app.foreground) return;
            for (JSONObject permission : events.permissions()) {
                String id = Models.string(permission, "id");
                if (permissionNotifications.add(id)) Notifier.update(app, project.id, sessionId, project.name, R.string.notification_permission);
            }
        }
        public void reply(String id, String response) {
            if (!replying.add(id)) return; changed();
            Api.Callback<Boolean> callback = new Api.Callback<Boolean>() {
                @Override public void success(Boolean acknowledged) {
                    replying.remove(id); if (closed) return;
                    if (Boolean.TRUE.equals(acknowledged)) { answeredPermissions.add(id); events.permissionAnswered(id); permissionRevision++; error = null; reconcile(); }
                    else error = new ApiError(ApiError.Kind.Client); changed();
                }
                @Override public void error(ApiError e) {
                    if (closed) return;
                    if (e.status == 404 || e.status == 405) track(app.api.permissionLegacy(project.id, sessionId, id, response, new Api.Callback<Boolean>() {
                        @Override public void success(Boolean result) { callbackSuccess(result); }
                        @Override public void error(ApiError error) { if (closed) return; replying.remove(id); Engine.this.error = error; changed(); }
                        private void callbackSuccess(Boolean result) { if (closed) return; replying.remove(id); if (Boolean.TRUE.equals(result)) { answeredPermissions.add(id); events.permissionAnswered(id); permissionRevision++; error = null; reconcile(); } else error = new ApiError(ApiError.Kind.Client); changed(); }
                    }));
                    else { replying.remove(id); error = e; changed(); }
                }
            };
            track(app.api.permission(project.id, id, response, callback));
        }
        public void stop() {
            if (sessionId.isEmpty()) return;
            track(app.api.abort(project.id, sessionId, scoped(acknowledged -> {
                if (!Boolean.TRUE.equals(acknowledged)) { error = new ApiError(ApiError.Kind.Client); return; }
                for (TaskLog.Entry entry : queue) app.tasks.update(entry.id, "cancelled"); queue.clear();
                for (JSONObject permission : events.permissions()) answeredPermissions.add(Models.string(permission, "id")); permissionRevision++; events.abortAcknowledged(); serverIdleConfirmed = true; statusRevision++; finishCurrent("cancelled"); app.watch(project.id, sessionId, project.name, false); watching = false; app.taskUpdated(); sync();
            })));
        }
        public String retryPrompt() {
            for (TaskLog.Entry entry : app.tasks.list(project.id)) if (entry.session.equals(sessionId)) return entry.prompt;
            return events.lastUserText();
        }
        public void close() {
            if (closed) return; closed = true; stopStream(); for (HttpCall call : calls) call.cancel(); calls.clear(); listeners.clear();
            app.main.removeCallbacks(flush); app.main.removeCallbacks(reconcileLater); app.watch(project.id, sessionId, project.name, false);
        }
    }

    private Engine engine;
    private ListView chat;
    private ChatAdapter adapter;
    private EditText input;
    private TextView connection, status, queueCount, emptyChat;
    private Button send, stop, retry, moreMessages, earlier, latest, modelButton, continueButton;
    private LinearLayout permissionHost, streamBanner;
    private boolean atBottom = true, olderMode, loadingEarlier;
    private int historyOffset;
    private String historyCursor = "";
    private List<Events.Row> historyRows;
    private final Set<String> expanded = new HashSet<>();
    private final Engine.Listener engineListener = this::render;
    private final Runnable timer = new Runnable() { @Override public void run() { if (visible) { renderStatus(); app.main.postDelayed(this, 1000); } } };
    public WorkspaceScreen(MainActivity activity) { super(activity); }
    @Override public int rootTab() { return 2; }
    @Override public View build(Context c) {
        Models.Project project = app.selected(); if (project == null) return shell(c, Widgets.empty(c, R.string.select_project), R.string.workspace, false);
        engine = app.engine(project); LinearLayout body = Widgets.column(c); body.setPaddingRelative(Theme.dp(c, 12), 0, Theme.dp(c, 12), Theme.dp(c, 8));
        LinearLayout heading = Widgets.row(c); Widgets.weighted(heading, Widgets.section(c, R.string.current_project));
        ((TextView) heading.getChildAt(0)).setText(project.name); connection = Widgets.chip(c, R.string.connecting, Theme.of(c).muted); heading.addView(connection); Widgets.add(body, heading);
        LinearLayout controls = Widgets.row(c); Widgets.weighted(controls, Widgets.ghost(c, R.string.sessions, this::sessions)); modelButton = Widgets.ghost(c, R.string.model, this::models); Widgets.weighted(controls, modelButton);
        Widgets.weighted(controls, Widgets.ghost(c, R.string.more_actions, this::overflow)); Widgets.add(body, controls);
        streamBanner = Widgets.column(c); body.addView(streamBanner); permissionHost = Widgets.column(c); body.addView(permissionHost);
        LinearLayout history = Widgets.row(c); earlier = Widgets.ghost(c, R.string.show_earlier, this::earlier); latest = Widgets.ghost(c, R.string.show_latest, () -> { olderMode = false; historyRows = null; historyOffset = 0; atBottom = true; render(); });
        Widgets.weighted(history, earlier); Widgets.weighted(history, latest); body.addView(history);
        FrameLayout chatHost = new FrameLayout(c); chat = new ListView(c); chat.setDivider(null); chat.setDividerHeight(0); chat.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
        adapter = new ChatAdapter(); chat.setAdapter(adapter); chatHost.addView(chat);
        emptyChat = Widgets.muted(c, engine.sessionId.isEmpty() ? R.string.no_session : R.string.no_messages); emptyChat.setGravity(android.view.Gravity.CENTER); chatHost.addView(emptyChat, new FrameLayout.LayoutParams(-1, -1)); chat.setEmptyView(emptyChat);
        body.addView(chatHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        chat.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int first, int count, int total) { atBottom = first + count >= total && !olderMode && historyOffset == 0; if (atBottom && moreMessages != null) moreMessages.setVisibility(View.GONE); }
        });
        moreMessages = Widgets.ghost(c, R.string.new_messages, () -> { olderMode = false; historyRows = null; historyOffset = 0; atBottom = true; render(); chat.setSelection(Math.max(0, adapter.getCount() - 1)); }); moreMessages.setVisibility(View.GONE); body.addView(moreMessages);
        status = Widgets.text(c, R.string.ready); queueCount = Widgets.muted(c, ""); body.addView(status); body.addView(queueCount);
        LinearLayout inputRow = Widgets.row(c); input = Widgets.field(c, R.string.prompt_hint, false);
        input.setSingleLine(false); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); input.setMinLines(2); input.setMaxLines(4);
        input.setText(app.store.draft(project.id));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { app.store.draft(project.id, s.toString()); }
            @Override public void afterTextChanged(Editable e) { }
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); send = Widgets.primary(c, R.string.send, () -> {
            String text = input.getText().toString(); engine.submit(text, () -> { if (visible && input.getText().toString().equals(text)) input.setText(""); });
        }); inputRow.addView(send); body.addView(inputRow);
        LinearLayout actions = Widgets.row(c); stop = Widgets.ghost(c, R.string.stop, () -> engine.stop()); retry = Widgets.ghost(c, R.string.retry, () -> engine.submit(engine.retryPrompt()));
        Widgets.weighted(actions, stop); Widgets.weighted(actions, retry); continueButton = Widgets.ghost(c, R.string.continue_task, () -> engine.submit(c.getString(R.string.continue_message))); Widgets.weighted(actions, continueButton); body.addView(actions);
        render(); return shell(c, body, R.string.workspace, false);
    }
    @Override public void onShow() { super.onShow(); if (engine != null) { engine.attach(engineListener); timer.run(); } }
    @Override public void onHide() { loadingEarlier = false; app.main.removeCallbacks(timer); if (engine != null) { app.store.draft(engine.project.id, input == null ? app.store.draft(engine.project.id) : input.getText().toString()); engine.detach(engineListener); } super.onHide(); }
    @Override public void onPause() { if (engine != null) engine.pause(); }
    @Override public void onResume() { if (engine != null) engine.resume(); }
    private void sessions() {
        List<String> names = new ArrayList<>(); names.add(activity.getString(R.string.new_session));
        for (Models.Session s : engine.sessions) names.add(s.title.isEmpty() ? s.id : s.title);
        keep(Widgets.dialog(activity).setTitle(R.string.sessions).setItems(names.toArray(new String[0]), (d, index) -> {
            if (index == 0) engine.createSession(); else { engine.chooseSession(engine.sessions.get(index - 1).id); olderMode = false; historyRows = null; historyOffset = 0; }
        }).create()).show();
    }
    private void models() {
        List<String> names = new ArrayList<>(); names.add(activity.getString(R.string.server_default));
        for (Models.ModelChoice m : engine.models) names.add(activity.getString(R.string.model_label, m.name, m.providerID));
        keep(Widgets.dialog(activity).setTitle(R.string.model).setItems(names.toArray(new String[0]), (d, index) -> {
            app.store.model(engine.project.id, index == 0 ? new JSONObject() : engine.models.get(index - 1).toJson()); renderStatus();
        }).create()).show();
        if (engine.models.isEmpty()) Widgets.toast(activity, R.string.no_models);
    }
    private void overflow() {
        String[] options = { activity.getString(R.string.diff), activity.getString(R.string.stop), activity.getString(R.string.clear_view) };
        keep(Widgets.dialog(activity).setTitle(R.string.more_actions).setItems(options, (d, index) -> {
            if (index == 0) openDiff(null); else if (index == 1) engine.stop();
            else { engine.events.clearView(); olderMode = false; historyRows = null; historyOffset = 0; render(); Widgets.toast(activity, R.string.clear_view_note); }
        }).create()).show();
    }
    private void openDiff(String file) { if (!engine.sessionId.isEmpty()) activity.navigator.push(new DiffScreen(activity, engine.project.id, engine.sessionId, file)); else Widgets.toast(activity, R.string.no_session); }
    private void earlier() {
        if (loadingEarlier) return; List<Events.Row> rows = olderMode ? historyRows : engine.events.rows();
        int start = Math.max(0, rows.size() - 400 - historyOffset);
        if (start > 0) { historyOffset = Math.min(rows.size() - 1, historyOffset + 400); atBottom = false; render(); return; }
        String cursor = olderMode ? historyCursor : engine.latestCursor;
        if (cursor.isEmpty()) return; loadingEarlier = true; earlier.setEnabled(false);
        engine.earlier(cursor, new Api.Callback<Engine.HistoryPage>() {
            @Override public void success(Engine.HistoryPage page) { loadingEarlier = false; if (!visible) return; olderMode = true; historyRows = page.rows; historyCursor = page.cursor; historyOffset = 0; earlier.setEnabled(true); atBottom = false; render(); }
            @Override public void error(ApiError error) { loadingEarlier = false; if (visible) { earlier.setEnabled(true); showError(error); } }
        });
    }
    private void render() {
        if (engine == null || adapter == null) return; emptyChat.setText(engine.sessionId.isEmpty() ? R.string.no_session : R.string.no_messages); boolean follow = atBottom && !olderMode && historyOffset == 0;
        List<Events.Row> all = olderMode && historyRows != null ? historyRows : engine.events.rows();
        int end = Math.max(0, all.size() - historyOffset), start = Math.max(0, end - 400);
        adapter.rows = new ArrayList<>(all.subList(start, end)); adapter.notifyDataSetChanged();
        earlier.setVisibility(start > 0 || !(olderMode ? historyCursor : engine.latestCursor).isEmpty() ? View.VISIBLE : View.GONE);
        latest.setVisibility(olderMode || historyOffset > 0 ? View.VISIBLE : View.GONE);
        if (follow) chat.post(() -> { if (visible && !olderMode) chat.setSelection(Math.max(0, adapter.getCount() - 1)); });
        else if (visible) moreMessages.setVisibility(View.VISIBLE);
        streamBanner.removeAllViews();
        if (engine.error != null || engine.connection == Sse.State.RECONNECTING) {
            String text = engine.error == null ? activity.getString(R.string.reconnecting, engine.reconnectAttempt) : engine.error.message(activity);
            LinearLayout banner = Widgets.banner(activity, text, Theme.of(activity).warning);
            if (engine.error != null && !engine.error.detail.isEmpty()) Widgets.add(banner, Widgets.muted(activity, engine.error.detail));
            banner.addView(Widgets.ghost(activity, R.string.retry, () -> engine.retry())); Widgets.add(streamBanner, banner);
        }
        renderPermission(); renderStatus();
    }
    private void renderStatus() {
        if (engine == null || connection == null) return;
        Theme t = Theme.of(activity); int stateText = engine.connection == Sse.State.LIVE ? R.string.live : engine.connection == Sse.State.STOPPED ? R.string.stopped : R.string.connecting;
        connection.setText(engine.connection == Sse.State.RECONNECTING ? activity.getString(R.string.reconnecting, engine.reconnectAttempt) : activity.getString(stateText)); connection.setTextColor(engine.connection == Sse.State.LIVE ? t.success : t.warning);
        String state;
        switch (engine.events.state()) {
            case QUEUED: state = activity.getString(R.string.queued); break;
            case WORKING: state = activity.getString(R.string.working); break;
            case RUNNING_TOOL: state = toolName(engine.events.runningTool()); break;
            case WAITING_PERMISSION: state = activity.getString(R.string.waiting_permission); break;
            case FAILED: state = activity.getString(R.string.failed); break;
            case CANCELLED: state = activity.getString(R.string.cancelled); break;
            case CONNECTING: state = activity.getString(R.string.connecting); break;
            default: state = activity.getString(engine.events.worked() ? R.string.completed : R.string.ready);
        }
        if (engine.sending || (engine.current() != null && !engine.events.busy() && !engine.events.assistantFinished(engine.current().messageId))) state = activity.getString(R.string.connecting);
        status.setText(engine.started() > 0 ? activity.getString(R.string.status_elapsed, state, Widgets.elapsed(activity, (System.currentTimeMillis() - engine.started()) / 1000)) : state);
        queueCount.setText(engine.queuedCount() > 0 ? activity.getString(R.string.queued_count, engine.queuedCount()) : ""); queueCount.setVisibility(engine.queuedCount() > 0 ? View.VISIBLE : View.GONE);
        continueButton.setEnabled(!engine.sessionId.isEmpty() && !engine.journaling); send.setEnabled(!engine.sessionId.isEmpty() && !engine.journaling); send.setText(engine.isActive() || !engine.ready ? R.string.queued : R.string.send);
        stop.setVisibility(engine.isActive() ? View.VISIBLE : View.GONE);
        retry.setVisibility(!engine.isActive() && !engine.retryPrompt().isEmpty() ? View.VISIBLE : View.GONE); retry.setEnabled(!engine.journaling);
        JSONObject model = app.store.model(engine.project.id); modelButton.setText(Models.string(model, "modelID").isEmpty() ? activity.getString(R.string.model) : Models.string(model, "modelID"));
    }
    private String toolName(JSONObject part) {
        if (part == null) return activity.getString(R.string.working);
        switch (Events.toolLabel(part)) { case COMMAND: return activity.getString(R.string.running_command); case EDIT: return activity.getString(R.string.editing_files);
            case READ: return activity.getString(R.string.reading_files); case WEB: return activity.getString(R.string.fetching_web); case AGENT: return activity.getString(R.string.sub_agent);
            case BUILD: return activity.getString(R.string.building); case TEST: return activity.getString(R.string.running_tests); default: return activity.getString(R.string.using_tool, Models.string(part, "tool")); }
    }
    private void renderPermission() {
        permissionHost.removeAllViews(); List<JSONObject> pending = engine.events.permissions(); if (pending.isEmpty()) return;
        JSONObject p = pending.get(0); String id = Models.string(p, "id"), action = Models.string(p, "permission");
        String command = Models.string(Models.object(p, "metadata"), "command"); if (command.isEmpty()) command = String.join("\n", Models.strings(Models.array(p, "patterns")));
        LinearLayout card = Widgets.card(activity); Widgets.add(card, Widgets.text(activity, activity.getString(R.string.permission_request, action))); Widgets.add(card, Widgets.mono(activity, command));
        if (Events.destructive(command)) { TextView warning = Widgets.text(activity, R.string.destructive_warning); warning.setTextColor(Theme.of(activity).error); Widgets.add(card, warning); }
        LinearLayout buttons = Widgets.row(activity); String[] replies = { "once", "always", "reject" }; int[] labels = { R.string.allow_once, R.string.allow_always, R.string.reject };
        for (int i = 0; i < replies.length; i++) { final String response = replies[i]; Button b = Widgets.ghost(activity, labels[i], () -> engine.reply(id, response)); b.setEnabled(!engine.replying(id)); Widgets.weighted(buttons, b); } card.addView(buttons); Widgets.add(permissionHost, card);
    }
    private final class ChatAdapter extends BaseAdapter {
        List<Events.Row> rows = new ArrayList<>();
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return rows.get(i).key.hashCode(); }
        @Override public View getView(int index, View recycled, ViewGroup parent) {
            Holder h;
            if (recycled == null) {
                h = new Holder(); h.wrap = Widgets.column(activity); h.card = Widgets.card(activity); LinearLayout title = Widgets.row(activity);
                h.label = Widgets.muted(activity, ""); h.label.setTypeface(Typeface.DEFAULT, Typeface.BOLD); Widgets.weighted(title, h.label);
                h.spinner = new ProgressBar(activity); title.addView(h.spinner, new LinearLayout.LayoutParams(Theme.dp(activity, 20), Theme.dp(activity, 20))); Widgets.add(h.card, title);
                h.body = Widgets.text(activity, ""); h.details = Widgets.mono(activity, ""); Widgets.add(h.card, h.body); Widgets.add(h.card, h.details); h.wrap.addView(h.card); h.wrap.setTag(h); recycled = h.wrap;
            } else h = (Holder) recycled.getTag();
            Events.Row row = rows.get(index); Theme t = Theme.of(activity); boolean opened = expanded.contains(row.key);
            h.wrap.setPaddingRelative(Theme.dp(activity, row.role.equals("user") ? 42 : 4), Theme.dp(activity, 5), Theme.dp(activity, row.role.equals("user") ? 4 : 12), Theme.dp(activity, 5));
            h.card.setBackground(Theme.shape(activity, row.role.equals("user") ? t.surface2 : t.surface, t.border)); h.label.setTextColor(t.muted); h.body.setTextColor(t.text); h.body.setTypeface(Typeface.DEFAULT); h.body.setTextIsSelectable(true);
            h.details.setVisibility(View.GONE); h.spinner.setVisibility(View.GONE); h.wrap.setOnClickListener(null);
            if (row.type.equals("text")) { h.label.setText(row.role.equals("user") ? R.string.you : R.string.assistant); h.body.setText(shortText(row.text)); }
            else if (row.type.equals("reasoning")) {
                h.label.setText(R.string.reasoning); h.body.setText(opened ? shortText(row.text) : activity.getString(R.string.expand)); h.body.setTextIsSelectable(opened); h.body.setTextColor(t.muted);
                h.wrap.setOnClickListener(v -> { if (!expanded.add(row.key)) expanded.remove(row.key); notifyDataSetChanged(); });
            } else if (row.type.equals("tool")) {
                JSONObject toolState = Models.object(row.part, "state"); boolean failed = Models.string(toolState, "status").equals("error");
                h.label.setText(toolName(row.part)); h.label.setTextColor(failed ? t.error : t.accent2); h.body.setTypeface(Typeface.MONOSPACE); h.body.setTextIsSelectable(false);
                h.body.setText(shortText(Events.toolSummary(row.part))); h.spinner.setVisibility(Models.string(toolState, "status").equals("running") && engine.events.busy() ? View.VISIBLE : View.GONE);
                if (opened) { h.details.setVisibility(View.VISIBLE); h.details.setText(activity.getString(R.string.input_output, shortText(Models.object(toolState, "input").toString()), shortText(Models.string(toolState, "output") + Models.string(toolState, "error")))); }
                h.wrap.setOnClickListener(v -> { if (!expanded.add(row.key)) expanded.remove(row.key); notifyDataSetChanged(); });
            } else if (row.type.equals("patch") || row.type.equals("change")) {
                h.label.setText(R.string.file_change); h.body.setText(row.type.equals("change") ? row.text : String.join("\n", Models.strings(Models.array(row.part, "files")))); h.body.setTextIsSelectable(false);
                h.wrap.setOnClickListener(v -> openDiff(row.type.equals("change") ? row.text : Models.array(row.part, "files").optString(0, "")));
            } else if (row.type.equals("file")) { h.label.setText(R.string.kind_other); h.body.setText(activity.getString(R.string.file_attachment, Models.string(row.part, "filename"))); }
            else if (row.type.equals("error") || row.type.equals("cancelled")) { h.label.setText(row.type.equals("cancelled") ? R.string.cancelled : R.string.failed); h.label.setTextColor(t.error); h.body.setText(shortText(row.text)); }
            else { h.label.setText(row.type.equals("step-start") ? R.string.step_start : R.string.step_finish); h.body.setText(Models.string(row.part, "reason")); }
            return recycled;
        }
        private String shortText(String value) { return value.length() <= 64000 ? value : value.substring(0, 64000) + "\n" + activity.getString(R.string.output_truncated); }
    }
    private static final class Holder { LinearLayout wrap, card; TextView label, body, details; ProgressBar spinner; }
}
