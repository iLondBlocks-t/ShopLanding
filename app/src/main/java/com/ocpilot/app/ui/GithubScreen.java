package com.ocpilot.app.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.R;
import com.ocpilot.app.core.Api;
import com.ocpilot.app.core.ApiError;
import com.ocpilot.app.core.Models;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GithubScreen extends Screen {
    private final boolean picker;
    private Models.Github github;
    private EditText token, search;
    private TextView account, scopes, empty;
    private Button save, remove, more, create;
    private final List<Models.Repo> repos = new ArrayList<>(), filtered = new ArrayList<>();
    private RepoAdapter adapter;
    private int page = 1;
    private boolean loading, hasMore;
    private String filter = "";
    public GithubScreen(MainActivity activity, boolean picker) { super(activity); this.picker = picker; }
    @Override public View build(Context c) {
        ListView list = new ListView(c); list.setDivider(null); list.setDividerHeight(0);
        LinearLayout header = Widgets.page(c), card = Widgets.card(c);
        account = Widgets.section(c, R.string.github_not_connected); scopes = Widgets.muted(c, ""); Widgets.add(card, account); Widgets.add(card, scopes);
        Widgets.add(card, Widgets.section(c, R.string.github_token)); token = Widgets.field(c, R.string.github_token_hint, true); token.setText(""); token.setSaveEnabled(false); Widgets.add(card, token);
        CheckBox show = new CheckBox(c); show.setText(R.string.show_token); show.setTextColor(Theme.of(c).text);
        show.setOnCheckedChangeListener((b, checked) -> { int caret = token.getSelectionStart(); token.setInputType(InputType.TYPE_CLASS_TEXT | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD)); token.setSelection(Math.max(0, Math.min(caret, token.length()))); }); Widgets.add(card, show);
        LinearLayout buttons = Widgets.row(c); save = Widgets.primary(c, R.string.save, this::saveToken); remove = Widgets.ghost(c, R.string.remove, this::removeToken); Widgets.weighted(buttons, save); Widgets.weighted(buttons, remove); Widgets.add(card, buttons);
        Widgets.add(card, Widgets.muted(c, R.string.github_help)); Widgets.add(header, card);
        create = Widgets.ghost(c, R.string.create_repo, this::createRepository); Widgets.add(header, create);
        Widgets.add(header, Widgets.section(c, R.string.repositories)); search = Widgets.field(c, R.string.search_repos, false); search.setText(filter); Widgets.add(header, search);
        empty = Widgets.muted(c, R.string.no_repos); Widgets.add(header, empty); list.addHeaderView(header, null, false);
        LinearLayout footer = Widgets.page(c); more = Widgets.ghost(c, R.string.load_more, this::loadPage); Widgets.add(footer, more); list.addFooterView(footer, null, false);
        adapter = new RepoAdapter(); list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) { filter = s.toString(); filter(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        list.setOnItemClickListener((p, v, position, id) -> { int index = position - list.getHeaderViewsCount(); if (index >= 0 && index < filtered.size()) ProjectsScreen.createDialog(this, filtered.get(index).fullName, false); });
        renderStatus(); filter(); return shell(c, list, picker ? R.string.from_repo : R.string.github, true);
    }
    @Override public void onShow() { super.onShow(); track(app.api.github(callback(status -> { github = status; renderStatus(); if (status.connected && repos.isEmpty()) { page = 1; loadPage(); } }))); }
    @Override public void onHide() { if (token != null) token.setText(""); loading = false; super.onHide(); }
    private void renderStatus() {
        if (account == null) return; boolean connected = github != null && github.connected;
        account.setText(connected ? activity.getString(R.string.github_connected, github.login) : activity.getString(R.string.github_not_connected));
        scopes.setText(connected ? activity.getString(R.string.scopes, String.join(activity.getString(R.string.list_separator), github.scopes)) : "");
        remove.setEnabled(connected); create.setEnabled(connected); more.setVisibility(connected && hasMore ? View.VISIBLE : View.GONE);
    }
    private void filter() {
        filtered.clear(); String needle = filter.toLowerCase(Locale.ROOT);
        for (Models.Repo repo : repos) if (repo.fullName.toLowerCase(Locale.ROOT).contains(needle) || repo.description.toLowerCase(Locale.ROOT).contains(needle)) filtered.add(repo);
        if (adapter != null) adapter.notifyDataSetChanged(); if (empty != null) empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }
    private void saveToken() {
        String value = token.getText().toString().trim(); if (value.isEmpty()) { token.setError(activity.getString(R.string.field_required)); return; }
        save.setEnabled(false); clearError();
        // Clear immediately, including when the screen is hidden or the request later fails. No prefs/state restore.
        token.setText("");
        app.api.saveGithub(value, new Api.Callback<Models.Github>() {
            @Override public void success(Models.Github result) { if (visible) { token.setText(""); save.setEnabled(true); github = result; repos.clear(); page = 1; renderStatus(); loadPage(); } }
            @Override public void error(ApiError error) { if (visible) { token.setText(""); save.setEnabled(true); showError(error); } }
        });
    }
    private void removeToken() {
        keep(Widgets.dialog(activity).setMessage(R.string.github_remove_confirm).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.remove, (d, w) -> {
            token.setText(""); track(app.api.removeGithub(callback(ignored -> { github = Models.Github.fromJson(new org.json.JSONObject()); repos.clear(); hasMore = false; renderStatus(); filter(); })));
        }).create()).show();
    }
    private void loadPage() {
        if (loading || github == null || !github.connected) return; loading = true; more.setEnabled(false);
        track(app.api.repos(page, new Api.Callback<List<Models.Repo>>() {
            @Override public void success(List<Models.Repo> list) {
                if (!visible) return; loading = false; more.setEnabled(true); repos.addAll(list); page++; hasMore = list.size() == 30; renderStatus(); filter(); clearError();
            }
            @Override public void error(ApiError error) { if (!visible) return; loading = false; more.setEnabled(true); hasMore = true; renderStatus(); showError(error); }
        }));
    }
    private void createRepository() {
        LinearLayout form = Widgets.page(activity); EditText name = Widgets.field(activity, R.string.repo_name, false), description = Widgets.field(activity, R.string.description, false);
        CheckBox isPrivate = new CheckBox(activity); isPrivate.setText(R.string.private_repo); isPrivate.setTextColor(Theme.of(activity).text); isPrivate.setChecked(true);
        Widgets.add(form, name); Widgets.add(form, description); Widgets.add(form, isPrivate);
        AlertDialog dialog = keep(Widgets.dialog(activity).setTitle(R.string.create_repo).setView(form).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm, null).create());
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim(); if (n.isEmpty()) { name.setError(activity.getString(R.string.field_required)); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            track(app.api.createRepo(n, isPrivate.isChecked(), description.getText().toString(), new Api.Callback<Models.Repo>() {
                @Override public void success(Models.Repo repo) { if (!visible) return; dialog.dismiss(); repos.clear(); page = 1; loadPage(); ProjectsScreen.createDialog(GithubScreen.this, repo.fullName, false); }
                @Override public void error(ApiError error) { if (!visible) return; dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); showError(error); }
            }));
        })); dialog.show();
    }
    private final class RepoAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int i) { return filtered.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View recycled, ViewGroup parent) {
            Holder h;
            if (recycled == null) {
                LinearLayout wrap = Widgets.page(activity), card = Widgets.card(activity); h = new Holder(); h.name = Widgets.section(activity, R.string.repositories); h.details = Widgets.muted(activity, "");
                Widgets.add(card, h.name); Widgets.add(card, h.details); wrap.addView(card); wrap.setTag(h); recycled = wrap;
            } else h = (Holder) recycled.getTag();
            Models.Repo repo = filtered.get(i); h.name.setText(repo.fullName); h.details.setText(activity.getString(R.string.repo_details, repo.description, repo.defaultBranch, Widgets.relative(activity, repo.updatedAt))); return recycled;
        }
    }
    private static final class Holder { TextView name, details; }
}
