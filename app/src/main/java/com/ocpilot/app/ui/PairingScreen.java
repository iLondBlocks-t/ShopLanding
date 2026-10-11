package com.ocpilot.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.R;
import com.ocpilot.app.core.Api;
import com.ocpilot.app.core.ApiError;
import com.ocpilot.app.core.Models;
import com.ocpilot.app.core.UrlUtil;
import java.util.Locale;

public final class PairingScreen extends Screen {
    private EditText address, code;
    private TextView progress;
    private Button connect;
    private LinearLayout health;
    private String addressValue = "", codeValue = "";
    private boolean busy;
    public PairingScreen(MainActivity activity, ApiError error) { super(activity); lastError = error; addressValue = app.store.server(); }
    @Override public View build(Context c) {
        LinearLayout page = Widgets.page(c);
        android.widget.ImageView logo = new android.widget.ImageView(c); logo.setImageResource(R.drawable.ic_pilot); logo.setColorFilter(Theme.of(c).accent);
        page.addView(logo, new LinearLayout.LayoutParams(Theme.dp(c, 64), Theme.dp(c, 64)));
        Widgets.add(page, Widgets.title(c, R.string.pair_subtitle)); Widgets.add(page, Widgets.muted(c, R.string.brand_tagline));
        LinearLayout card = Widgets.card(c); Widgets.add(card, Widgets.section(c, R.string.server_address));
        address = Widgets.field(c, R.string.server_hint, false); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setTextDirection(View.TEXT_DIRECTION_LTR); address.setText(addressValue); Widgets.add(card, address);
        Widgets.add(card, Widgets.section(c, R.string.link_code)); code = Widgets.field(c, R.string.link_code_hint, false);
        code.setTypeface(Typeface.MONOSPACE); code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setSaveEnabled(false); code.setTextDirection(View.TEXT_DIRECTION_LTR); code.setText(codeValue); Widgets.add(card, code);
        code.addTextChangedListener(new TextWatcher() {
            private boolean changing;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) {
                if (changing) return; String raw = editable.toString().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
                if (raw.length() > 8) raw = raw.substring(0, 8);
                String formatted = raw.length() >= 4 ? raw.substring(0, 4) + "-" + raw.substring(4) : raw;
                codeValue = formatted;
                if (!formatted.equals(editable.toString())) { changing = true; editable.replace(0, editable.length(), formatted); code.setSelection(editable.length()); changing = false; }
            }
        });
        Widgets.add(card, Widgets.muted(c, R.string.link_code_help)); health = Widgets.column(c); Widgets.add(card, health);
        progress = Widgets.muted(c, R.string.checking_health); progress.setVisibility(busy ? View.VISIBLE : View.GONE); Widgets.add(card, progress);
        connect = Widgets.primary(c, R.string.connect, this::connect); connect.setEnabled(!busy); Widgets.add(card, connect); Widgets.add(page, card);
        return shell(c, Widgets.scroll(c, page), R.string.pair_title, false);
    }
    private void setBusy(boolean value, int text) { busy = value; if (visible) { connect.setEnabled(!value); address.setEnabled(!value); code.setEnabled(!value); progress.setText(text); progress.setVisibility(value ? View.VISIBLE : View.GONE); } }
    private void connect() {
        if (busy) return; clearError(); String server;
        try { server = UrlUtil.normalize(address.getText().toString()); }
        catch (IllegalArgumentException ex) { address.setError(activity.getString(R.string.error_address)); return; }
        String linkCode = code.getText().toString();
        if (!linkCode.matches("[A-Z0-9]{4}-[A-Z0-9]{4}")) { code.setError(activity.getString(R.string.error_code_format)); return; }
        addressValue = server; address.setText(server); health.removeAllViews();
        if (server.startsWith("http://")) Widgets.add(health, Widgets.banner(activity, activity.getString(R.string.http_warning), Theme.of(activity).warning));
        long credentialEpoch = app.store.generation(); Api pairingApi = new Api(app, server); setBusy(true, R.string.checking_health);
        track(pairingApi.health(new Api.Callback<Models.Health>() {
            @Override public void success(Models.Health result) {
                if (!visible) return;
                Widgets.add(health, Widgets.chip(activity, activity.getString(R.string.gateway_health, result.gateway,
                    activity.getString(result.running ? R.string.running : R.string.not_running)), result.ok ? Theme.of(activity).success : Theme.of(activity).error));
                if (!result.ok) { setBusy(false, R.string.connect); showError(new ApiError(ApiError.Kind.Server)); return; }
                setBusy(true, R.string.pairing);
                String deviceName = Build.MANUFACTURER + " " + Build.MODEL;
                // Redemption is deliberately not screen-cancelled: rotating a palette must not discard a minted credential.
                pairingApi.redeem(linkCode, deviceName, new Api.Callback<Models.Pairing>() {
                    @Override public void success(Models.Pairing pairing) {
                        codeValue = ""; if (visible) code.setText("");
                        app.requests.execute(() -> {
                            try {
                                app.store.saveCredentials(server, pairing.deviceId, deviceName, pairing.deviceToken, credentialEpoch);
                                app.main.post(() -> { busy = false; app.notifyPaired(); });
                            } catch (Exception ex) { app.main.post(() -> { setBusy(false, R.string.connect); activity.showError(new ApiError(ApiError.Kind.Storage)); }); }
                        });
                    }
                    @Override public void error(ApiError error) { setBusy(false, R.string.connect); if (visible) showError(error); }
                });
            }
            @Override public void error(ApiError error) { setBusy(false, R.string.connect); if (visible) showError(error); }
        }));
    }
    @Override public void onShow() { super.onShow(); connect.setEnabled(!busy); address.setEnabled(!busy); code.setEnabled(!busy); }
    @Override public void onHide() {
        if (address != null) addressValue = address.getText().toString(); if (code != null) codeValue = code.getText().toString(); super.onHide();
        // A health probe can be retried; a redemption already in flight is allowed to finish securely.
        if (progress != null && progress.getText().toString().equals(activity.getString(R.string.checking_health))) busy = false;
    }
    @Override public void destroy() { super.destroy(); codeValue = ""; if (code != null) code.setText(""); }
}
