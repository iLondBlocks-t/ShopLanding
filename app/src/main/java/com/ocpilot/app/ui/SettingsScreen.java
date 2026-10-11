package com.ocpilot.app.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.BuildConfig;
import com.ocpilot.app.R;
import com.ocpilot.app.core.Api;
import com.ocpilot.app.core.ApiError;
import com.ocpilot.app.core.Models;
import java.util.ArrayList;
import java.util.List;

public final class SettingsScreen extends Screen {
    private List<Models.Device> devices = new ArrayList<>();
    private DeviceAdapter adapter;
    private LinearLayout serverCard;
    private boolean online, rotating;
    public SettingsScreen(MainActivity activity) { super(activity); }
    @Override public int rootTab() { return 4; }
    @Override public View build(Context c) {
        ListView list = new ListView(c); list.setDivider(null); list.setDividerHeight(0);
        LinearLayout header = Widgets.page(c); serverCard = Widgets.card(c); Widgets.add(header, serverCard); renderServer();
        LinearLayout session = Widgets.card(c); Widgets.add(session, Widgets.section(c, R.string.session_settings));
        Widgets.add(session, Widgets.ghost(c, R.string.rotate_credential, this::rotate));
        Widgets.add(session, Widgets.ghost(c, R.string.disconnect, () -> confirmDisconnect(false)));
        Widgets.add(session, Widgets.ghost(c, R.string.revoke_disconnect, () -> confirmDisconnect(true))); Widgets.add(header, session);
        LinearLayout connection = Widgets.card(c); Widgets.add(connection, Widgets.section(c, R.string.connection));
        Widgets.add(connection, Widgets.ghost(c, R.string.max_reconnect, () -> seconds(true)));
        Widgets.add(connection, Widgets.muted(c, c.getString(R.string.seconds, app.store.reconnectCap())));
        Widgets.add(connection, Widgets.ghost(c, R.string.request_timeout, () -> seconds(false)));
        Widgets.add(connection, Widgets.muted(c, c.getString(R.string.seconds, app.store.readTimeout()))); Widgets.add(header, connection);
        LinearLayout notifications = Widgets.card(c); Switch toggle = new Switch(c); toggle.setText(R.string.notifications); toggle.setTextColor(Theme.of(c).text); toggle.setMinHeight(Theme.dp(c, 48)); toggle.setChecked(app.store.notifications());
        toggle.setOnCheckedChangeListener((button, enabled) -> activity.enableNotifications(enabled)); Widgets.add(notifications, toggle); Widgets.add(notifications, Widgets.muted(c, R.string.notifications_help)); Widgets.add(header, notifications);
        LinearLayout appearance = Widgets.card(c); Widgets.add(appearance, Widgets.ghost(c, R.string.theme, this::theme)); Widgets.add(header, appearance);
        LinearLayout data = Widgets.card(c); Widgets.add(data, Widgets.section(c, R.string.data));
        Widgets.add(data, Widgets.ghost(c, R.string.clear_cache, () -> keep(Widgets.dialog(activity).setMessage(R.string.clear_cache_confirm).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm, (d, w) -> {
            if (app.tasksRunning() || app.downloads.anyActive()) { Widgets.toast(activity, R.string.stop_first); return; }
            app.clearCached(new Api.Callback<Void>() {
                @Override public void success(Void ignored) { if (visible) { Widgets.toast(activity, R.string.cache_cleared); activity.navigator.rebuild(); } }
                @Override public void error(ApiError error) { if (visible) showError(error); }
            });
        }).create()).show())); Widgets.add(header, data);
        Widgets.add(header, Widgets.section(c, R.string.devices)); list.addHeaderView(header, null, false); adapter = new DeviceAdapter(); list.setAdapter(adapter);
        return shell(c, list, R.string.settings, false);
    }
    @Override public void onShow() { super.onShow(); load(); }
    private void load() {
        track(app.api.health(new Api.Callback<Models.Health>() {
            @Override public void success(Models.Health result) { if (visible) { app.lastHealth = result; online = result.ok; renderServer(); } }
            @Override public void error(ApiError error) { if (visible) { online = false; showError(error); renderServer(); } }
        }));
        track(app.api.status(callback(status -> { app.lastStatus = status; renderServer(); })));
        track(app.api.devices(callback(list -> { devices = list; adapter.notifyDataSetChanged(); })));
    }
    private void renderServer() {
        if (serverCard == null) return; Context c = activity; serverCard.removeAllViews(); Widgets.add(serverCard, Widgets.section(c, R.string.server_address)); Widgets.add(serverCard, Widgets.mono(c, app.store.server()));
        Widgets.add(serverCard, Widgets.chip(c, online ? R.string.live : R.string.offline, online ? Theme.of(c).success : Theme.of(c).warning));
        Models.Status status = app.lastStatus;
        Widgets.add(serverCard, Widgets.muted(c, c.getString(R.string.gateway_version, status == null || status.gatewayVersion.isEmpty() ? c.getString(R.string.unknown) : status.gatewayVersion)));
        Widgets.add(serverCard, Widgets.muted(c, c.getString(R.string.opencode_version, status == null || status.opencodeVersion.isEmpty() ? c.getString(R.string.unknown) : status.opencodeVersion)));
        Widgets.add(serverCard, Widgets.muted(c, c.getString(R.string.app_version, BuildConfig.VERSION_NAME))); Widgets.add(serverCard, Widgets.ghost(c, R.string.refresh, this::load));
    }
    private void seconds(boolean reconnect) {
        int[] values = reconnect ? new int[]{15, 30, 60} : new int[]{10, 30, 60}; String[] labels = new String[values.length]; int selected = 1;
        for (int i = 0; i < values.length; i++) { labels[i] = activity.getString(R.string.seconds, values[i]); if (values[i] == (reconnect ? app.store.reconnectCap() : app.store.readTimeout())) selected = i; }
        keep(Widgets.dialog(activity).setTitle(reconnect ? R.string.max_reconnect : R.string.request_timeout).setSingleChoiceItems(labels, selected, (d, which) -> {
            if (reconnect) { app.store.reconnectCap(values[which]); app.reconnect(); } else app.store.readTimeout(values[which]); d.dismiss(); activity.navigator.rebuild();
        }).setNegativeButton(R.string.cancel, null).create()).show();
    }
    private void theme() {
        String[] modes = {"system", "dark", "light"}; String[] labels = { activity.getString(R.string.theme_system), activity.getString(R.string.theme_dark), activity.getString(R.string.theme_light) }; int selected = 1;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(app.store.theme())) selected = i;
        keep(Widgets.dialog(activity).setTitle(R.string.theme).setSingleChoiceItems(labels, selected, (d, which) -> { app.store.theme(modes[which]); d.dismiss(); activity.applyTheme(); activity.navigator.rebuild(); }).setNegativeButton(R.string.cancel, null).create()).show();
    }
    private void rotate() {
        if (rotating || app.rotationPending()) return; rotating = true; final long generation = app.store.generation(), operation = app.beginRotation();
        // Must survive screen changes: the old credential dies before the response is encrypted.
        app.api.rotate(new Api.Callback<Models.Rotation>() {
            @Override public void success(Models.Rotation result) {
                if (!app.rotationActive(operation)) return;
                activity.rotateCredential(result.deviceToken, generation, operation, new Api.Callback<Void>() {
                    @Override public void success(Void ignored) { rotating = false; app.finishRotation(operation, true); app.reconnect(); if (visible) { Widgets.toast(activity, R.string.credential_rotated); load(); } }
                    @Override public void error(ApiError error) { rotating = false; app.finishRotation(operation, false); }
                });
            }
            @Override public void error(ApiError error) { rotating = false; app.finishRotation(operation, false); if (visible) showError(error); }
        });
    }
    private void confirmDisconnect(boolean revoke) {
        keep(Widgets.dialog(activity).setMessage(R.string.disconnect_confirm).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm, (d, w) -> {
            if (!revoke) { app.disconnect(); activity.navigator.pairing(null); }
            else track(app.api.revokeDevice(app.store.deviceId(), callback(ignored -> { app.disconnect(); activity.navigator.pairing(new ApiError(ApiError.Kind.Unauthorized)); })));
        }).create()).show();
    }
    private void revoke(Models.Device device) {
        keep(Widgets.dialog(activity).setMessage(activity.getString(R.string.revoke_confirm, device.name)).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.revoke, (d, w) -> track(app.api.revokeDevice(device.id, callback(ignored -> {
            if (device.current || device.id.equals(app.store.deviceId())) { app.disconnect(); activity.navigator.pairing(new ApiError(ApiError.Kind.Unauthorized)); } else load();
        })))).create()).show();
    }
    private final class DeviceAdapter extends BaseAdapter {
        @Override public int getCount() { return devices.size(); }
        @Override public Object getItem(int i) { return devices.get(i); }
        @Override public long getItemId(int i) { return devices.get(i).id.hashCode(); }
        @Override public View getView(int i, View recycled, ViewGroup parent) {
            Holder h;
            if (recycled == null) {
                LinearLayout wrap = Widgets.page(activity), card = Widgets.card(activity); h = new Holder(); h.name = Widgets.text(activity, ""); h.dates = Widgets.muted(activity, ""); h.current = Widgets.chip(activity, R.string.current_device, Theme.of(activity).accent2);
                final Holder holder = h; h.revoke = Widgets.ghost(activity, R.string.revoke, () -> revoke(holder.device)); Widgets.add(card, h.name); Widgets.add(card, h.dates); Widgets.add(card, h.current); Widgets.add(card, h.revoke); wrap.addView(card); wrap.setTag(h); recycled = wrap;
            } else h = (Holder) recycled.getTag();
            Models.Device device = devices.get(i); h.device = device; h.name.setText(device.name); h.dates.setText(activity.getString(R.string.device_dates, device.createdAt, device.lastSeenAt)); h.current.setVisibility(device.current || device.id.equals(app.store.deviceId()) ? View.VISIBLE : View.GONE); return recycled;
        }
    }
    private static final class Holder { TextView name, dates, current; android.widget.Button revoke; Models.Device device; }
}
