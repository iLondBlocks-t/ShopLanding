package com.ocpilot.app.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.os.SystemClock;
import com.ocpilot.app.App;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.R;
import java.util.ArrayList;
import java.util.List;

public final class Navigator {
    private final MainActivity activity;
    private final App app;
    private final FrameLayout host;
    private final LinearLayout bottom;
    private final List<Screen> stack = new ArrayList<>();
    private long lastBack;
    private final int[] labels = { R.string.home, R.string.projects, R.string.workspace, R.string.builds, R.string.settings };
    private final int[] icons = { R.drawable.ic_home, R.drawable.ic_projects, R.drawable.ic_workspace, R.drawable.ic_builds, R.drawable.ic_settings };
    public Navigator(MainActivity activity, FrameLayout host, LinearLayout bottom) {
        this.activity = activity; this.app = (App) activity.getApplication(); this.host = host; this.bottom = bottom;
    }
    public Screen current() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }
    public int rootTab() { return stack.isEmpty() ? 0 : Math.max(0, stack.get(0).rootTab()); }
    public void root(int tab) {
        if (!app.store.paired()) { pairing(null); return; }
        if ((tab == 2 || tab == 3) && app.selected() == null) tab = 1;
        Screen next;
        switch (tab) { case 1: next = new ProjectsScreen(activity); break; case 2: next = new WorkspaceScreen(activity); break;
            case 3: next = new BuildsScreen(activity); break; case 4: next = new SettingsScreen(activity); break; default: next = new HomeScreen(activity); }
        replace(next);
    }
    public void pairing(com.ocpilot.app.core.ApiError error) { replace(new PairingScreen(activity, error)); }
    private void replace(Screen screen) {
        for (Screen old : stack) old.destroy(); stack.clear(); stack.add(screen); mount();
    }
    public void push(Screen screen) { if (current() != null) current().onHide(); stack.add(screen); mount(); }
    private void mount() {
        host.removeAllViews(); Screen screen = current(); if (screen == null) return;
        host.addView(screen.build(activity), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.onShow(); renderBottom();
        host.setAlpha(0.8f); host.animate().alpha(1).setDuration(150).start();
    }
    private void renderBottom() {
        bottom.removeAllViews(); Screen screen = current();
        bottom.setVisibility(stack.size() == 1 && screen != null && screen.rootTab() >= 0 && app.store.paired() ? View.VISIBLE : View.GONE);
        Theme t = Theme.of(activity); bottom.setBackgroundColor(t.surface);
        for (int i = 0; i < labels.length; i++) {
            final int index = i; boolean selected = screen != null && screen.rootTab() == i;
            LinearLayout tab = Widgets.column(activity); tab.setGravity(android.view.Gravity.CENTER); tab.setMinimumHeight(Theme.dp(activity, 64));
            tab.setPaddingRelative(0, Theme.dp(activity, 8), 0, Theme.dp(activity, 6));
            ImageView icon = new ImageView(activity); icon.setImageResource(icons[i]); icon.setColorFilter(selected ? t.accent : t.muted);
            tab.addView(icon, new LinearLayout.LayoutParams(Theme.dp(activity, 22), Theme.dp(activity, 22)));
            TextView text = Widgets.text(activity, labels[i]); text.setTextSize(10); text.setTextColor(selected ? t.accent : t.muted); tab.addView(text);
            tab.setContentDescription(activity.getString(labels[i])); tab.setOnClickListener(v -> app.safe(() -> root(index)));
            bottom.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
    }
    public void back() {
        Screen screen = current(); if (screen != null && screen.onBack()) return;
        if (stack.size() > 1) { stack.remove(stack.size() - 1).destroy(); mount(); return; }
        long now = SystemClock.elapsedRealtime();
        if (lastBack > 0 && now - lastBack < 1800) activity.finish(); else { lastBack = now; Widgets.toast(activity, R.string.back_again); }
    }
    public void rebuild() { Screen screen = current(); if (screen != null) { screen.onHide(); mount(); } }
    public void pause() { if (current() != null) current().onPause(); }
    public void resume() { if (current() != null) current().onResume(); }
    public void destroy() { for (Screen screen : stack) screen.destroy(); stack.clear(); }
}
