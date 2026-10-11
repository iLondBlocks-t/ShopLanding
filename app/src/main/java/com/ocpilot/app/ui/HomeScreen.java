package com.ocpilot.app.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.R;
import com.ocpilot.app.core.Api;
import com.ocpilot.app.core.ApiError;
import com.ocpilot.app.core.Downloads;
import com.ocpilot.app.core.Models;
import com.ocpilot.app.core.TaskLog;
import java.util.ArrayList;
import java.util.List;

public final class HomeScreen extends Screen {
    private LinearLayout page;
    private boolean online;
    private List<Models.Artifact> artifacts = new ArrayList<>();
    private final Runnable poll = new Runnable() { @Override public void run() { if (visible && app.foreground) { refresh(); app.main.postDelayed(this, 15000); } } };
    private final Downloads.Listener downloadsListener = () -> { if (visible) render(); };
    public HomeScreen(MainActivity activity) { super(activity); }
    @Override public int rootTab() { return 0; }
    @Override public View build(Context c) { page = Widgets.page(c); render(); return shell(c, Widgets.scroll(c, page), R.string.app_name, false); }
    @Override public void onShow() { super.onShow(); app.downloads.observe(downloadsListener); poll.run(); loadProjectsAndArtifacts(); }
    @Override public void onHide() { app.main.removeCallbacks(poll); app.downloads.unobserve(downloadsListener); super.onHide(); }
    @Override public void onPause() { app.main.removeCallbacks(poll); super.onPause(); }
    @Override public void onResume() { super.onResume(); if (visible) { app.main.removeCallbacks(poll); poll.run(); } }
    @Override public void onTaskChanged() { if (visible) render(); }
    private void refresh() {
        track(app.api.health(new Api.Callback<Models.Health>() {
            @Override public void success(Models.Health health) { if (!visible) return; app.lastHealth = health; online = health.ok; clearError(); render(); }
            @Override public void error(ApiError error) { if (!visible) return; online = false; showError(error); render(); }
        }));
        track(app.api.status(callback(status -> { app.lastStatus = status; render(); })));
    }
    private void loadProjectsAndArtifacts() {
        track(app.api.projects(callback(list -> { app.setProjects(list); render(); loadArtifacts(); })));
        loadArtifacts();
    }
    private void loadArtifacts() {
        Models.Project project = app.selected(); if (project == null) { artifacts.clear(); render(); return; }
        track(app.api.artifacts(project.id, callback(list -> { artifacts = list; app.downloads.scan(project.id, list); render(); })));
    }
    private String known(String value) { return value == null || value.isEmpty() ? activity.getString(R.string.unknown) : value; }
    private void render() {
        if (page == null) return; Context c = activity; Theme t = Theme.of(c); page.removeAllViews();
        Widgets.add(page, Widgets.muted(c, R.string.brand_tagline));
        LinearLayout connection = Widgets.card(c); LinearLayout top = Widgets.row(c);
        Widgets.weighted(top, Widgets.section(c, R.string.connection)); top.addView(Widgets.chip(c, online ? R.string.live : R.string.offline, online ? t.success : t.warning)); Widgets.add(connection, top);
        Widgets.add(connection, Widgets.mono(c, app.store.server()));
        Models.Health h = app.lastHealth; Models.Status s = app.lastStatus;
        Widgets.add(connection, Widgets.muted(c, c.getString(R.string.version_label, known(s != null ? s.gatewayVersion : h == null ? "" : h.gateway))));
        if (h != null || s != null) Widgets.add(connection, Widgets.muted(c, c.getString(R.string.uptime, Widgets.elapsed(c, s != null ? s.uptimeSec : h.uptimeSec))));
        Widgets.add(connection, Widgets.ghost(c, R.string.github, () -> activity.navigator.push(new GithubScreen(activity, false)))); Widgets.add(page, connection);
        LinearLayout oc = Widgets.card(c); Widgets.add(oc, Widgets.section(c, R.string.opencode));
        if (s != null || h != null) {
            boolean running = s != null ? s.running : h.running;
            Widgets.add(oc, Widgets.chip(c, running ? R.string.running : R.string.not_running, running ? t.success : t.error));
            Widgets.add(oc, Widgets.muted(c, c.getString(R.string.version_label, known(s != null ? s.opencodeVersion : h.opencodeVersion))));
            if (s != null) {
                Widgets.add(oc, Widgets.muted(c, c.getString(R.string.restarts, s.restarts)));
                if (!s.lastError.isEmpty()) {
                    android.widget.TextView error = Widgets.mono(c, s.lastError.length() > 140 ? s.lastError.substring(0, 140) : s.lastError);
                    error.setMinHeight(Theme.dp(c, 48)); error.setOnClickListener(v -> keep(Widgets.dialog(activity).setTitle(R.string.last_error).setMessage(s.lastError).setPositiveButton(R.string.close, null).create()).show()); Widgets.add(oc, error);
                }
            }
        } else Widgets.add(oc, Widgets.muted(c, R.string.loading)); Widgets.add(page, oc);
        if (s != null) {
            LinearLayout system = Widgets.card(c); Widgets.add(system, Widgets.section(c, R.string.system));
            Widgets.add(system, Widgets.text(c, c.getString(R.string.disk_memory, s.freeDiskMB, s.memFreeMB, s.memTotalMB)));
            Widgets.add(system, Widgets.mono(c, c.getString(R.string.host_tools, c.getString(s.git ? R.string.yes_mark : R.string.no_mark), c.getString(s.java ? R.string.yes_mark : R.string.no_mark), c.getString(s.gradle ? R.string.yes_mark : R.string.no_mark), c.getString(s.androidSdk ? R.string.yes_mark : R.string.no_mark))));
            if (!s.java || !s.androidSdk) Widgets.add(system, Widgets.muted(c, R.string.apk_host_unavailable)); Widgets.add(page, system);
        }
        Models.Project project = app.selected(); LinearLayout current = Widgets.card(c); Widgets.add(current, Widgets.section(c, R.string.current_project));
        if (project == null) Widgets.add(current, Widgets.muted(c, R.string.select_project));
        else {
            Widgets.add(current, Widgets.title(c, project.name));
            Widgets.add(current, Widgets.muted(c, c.getString(R.string.repo_branch, project.repoFullName.isEmpty() ? c.getString(R.string.empty_project) : project.repoFullName, known(project.branch))));
            Widgets.add(current, Widgets.muted(c, c.getString(R.string.activity, Widgets.relative(c, project.lastActivityAt))));
            Widgets.add(current, Widgets.primary(c, R.string.open_workspace, () -> activity.navigator.root(2)));
        } Widgets.add(page, current);
        LinearLayout tasks = Widgets.card(c); Widgets.add(tasks, Widgets.section(c, R.string.tasks));
        List<TaskLog.Entry> entries = app.tasks.list(project == null ? null : project.id); int running = 0, queued = 0, done = 0, failed = 0;
        for (TaskLog.Entry e : entries) { if (e.state.equals("queued")) queued++; else if (e.state.equals("working") || e.state.equals("permission")) running++; else if (e.state.equals("completed")) done++; else if (e.state.equals("failed")) failed++; }
        Widgets.add(tasks, Widgets.muted(c, c.getString(R.string.task_counts, running, queued, done, failed)));
        if (entries.isEmpty()) Widgets.add(tasks, Widgets.muted(c, R.string.no_tasks));
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            TaskLog.Entry e = entries.get(i); android.widget.TextView row = Widgets.text(c, e.prompt.length() > 90 ? e.prompt.substring(0, 90) : e.prompt);
            row.setMinHeight(Theme.dp(c, 48)); row.setOnClickListener(v -> { Models.Project p = app.project(e.project); if (p != null) { app.select(p); WorkspaceScreen.Engine engine = app.cachedEngine(p.id); if (engine != null) { if (!engine.isActive()) engine.chooseSession(e.session); else if (!engine.sessionId.equals(e.session)) Widgets.toast(activity, R.string.stop_first); } else app.store.session(p.id, e.session); activity.navigator.root(2); } else activity.navigator.root(1); });
            Widgets.add(tasks, row); Widgets.add(tasks, Widgets.chip(c, Widgets.state(e.state), e.state.equals("failed") ? t.error : t.muted));
        } Widgets.add(page, tasks);
        LinearLayout recent = Widgets.card(c); Widgets.add(recent, Widgets.section(c, R.string.recent_artifacts));
        if (artifacts.isEmpty()) Widgets.add(recent, Widgets.muted(c, R.string.no_artifacts));
        if (project != null) for (int i = 0; i < Math.min(3, artifacts.size()); i++) {
            Models.Artifact a = artifacts.get(i); Widgets.add(recent, Widgets.text(c, a.filename()));
            Downloads.Job job = app.downloads.job(project.id, a);
            Widgets.add(recent, Widgets.ghost(c, job != null && job.active ? R.string.cancel : R.string.download, () -> { if (job != null && job.active) job.cancel(); else app.downloads.start(project.id, a); }));
            if (job != null && job.error != null) Widgets.add(recent, Widgets.muted(c, job.error.message(c)));
        } Widgets.add(page, recent);
        Widgets.add(page, Widgets.section(c, R.string.quick_actions)); LinearLayout quick = Widgets.row(c);
        Widgets.weighted(quick, Widgets.primary(c, R.string.new_task, () -> activity.navigator.root(2)));
        Widgets.weighted(quick, Widgets.ghost(c, R.string.projects, () -> activity.navigator.root(1)));
        Widgets.add(page, quick); Widgets.add(page, Widgets.ghost(c, R.string.reconnect, () -> { app.reconnect(); refresh(); loadProjectsAndArtifacts(); }));
    }
}
