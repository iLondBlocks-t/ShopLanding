package com.ocpilot.app;

import android.app.Application;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import com.ocpilot.app.core.Api;
import com.ocpilot.app.core.ApiError;
import com.ocpilot.app.core.Downloads;
import com.ocpilot.app.core.Log;
import com.ocpilot.app.core.Models;
import com.ocpilot.app.core.Notifier;
import com.ocpilot.app.core.Store;
import com.ocpilot.app.core.TaskLog;
import com.ocpilot.app.core.TaskWatchService;
import com.ocpilot.app.ui.WorkspaceScreen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class App extends Application {
    public final ExecutorService requests = Executors.newFixedThreadPool(4);
    public final Handler main = new Handler(Looper.getMainLooper());
    public Store store;
    public Api api;
    public Downloads downloads;
    public TaskLog tasks;
    public Models.Status lastStatus;
    public Models.Health lastHealth;
    public boolean ready, foreground;
    public ApiError startupError;
    public final List<Models.Project> projects = new ArrayList<>();
    private boolean rotationPending, rotationUnauthorized;
    private String rotationToken = "";
    private long rotationSequence;
    private final List<Observer> observers = new ArrayList<>();
    private final Map<String, WorkspaceScreen.Engine> engines = new LinkedHashMap<>();
    private final Map<String, String[]> watching = new LinkedHashMap<>();
    public interface Observer { void ready(); void paired(); void unauthorized(); void networkChanged(); void taskChanged(); }
    @Override public void onCreate() {
        super.onCreate(); api = new Api(this); downloads = new Downloads(this);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { store.crash(error); } catch (Exception ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(1); }
        });
        if (BuildConfig.DEBUG) StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyLog().build());
        Notifier.channels(this);
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity != null) try {
            connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { notifyNetwork(); }
                @Override public void onLost(Network network) { notifyNetwork(); }
            });
        } catch (Exception ex) { Log.failure(ex); }
        requests.execute(() -> {
            store = new Store(this);
            try { store.restoreCredential(); } catch (Exception ex) { store.clearCredential(); startupError = new ApiError(ApiError.Kind.Storage); }
            tasks = new TaskLog(store, requests);
            List<Models.Project> cached = store.paired() ? store.cachedProjects() : new ArrayList<>();
            try { downloads.initialize(); } catch (Exception ex) { startupError = new ApiError(ApiError.Kind.Storage); }
            main.post(() -> { projects.addAll(cached); ready = true; for (Observer o : new ArrayList<>(observers)) safe(o::ready); });
        });
    }
    public void safe(Runnable run) {
        try { run.run(); } catch (Exception | StackOverflowError ex) { Log.failure(ex); if (store != null) try { store.crash(ex); } catch (Exception ignored) { } }
    }
    public void observe(Observer observer) { if (!observers.contains(observer)) observers.add(observer); }
    public void unobserve(Observer observer) { observers.remove(observer); }
    private void notifyNetwork() { main.post(() -> { if (ready && store.paired()) reconnect(); for (Observer o : new ArrayList<>(observers)) safe(o::networkChanged); }); }
    public boolean networkAvailable() {
        try { ConnectivityManager c = getSystemService(ConnectivityManager.class); return c != null && c.getActiveNetwork() != null; }
        catch (Exception ex) { return false; }
    }
    public void notifyPaired() { for (Observer observer : new ArrayList<>(observers)) safe(observer::paired); }
    public boolean rotationPending() { return rotationPending; }
    public long beginRotation() { rotationPending = true; rotationUnauthorized = false; rotationToken = store.token(); return ++rotationSequence; }
    public boolean rotationActive(long sequence) { return rotationPending && rotationSequence == sequence; }
    public void finishRotation(long sequence, boolean success) {
        if (sequence != rotationSequence) return;
        boolean invalidate = rotationUnauthorized && !success; String old = rotationToken;
        rotationPending = false; rotationUnauthorized = false; rotationToken = "";
        if (invalidate) unauthorized(old);
    }
    public void unauthorized(String requestToken) {
        if (rotationPending && requestToken.equals(rotationToken)) { rotationUnauthorized = true; return; }
        // A late 401 from a pre-rotation request cannot invalidate the newly rotated token.
        if (!store.paired() || !store.token().equals(requestToken)) return;
        disconnect(); for (Observer o : new ArrayList<>(observers)) safe(o::unauthorized);
    }
    public void disconnect() {
        rotationSequence++; rotationPending = false; rotationUnauthorized = false; rotationToken = "";
        for (WorkspaceScreen.Engine engine : new ArrayList<>(engines.values())) engine.close();
        engines.clear(); downloads.cancelAll(); watching.clear(); stopService(new Intent(this, TaskWatchService.class));
        store.clearCredential(); projects.clear(); lastStatus = null; lastHealth = null;
    }
    public void setProjects(List<Models.Project> list) {
        projects.clear(); projects.addAll(list); List<Models.Project> copy = new ArrayList<>(list);
        String server = store.server(); requests.execute(() -> store.cacheProjects(server, copy));
    }
    public Models.Project project(String id) { for (Models.Project p : projects) if (p.id.equals(id)) return p; return null; }
    public Models.Project selected() { return project(store.selectedProject()); }
    public void select(Models.Project project) {
        if (project.id.isEmpty()) return;
        boolean found = false;
        for (int i = 0; i < projects.size(); i++) if (projects.get(i).id.equals(project.id)) { projects.set(i, project); found = true; break; }
        if (!found) projects.add(project);
        store.selectedProject(project.id); List<Models.Project> copy = new ArrayList<>(projects); String server = store.server(); requests.execute(() -> store.cacheProjects(server, copy));
    }
    public WorkspaceScreen.Engine engine(Models.Project project) {
        WorkspaceScreen.Engine engine = engines.get(project.id);
        if (engine == null) { engine = new WorkspaceScreen.Engine(this, project); engines.put(project.id, engine); }
        return engine;
    }
    public void dropEngine(String project) {
        WorkspaceScreen.Engine engine = engines.remove(project); if (engine != null) engine.close(); watch(project, "", "", false);
    }
    public void reconnect() { for (WorkspaceScreen.Engine e : engines.values()) e.restartStream(); }
    public boolean hasActiveWorkspace(String project) { WorkspaceScreen.Engine e = engines.get(project); return e != null && e.isActive(); }
    public WorkspaceScreen.Engine cachedEngine(String project) { return engines.get(project); }
    public boolean hasWatch(String project) { return watching.containsKey(project); }
    public void taskUpdated() { for (Observer observer : new ArrayList<>(observers)) safe(observer::taskChanged); }
    public boolean tasksRunning() { for (WorkspaceScreen.Engine engine : engines.values()) if (engine.isActive()) return true; return !watching.isEmpty(); }
    public boolean watch(String project, String session, String name, boolean active) {
        if (active) watching.put(project, new String[]{ session, name }); else watching.remove(project);
        try {
            if (watching.isEmpty()) stopService(new Intent(this, TaskWatchService.class));
            else startForegroundService(new Intent(this, TaskWatchService.class));
        } catch (Exception ex) { watching.remove(project); Log.failure(ex); return false; }
        return true;
    }
    public String watchingProject() { return watching.isEmpty() ? "" : watching.keySet().iterator().next(); }
    public String watchingSession() { return watching.isEmpty() ? "" : watching.values().iterator().next()[0]; }
    public String watchingName() { return watching.isEmpty() ? getString(R.string.app_name) : watching.values().iterator().next()[1]; }
    public void clearCached(Api.Callback<Void> callback) {
        if (tasksRunning() || downloads.anyActive()) { callback.error(new ApiError(ApiError.Kind.Client)); return; }
        for (WorkspaceScreen.Engine e : engines.values()) e.close(); engines.clear();
        requests.execute(() -> {
            try {
                downloads.clearAll(); tasks.clear(); store.clearLists();
                main.post(() -> { projects.clear(); safe(() -> callback.success(null)); });
            } catch (Exception ex) { main.post(() -> safe(() -> callback.error(new ApiError(ApiError.Kind.Storage)))); }
        });
    }
}
