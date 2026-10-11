package com.ocpilot.app.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.R;
import com.ocpilot.app.core.Api;
import com.ocpilot.app.core.ApiError;
import com.ocpilot.app.core.Downloads;
import com.ocpilot.app.core.Models;
import com.ocpilot.app.core.TaskLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BuildsScreen extends Screen {
    private Models.Project project;
    private List<Models.Artifact> artifacts = new ArrayList<>();
    private ArtifactAdapter adapter;
    private LinearLayout buildQueue, hostNote;
    private TextView empty;
    private final Set<String> expanded = new HashSet<>();
    private final Downloads.Listener listener = () -> { if (visible && adapter != null) adapter.notifyDataSetChanged(); };
    public BuildsScreen(MainActivity activity) { super(activity); project = app.selected(); }
    @Override public int rootTab() { return 3; }
    @Override public View build(Context c) {
        ListView list = new ListView(c); list.setDivider(null); list.setDividerHeight(0);
        LinearLayout header = Widgets.page(c); if (project != null) Widgets.add(header, Widgets.title(c, project.name));
        Widgets.add(header, Widgets.ghost(c, R.string.refresh, this::load)); hostNote = Widgets.column(c); Widgets.add(header, hostNote);
        buildQueue = Widgets.card(c); Widgets.add(header, buildQueue); empty = Widgets.muted(c, R.string.no_artifacts); Widgets.add(header, empty);
        list.addHeaderView(header, null, false); adapter = new ArtifactAdapter(); list.setAdapter(adapter); renderLocal();
        return shell(c, list, R.string.builds, false);
    }
    @Override public void onShow() { super.onShow(); app.downloads.observe(listener); load(); }
    @Override public void onHide() { app.downloads.unobserve(listener); super.onHide(); }
    @Override public void onTaskChanged() { if (visible) renderLocal(); }
    private void load() {
        if (project == null) return;
        track(app.api.artifacts(project.id, callback(list -> { artifacts = list; artifacts.sort(Comparator.comparingInt((Models.Artifact a) -> group(a.kind))); empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE); adapter.notifyDataSetChanged(); app.downloads.scan(project.id, list); })));
        track(app.api.status(callback(status -> { app.lastStatus = status; renderLocal(); })));
    }
    private void renderLocal() {
        if (buildQueue == null) return;
        Context c = activity; hostNote.removeAllViews(); if (app.lastStatus != null && (!app.lastStatus.java || !app.lastStatus.androidSdk)) Widgets.add(hostNote, Widgets.banner(c, c.getString(R.string.apk_host_unavailable), Theme.of(c).warning));
        buildQueue.removeAllViews(); Widgets.add(buildQueue, Widgets.section(c, R.string.build_queue)); Widgets.add(buildQueue, Widgets.muted(c, R.string.build_queue_help));
        boolean found = false;
        if (project != null) for (TaskLog.Entry task : app.tasks.list(project.id)) if (task.build) {
            found = true; TextView title = Widgets.text(c, task.prompt.length() > 100 ? task.prompt.substring(0, 100) : task.prompt); title.setMinHeight(Theme.dp(c, 48));
            title.setOnClickListener(v -> { if (!expanded.add(task.id)) expanded.remove(task.id); renderLocal(); }); Widgets.add(buildQueue, title);
            Widgets.add(buildQueue, Widgets.chip(c, Widgets.state(task.state), task.state.equals("failed") ? Theme.of(c).error : Theme.of(c).muted));
            if (expanded.contains(task.id)) Widgets.add(buildQueue, Widgets.mono(c, task.log));
        }
        if (!found) Widgets.add(buildQueue, Widgets.muted(c, R.string.no_builds));
    }
    private static int group(String kind) { switch (kind) { case "apk-debug": return 0; case "apk-release": return 1; case "zip": return 2; case "log": return 3; default: return 4; } }
    private void download(Models.Artifact artifact) { Downloads.Job job = app.downloads.job(project.id, artifact); if (job != null && job.active) job.cancel(); else app.downloads.start(project.id, artifact); }
    private void delete(Models.Artifact artifact) {
        keep(Widgets.dialog(activity).setMessage(R.string.delete_local_confirm).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete, (d, w) -> app.downloads.delete(project.id, artifact, new Api.Callback<Void>() {
            @Override public void success(Void value) { if (visible) { Widgets.toast(activity, R.string.file_deleted); adapter.notifyDataSetChanged(); } }
            @Override public void error(ApiError error) { if (visible) showError(error); }
        })).create()).show();
    }
    private final class ArtifactAdapter extends BaseAdapter {
        @Override public int getCount() { return artifacts.size(); }
        @Override public Object getItem(int i) { return artifacts.get(i); }
        @Override public long getItemId(int i) { return artifacts.get(i).path.hashCode(); }
        @Override public View getView(int index, View recycled, ViewGroup parent) {
            Holder h;
            if (recycled == null) {
                LinearLayout wrap = Widgets.page(activity), card = Widgets.card(activity); h = new Holder(); h.group = Widgets.section(activity, R.string.recent_artifacts);
                h.name = Widgets.text(activity, ""); h.badge = Widgets.chip(activity, R.string.kind_other, Theme.of(activity).accent2); h.metadata = Widgets.mono(activity, ""); h.progress = Widgets.muted(activity, "");
                Widgets.add(card, h.group); Widgets.add(card, h.name); Widgets.add(card, h.badge); Widgets.add(card, h.metadata); Widgets.add(card, h.progress);
                final Holder holder = h; LinearLayout row = Widgets.row(activity); h.download = Widgets.primary(activity, R.string.download, () -> download(holder.artifact)); h.install = Widgets.ghost(activity, R.string.install, () -> activity.install(project.id, holder.artifact)); Widgets.weighted(row, h.download); Widgets.weighted(row, h.install); Widgets.add(card, row);
                LinearLayout actions = Widgets.row(activity); h.save = Widgets.ghost(activity, R.string.save_downloads, () -> activity.export(project.id, holder.artifact)); h.share = Widgets.ghost(activity, R.string.share, () -> activity.share(project.id, holder.artifact)); Widgets.weighted(actions, h.save); Widgets.weighted(actions, h.share); Widgets.add(card, actions);
                h.delete = Widgets.ghost(activity, R.string.delete_local, () -> delete(holder.artifact)); Widgets.add(card, h.delete); wrap.addView(card); wrap.setTag(h); recycled = wrap;
            } else h = (Holder) recycled.getTag();
            Models.Artifact a = artifacts.get(index); h.artifact = a; boolean ready = app.downloads.verifiedFile(project.id, a) != null; Downloads.Job job = app.downloads.job(project.id, a);
            h.group.setText(group(a.kind) == 4 ? R.string.kind_other : Widgets.kind(a.kind)); h.group.setVisibility(index == 0 || group(artifacts.get(index - 1).kind) != group(a.kind) ? View.VISIBLE : View.GONE);
            h.name.setText(a.filename()); h.badge.setText(Widgets.kind(a.kind));
            h.metadata.setText(activity.getString(R.string.artifact_metadata, Widgets.bytes(activity, a.size), a.modifiedAt, a.sha256.length() > 16 ? a.sha256.substring(0, 16) : a.sha256)); h.metadata.setMinHeight(Theme.dp(activity, 48)); h.metadata.setOnClickListener(v -> activity.copy(a.sha256));
            h.progress.setText(job != null && job.active ? job.verifying ? activity.getString(R.string.verifying_hash) : activity.getString(R.string.download_progress, Widgets.bytes(activity, job.bytes), Widgets.bytes(activity, a.size)) : job != null && job.error != null ? job.error.kind == ApiError.Kind.Cancelled ? activity.getString(R.string.download_cancelled) : job.error.message(activity) : ready ? activity.getString(R.string.download_done) : "");
            h.download.setText(job != null && job.active ? R.string.cancel : R.string.download); h.download.setOnClickListener(v -> app.safe(() -> download(a)));
            h.install.setVisibility(a.isApk() ? View.VISIBLE : View.GONE); h.install.setEnabled(ready); h.install.setOnClickListener(v -> app.safe(() -> activity.install(project.id, a)));
            h.save.setEnabled(ready); h.save.setOnClickListener(v -> app.safe(() -> activity.export(project.id, a)));
            h.share.setEnabled(ready); h.share.setOnClickListener(v -> app.safe(() -> activity.share(project.id, a)));
            h.delete.setEnabled(ready || job != null && !job.active); h.delete.setOnClickListener(v -> app.safe(() -> delete(a))); return recycled;
        }
    }
    private static final class Holder { Models.Artifact artifact; TextView group, name, badge, metadata, progress; Button download, install, save, share, delete; }
}
