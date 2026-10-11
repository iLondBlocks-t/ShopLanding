package com.ocpilot.app.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.ocpilot.app.App;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.R;
import com.ocpilot.app.core.Api;
import com.ocpilot.app.core.ApiError;
import com.ocpilot.app.core.HttpCall;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public abstract class Screen {
    protected final MainActivity activity;
    protected final App app;
    protected boolean visible;
    private final List<HttpCall> calls = new ArrayList<>();
    private final List<android.app.Dialog> dialogs = new ArrayList<>();
    protected <T extends android.app.Dialog> T keep(T dialog) { dialogs.add(dialog); return dialog; }
    private LinearLayout banners;
    protected ApiError lastError;
    public Screen(MainActivity activity) { this.activity = activity; app = (App) activity.getApplication(); }
    public abstract View build(Context context);
    public int rootTab() { return -1; }
    public void onShow() { visible = true; refreshBanners(); }
    public void onHide() { visible = false; for (HttpCall call : calls) call.cancel(); calls.clear(); for (android.app.Dialog dialog : dialogs) dialog.dismiss(); dialogs.clear(); }
    public void onTaskChanged() { }
    public void onPause() { }
    public void onResume() { }
    public boolean onBack() { return false; }
    public void destroy() { onHide(); }
    public HttpCall track(HttpCall call) { calls.add(call); return call; }
    protected <T> Api.Callback<T> callback(Consumer<T> success) {
        return new Api.Callback<T>() {
            @Override public void success(T value) { if (visible) { clearError(); success.accept(value); } }
            @Override public void error(ApiError error) { if (visible) showError(error); }
        };
    }
    protected View shell(Context c, View content, int title, boolean back) {
        Theme t = Theme.of(c); LinearLayout shell = Widgets.column(c); shell.setBackgroundColor(t.background);
        LinearLayout header = Widgets.row(c); header.setPaddingRelative(Theme.dp(c, 16), Theme.dp(c, 12), Theme.dp(c, 16), Theme.dp(c, 8));
        if (back) header.addView(Widgets.ghost(c, R.string.back, () -> activity.navigator.back()));
        android.widget.TextView name = Widgets.title(c, title); name.setPaddingRelative(Theme.dp(c, 8), 0, 0, 0);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        shell.addView(header); banners = Widgets.column(c); banners.setPaddingRelative(Theme.dp(c, 16), 0, Theme.dp(c, 16), 0); shell.addView(banners);
        shell.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1)); refreshBanners(); return shell;
    }
    public void refreshBanners() {
        if (banners == null) return;
        banners.removeAllViews(); Context c = activity; Theme t = Theme.of(c);
        if (app.store.server().startsWith("http://")) Widgets.add(banners, Widgets.banner(c, c.getString(R.string.http_warning), t.warning));
        if (!app.networkAvailable()) Widgets.add(banners, Widgets.banner(c, c.getString(R.string.no_network), t.warning));
        if (!app.store.crashNote().isEmpty()) {
            LinearLayout note = Widgets.banner(c, c.getString(R.string.crash_note, app.store.crashNote()), t.warning);
            note.addView(Widgets.ghost(c, R.string.close, () -> { app.store.dismissCrash(); refreshBanners(); })); Widgets.add(banners, note);
        }
        if (lastError != null) {
            LinearLayout error = Widgets.banner(c, lastError.message(c), t.error);
            if (!lastError.detail.isEmpty()) Widgets.add(error, Widgets.muted(c, lastError.detail));
            error.addView(Widgets.ghost(c, R.string.close, this::clearError)); Widgets.add(banners, error);
        }
    }
    public void showError(ApiError error) { lastError = error; refreshBanners(); }
    public void clearError() { lastError = null; refreshBanners(); }
}
