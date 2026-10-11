package com.ocpilot.app.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.ocpilot.app.App;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.R;
import com.ocpilot.app.core.Api;
import com.ocpilot.app.core.ApiError;
import com.ocpilot.app.core.Models;
import java.util.ArrayList;
import java.util.List;

public final class ProjectsScreen extends Screen {
    private List<Models.Project> projects = new ArrayList<>();
    private ProjectAdapter adapter;
    private TextView empty;
    public ProjectsScreen(MainActivity activity) { super(activity); projects.addAll(app.projects); }
    @Override public int rootTab() { return 1; }
    @Override public View build(Context c) {
        ListView list = new ListView(c); list.setDivider(null); list.setDividerHeight(0);
        LinearLayout header = Widgets.page(c); LinearLayout actions = Widgets.row(c);
        Widgets.weighted(actions, Widgets.primary(c, R.string.new_project, this::newProject)); Widgets.weighted(actions, Widgets.ghost(c, R.string.refresh, this::load)); Widgets.add(header, actions);
        empty = Widgets.muted(c, projects.isEmpty() ? R.string.no_projects : R.string.loading); empty.setVisibility(projects.isEmpty() ? View.VISIBLE : View.GONE); Widgets.add(header, empty);
        list.addHeaderView(header, null, false); adapter = new ProjectAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> { int index = position - list.getHeaderViewsCount(); if (index >= 0 && index < projects.size()) actions(projects.get(index)); });
        return shell(c, list, R.string.projects, false);
    }
    @Override public void onShow() { super.onShow(); load(); }
    private void load() { track(app.api.projects(callback(list -> { projects = list; app.setProjects(list); adapter.notifyDataSetChanged(); empty.setText(R.string.no_projects); empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE); }))); }
    private void newProject() {
        String[] options = { activity.getString(R.string.from_repo), activity.getString(R.string.create_repo_project), activity.getString(R.string.empty_project) };
        keep(Widgets.dialog(activity).setTitle(R.string.new_project).setItems(options, (d, which) -> {
            if (which == 0) activity.navigator.push(new GithubScreen(activity, true));
            else createDialog(this, null, which == 1);
        }).create()).show();
    }
    public static void createDialog(Screen host, String repo, boolean createRepo) {
        MainActivity activity = host.activity; App app = host.app; LinearLayout form = Widgets.page(activity);
        EditText name = Widgets.field(activity, R.string.project_name, false);
        if (repo != null) name.setText(repo.substring(repo.lastIndexOf('/') + 1)); Widgets.add(form, name);
        CheckBox isPrivate = new CheckBox(activity); isPrivate.setText(R.string.private_repo); isPrivate.setTextColor(Theme.of(activity).text); isPrivate.setChecked(true);
        if (createRepo) Widgets.add(form, isPrivate);
        TextView progress = Widgets.muted(activity, R.string.creating_project); progress.setVisibility(View.GONE); Widgets.add(form, progress);
        AlertDialog dialog = host.keep(Widgets.dialog(activity).setTitle(R.string.new_project).setView(form).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm, null).create());
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = name.getText().toString().trim(); if (value.isEmpty()) { name.setError(activity.getString(R.string.field_required)); return; }
            name.setEnabled(false); progress.setVisibility(View.VISIBLE); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            host.track(app.api.createProject(value, repo, createRepo ? isPrivate.isChecked() : null, new Api.Callback<Models.Project>() {
                @Override public void success(Models.Project project) {
                    if (project.id.isEmpty()) { error(new ApiError(ApiError.Kind.Parse)); return; }
                    dialog.dismiss(); app.select(project); if (!activity.isDestroyed()) activity.navigator.root(2);
                }
                @Override public void error(ApiError error) {
                    if (!host.visible) return; name.setEnabled(true); progress.setVisibility(View.GONE); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); host.showError(error);
                }
            }));
        })); dialog.show();
    }
    private void actions(Models.Project project) {
        String[] options = { activity.getString(R.string.open), activity.getString(R.string.rename), activity.getString(project.archived ? R.string.unarchive : R.string.archive), activity.getString(R.string.delete), activity.getString(R.string.git_status) };
        keep(Widgets.dialog(activity).setTitle(project.name).setItems(options, (d, which) -> {
            if (which == 0) { app.select(project); activity.navigator.root(2); }
            if (which == 1) rename(project);
            if (which == 2) track(app.api.updateProject(project.id, null, !project.archived, callback(p -> load())));
            if (which == 3) delete(project);
            if (which == 4) git(project);
        }).create()).show();
    }
    private void git(Models.Project project) {
        track(app.api.git(project.id, callback(git -> {
            StringBuilder text = new StringBuilder(activity.getString(R.string.git_details, git.branch, activity.getString(git.dirty ? R.string.git_dirty : R.string.git_clean), git.ahead));
            for (Models.Commit commit : git.lastCommits) text.append("\n").append(activity.getString(R.string.git_commit, commit.sha.length() > 12 ? commit.sha.substring(0, 12) : commit.sha, commit.subject, commit.date));
            LinearLayout body = Widgets.page(activity); Widgets.add(body, Widgets.mono(activity, text.toString()));
            keep(Widgets.dialog(activity).setTitle(R.string.git_status).setView(Widgets.scroll(activity, body)).setPositiveButton(R.string.close, null).create()).show();
        })));
    }
    private void rename(Models.Project p) {
        LinearLayout form = Widgets.page(activity); EditText name = Widgets.field(activity, R.string.project_name, false); name.setText(p.name); Widgets.add(form, name);
        AlertDialog dialog = keep(Widgets.dialog(activity).setTitle(R.string.rename).setView(form).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create());
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = name.getText().toString().trim(); if (value.isEmpty()) { name.setError(activity.getString(R.string.field_required)); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            track(app.api.updateProject(p.id, value, null, new Api.Callback<Models.Project>() {
                @Override public void success(Models.Project result) { if (!visible) return; dialog.dismiss(); load(); }
                @Override public void error(ApiError error) { if (!visible) return; dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); showError(error); }
            }));
        })); dialog.show();
    }
    private void delete(Models.Project p) {
        LinearLayout form = Widgets.page(activity); Widgets.add(form, Widgets.text(activity, activity.getString(R.string.delete_project_confirm, p.name)));
        EditText name = Widgets.field(activity, R.string.project_name, false); Widgets.add(form, name);
        AlertDialog dialog = keep(Widgets.dialog(activity).setTitle(R.string.delete).setView(form).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete, null).create());
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!name.getText().toString().equals(p.name)) { name.setError(activity.getString(R.string.confirmation_mismatch)); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            track(app.api.deleteProject(p.id, new Api.Callback<Void>() {
                @Override public void success(Void ignored) { if (!visible) return; dialog.dismiss(); app.dropEngine(p.id); if (app.store.selectedProject().equals(p.id)) app.store.selectedProject(""); load(); }
                @Override public void error(ApiError error) { if (!visible) return; dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); showError(error); }
            }));
        })); dialog.show();
    }
    private final class ProjectAdapter extends BaseAdapter {
        @Override public int getCount() { return projects.size(); }
        @Override public Object getItem(int i) { return projects.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View recycled, ViewGroup parent) {
            Holder h;
            if (recycled == null) {
                LinearLayout wrap = Widgets.page(activity), card = Widgets.card(activity); h = new Holder(); h.name = Widgets.title(activity, ""); h.repo = Widgets.muted(activity, ""); h.date = Widgets.muted(activity, ""); h.badge = Widgets.chip(activity, R.string.archived, Theme.of(activity).warning);
                Widgets.add(card, h.name); Widgets.add(card, h.repo); Widgets.add(card, h.date); Widgets.add(card, h.badge); wrap.addView(card); wrap.setTag(h); recycled = wrap;
            } else h = (Holder) recycled.getTag();
            Models.Project p = projects.get(i); h.name.setText(p.name);
            h.repo.setText(activity.getString(R.string.repo_branch, p.repoFullName.isEmpty() ? activity.getString(R.string.empty_project) : p.repoFullName, p.branch.isEmpty() ? activity.getString(R.string.unknown) : p.branch));
            h.date.setText(activity.getString(R.string.activity, Widgets.relative(activity, p.lastActivityAt))); h.badge.setVisibility(p.archived ? View.VISIBLE : View.GONE); return recycled;
        }
    }
    private static final class Holder { TextView name, repo, date, badge; }
}
