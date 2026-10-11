package com.ocpilot.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import com.ocpilot.app.core.Api;
import com.ocpilot.app.core.ApiError;
import com.ocpilot.app.core.Downloads;
import com.ocpilot.app.core.Models;
import com.ocpilot.app.core.ShareProvider;
import com.ocpilot.app.ui.Navigator;
import com.ocpilot.app.ui.Theme;
import com.ocpilot.app.ui.Widgets;
import java.io.File;
import java.util.Locale;

public final class MainActivity extends Activity implements App.Observer {
    private static final int NOTIFICATIONS = 101, WRITE_DOWNLOADS = 102, INSTALL_SOURCE = 103;
    public Navigator navigator;
    private App app;
    private boolean initialized, paletteDark = true;
    private int restoredTab;
    private String pendingInstallProject = "", pendingExportProject = "";
    private Models.Artifact pendingInstall, pendingExport;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); app = (App) getApplication(); app.observe(this);
        if (saved != null) {
            restoredTab = saved.getInt("tab", 0); pendingInstallProject = saved.getString("install_project", "");
            pendingExportProject = saved.getString("export_project", "");
            pendingInstall = artifact(saved.getString("install_artifact", "")); pendingExport = artifact(saved.getString("export_artifact", ""));
        }
        LinearLayout loading = Widgets.page(this); loading.setGravity(android.view.Gravity.CENTER);
        loading.setBackgroundColor(Theme.of(this).background); loading.addView(new ProgressBar(this)); loading.addView(Widgets.text(this, R.string.loading)); setContentView(loading);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> onBackPressed());
        if (app.ready) ready();
    }
    private Models.Artifact artifact(String json) {
        try { return json.isEmpty() ? null : Models.Artifact.fromJson(new org.json.JSONObject(json)); } catch (Exception ignored) { return null; }
    }
    @Override public void ready() {
        if (initialized || isFinishing() || isDestroyed()) return; initialized = true;
        LinearLayout root = Widgets.column(this);
        String language = getResources().getConfiguration().getLocales().get(0).getLanguage();
        root.setLayoutDirection(language.equals("en") ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL);
        FrameLayout host = new FrameLayout(this); root.addView(host, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout bottom = Widgets.row(this); root.addView(bottom); setContentView(root); applyTheme(); navigator = new Navigator(this, host, bottom);
        if (app.store.paired()) navigator.root(restoredTab); else navigator.pairing(app.startupError);
        handleIntent(getIntent());
    }
    public void applyTheme() {
        Theme t = Theme.of(this); paletteDark = t.dark; getWindow().setStatusBarColor(t.background); getWindow().setNavigationBarColor(t.background);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(t.dark ? flags & ~light : flags | light);
    }
    @Override public void paired() { if (navigator != null) navigator.root(0); }
    @Override public void unauthorized() { if (navigator != null) navigator.pairing(new ApiError(ApiError.Kind.Unauthorized)); }
    @Override public void taskChanged() { if (navigator != null && navigator.current() != null) navigator.current().onTaskChanged(); }
    @Override public void networkChanged() { if (navigator != null && navigator.current() != null) navigator.current().refreshBanners(); }
    @Override protected void onResume() { super.onResume(); if (app != null) app.foreground = true; if (navigator != null) navigator.resume(); }
    @Override protected void onPause() { if (app != null) app.foreground = false; if (navigator != null) navigator.pause(); super.onPause(); }
    @Override protected void onDestroy() { if (navigator != null) navigator.destroy(); if (app != null) app.unobserve(this); super.onDestroy(); }
    @Override public void onBackPressed() { if (navigator != null) navigator.back(); else super.onBackPressed(); }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (!app.ready) return;
        boolean changed = Theme.of(this).dark != paletteDark; applyTheme();
        // Orientation itself never destroys a draft/dialog. Only a changed system palette rebuilds Views.
        if (changed && app.store.theme().equals("system") && navigator != null) navigator.rebuild();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (navigator != null) state.putInt("tab", navigator.rootTab());
        state.putString("install_project", pendingInstallProject); state.putString("export_project", pendingExportProject);
        if (pendingInstall != null) state.putString("install_artifact", pendingInstall.toJson().toString());
        if (pendingExport != null) state.putString("export_artifact", pendingExport.toJson().toString());
        super.onSaveInstanceState(state);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }
    private void handleIntent(Intent intent) {
        if (intent == null || !initialized || !app.store.paired()) return;
        String projectId = intent.getStringExtra("project_id"); if (projectId == null || projectId.isEmpty()) return;
        String sessionId = intent.getStringExtra("session_id");
        Models.Project project = app.project(projectId);
        if (project != null) openIntentProject(project, sessionId);
        else app.api.projects(new Api.Callback<java.util.List<Models.Project>>() {
            @Override public void success(java.util.List<Models.Project> list) { app.setProjects(list); Models.Project p = app.project(projectId); if (p != null && !isDestroyed()) openIntentProject(p, sessionId); }
            @Override public void error(ApiError error) { showError(error); }
        });
        intent.removeExtra("project_id"); intent.removeExtra("session_id");
    }
    private void openIntentProject(Models.Project project, String session) {
        app.select(project);
        if (session != null && !session.isEmpty() && !app.engine(project).isActive()) app.store.session(project.id, session);
        navigator.root(2);
    }
    public void showError(ApiError error) { if (!isDestroyed() && navigator != null && navigator.current() != null) navigator.current().showError(error); }
    public void copy(String value) { try { ClipboardManager clipboard = getSystemService(ClipboardManager.class); if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy), value)); Widgets.toast(this, R.string.copied); } catch (Exception ex) { Widgets.toast(this, R.string.permission_denied); } }
    public void enableNotifications(boolean enabled) {
        if (!enabled) { app.store.notifications(false); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, NOTIFICATIONS);
        else app.store.notifications(true);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results); boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == NOTIFICATIONS) { app.store.notifications(granted); if (!granted) Widgets.toast(this, R.string.permission_denied); if (navigator != null && navigator.rootTab() == 4) navigator.rebuild(); }
        if (code == WRITE_DOWNLOADS) {
            Models.Artifact a = pendingExport; String project = pendingExportProject; pendingExport = null;
            if (granted && a != null) export(project, a); else Widgets.toast(this, R.string.permission_denied);
        }
    }
    public void install(String project, Models.Artifact artifact) {
        if (!artifact.isApk()) return;
        app.downloads.verified(project, artifact, new Downloads.Result() {
            @Override public void success(File file) {
                if (isDestroyed()) return;
                if (!getPackageManager().canRequestPackageInstalls()) {
                    pendingInstallProject = project; pendingInstall = artifact;
                    Widgets.dialog(MainActivity.this).setTitle(R.string.install).setMessage(R.string.install_explanation)
                        .setNegativeButton(R.string.cancel, (d, w) -> pendingInstall = null)
                        .setPositiveButton(R.string.settings, (d, w) -> {
                            try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), INSTALL_SOURCE); }
                            catch (Exception ex) { pendingInstall = null; Widgets.toast(MainActivity.this, R.string.error_no_handler); }
                        }).show();
                } else launchInstall(file);
            }
            @Override public void error(ApiError error) { showError(error); }
        });
    }
    private void launchInstall(File file) {
        Uri uri = ShareProvider.uri(file);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClipData(ClipData.newRawUri(getString(R.string.install), uri));
        try { startActivity(intent); } catch (Exception ex) { Widgets.toast(this, R.string.error_no_handler); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == INSTALL_SOURCE) {
            Models.Artifact artifact = pendingInstall; String project = pendingInstallProject; pendingInstall = null;
            if (getPackageManager().canRequestPackageInstalls() && artifact != null) install(project, artifact);
            else Widgets.toast(this, R.string.permission_denied);
        }
    }
    public void share(String project, Models.Artifact artifact) {
        app.downloads.verified(project, artifact, new Downloads.Result() {
            @Override public void success(File file) {
                if (isDestroyed()) return; Uri uri = ShareProvider.uri(file);
                Intent intent = new Intent(Intent.ACTION_SEND).setType(ShareProvider.mime(file)).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); intent.setClipData(ClipData.newRawUri(getString(R.string.share), uri));
                try { startActivity(Intent.createChooser(intent, getString(R.string.share))); } catch (Exception ex) { Widgets.toast(MainActivity.this, R.string.error_no_handler); }
            }
            @Override public void error(ApiError error) { showError(error); }
        });
    }
    public void export(String project, Models.Artifact artifact) {
        if (Build.VERSION.SDK_INT == 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingExport = artifact; pendingExportProject = project; requestPermissions(new String[]{ Manifest.permission.WRITE_EXTERNAL_STORAGE }, WRITE_DOWNLOADS); return;
        }
        app.downloads.verified(project, artifact, new Downloads.Result() {
            @Override public void success(File file) { app.downloads.exportPublic(file, artifact, new Api.Callback<Void>() {
                @Override public void success(Void ignored) { if (!isDestroyed()) Widgets.toast(MainActivity.this, R.string.file_saved); }
                @Override public void error(ApiError error) { showError(error); }
            }); }
            @Override public void error(ApiError error) { showError(error); }
        });
    }
    public void rotateCredential(String token, long expected, long operation, Api.Callback<Void> result) {
        app.requests.execute(() -> {
            try { app.store.rotate(token, expected); app.main.post(() -> { if (app.rotationActive(operation)) app.safe(() -> result.success(null)); }); }
            catch (Exception ex) { app.main.post(() -> { if (!app.rotationActive(operation)) return; app.disconnect(); if (!isDestroyed() && navigator != null) navigator.pairing(new ApiError(ApiError.Kind.Storage)); app.safe(() -> result.error(new ApiError(ApiError.Kind.Storage))); }); }
        });
    }
}
