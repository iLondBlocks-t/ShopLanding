package com.ocpilot.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.ocpilot.app.App;
import com.ocpilot.app.R;
import com.ocpilot.app.core.Models;
import java.util.Locale;

public final class Widgets {
    private Widgets() { }
    public static LinearLayout column(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    public static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    public static LinearLayout page(Context c) {
        LinearLayout l = column(c); int pad = Theme.dp(c, 16); l.setPaddingRelative(pad, pad, pad, pad); return l;
    }
    public static ScrollView scroll(Context c, View content) { ScrollView s = new ScrollView(c); s.setFillViewport(true); s.addView(content); return s; }
    public static void add(LinearLayout parent, View view) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Theme.dp(parent.getContext(), 10); parent.addView(view, p);
    }
    public static void weighted(LinearLayout row, View view) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.setMarginEnd(Theme.dp(row.getContext(), 6)); row.addView(view, p);
    }
    public static TextView text(Context c, String text) { TextView t = new TextView(c); t.setText(text); t.setTextColor(Theme.of(c).text); t.setTextSize(15); t.setGravity(Gravity.START); t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG); t.setLineSpacing(Theme.dp(c, 3), 1); return t; }
    public static TextView text(Context c, int id) { return text(c, c.getString(id)); }
    public static TextView muted(Context c, String value) { TextView t = text(c, value); t.setTextColor(Theme.of(c).muted); t.setTextSize(13); return t; }
    public static TextView muted(Context c, int id) { return muted(c, c.getString(id)); }
    public static TextView title(Context c, String value) { TextView t = text(c, value); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setTextSize(22); return t; }
    public static TextView title(Context c, int id) { return title(c, c.getString(id)); }
    public static TextView section(Context c, int id) { TextView t = text(c, id); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setTextSize(16); return t; }
    public static TextView mono(Context c, String value) { TextView t = text(c, value); t.setTypeface(Typeface.MONOSPACE); t.setTextSize(13); t.setTextIsSelectable(true); return t; }
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c); int pad = Theme.dp(c, 16); l.setPaddingRelative(pad, pad, pad, pad);
        l.setBackground(Theme.shape(c, Theme.of(c).surface, Theme.of(c).border)); return l;
    }
    private static Button button(Context c, int id, Runnable action, boolean primary) {
        Theme t = Theme.of(c); Button b = new Button(c); b.setText(id); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : t.text); b.setMinHeight(Theme.dp(c, 48)); b.setMinimumHeight(Theme.dp(c, 48));
        b.setPaddingRelative(Theme.dp(c, 10), 0, Theme.dp(c, 10), 0);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(t.accent, 60)), Theme.shape(c, primary ? t.accent : t.surface2, primary ? 0 : t.border), null));
        b.setOnClickListener(v -> ((App) c.getApplicationContext()).safe(action)); return b;
    }
    public static Button primary(Context c, int id, Runnable action) { return button(c, id, action, true); }
    public static Button ghost(Context c, int id, Runnable action) { return button(c, id, action, false); }
    public static EditText field(Context c, int hint, boolean password) {
        Theme t = Theme.of(c); EditText e = new EditText(c); e.setTextSize(15); e.setTextColor(t.text); e.setHintTextColor(t.muted); e.setHint(hint);
        e.setSingleLine(true); e.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        e.setMinHeight(Theme.dp(c, 54)); e.setPaddingRelative(Theme.dp(c, 14), Theme.dp(c, 10), Theme.dp(c, 14), Theme.dp(c, 10));
        e.setBackground(Theme.shape(c, t.surface2, t.border)); if (password) e.setSaveEnabled(false); return e;
    }
    public static TextView chip(Context c, String value, int color) {
        TextView t = text(c, value); t.setTextSize(11); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setTextColor(color);
        t.setPaddingRelative(Theme.dp(c, 10), Theme.dp(c, 6), Theme.dp(c, 10), Theme.dp(c, 6)); t.setBackground(Theme.shape(c, Theme.alpha(color, 25), 0)); return t;
    }
    public static TextView chip(Context c, int id, int color) { return chip(c, c.getString(id), color); }
    public static LinearLayout banner(Context c, String message, int color) {
        LinearLayout l = column(c); int p = Theme.dp(c, 12); l.setPaddingRelative(p, p, p, p); l.setBackground(Theme.shape(c, Theme.alpha(color, 24), Theme.alpha(color, 100)));
        TextView text = text(c, message); text.setTextColor(color); l.addView(text); return l;
    }
    public static View empty(Context c, int message) { LinearLayout card = card(c); add(card, muted(c, message)); return card; }
    public static void toast(Context c, int id) { toast(c, c.getString(id)); }
    public static void toast(Context c, String text) { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); }
    public static AlertDialog.Builder dialog(Activity c) {
        return new AlertDialog.Builder(new ContextThemeWrapper(c, Theme.of(c).dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert));
    }
    public static String bytes(Context c, long n) {
        if (n < 1024) return c.getString(R.string.bytes_format, n);
        if (n < 1048576) return c.getString(R.string.kb_format, n / 1024.0);
        if (n < 1073741824) return c.getString(R.string.mb_format, n / 1048576.0);
        return c.getString(R.string.gb_format, n / 1073741824.0);
    }
    public static String elapsed(Context c, long seconds) { seconds = Math.max(0, seconds); return c.getString(R.string.elapsed_format, seconds / 3600, seconds / 60 % 60, seconds % 60); }
    public static String relative(Context c, String stamp) {
        long time = Models.timestamp(stamp); if (time == 0) return c.getString(R.string.unknown);
        long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60000);
        if (minutes == 0) return c.getString(R.string.just_now);
        if (minutes < 60) return c.getString(R.string.minutes_ago, minutes);
        if (minutes < 1440) return c.getString(R.string.hours_ago, minutes / 60);
        return c.getString(R.string.days_ago, minutes / 1440);
    }
    public static int kind(String kind) {
        switch (kind) { case "apk-debug": return R.string.kind_apk_debug; case "apk-release": return R.string.kind_apk_release;
            case "apk": return R.string.kind_apk; case "zip": return R.string.kind_zip; case "log": return R.string.kind_log; default: return R.string.kind_other; }
    }
    public static int state(String state) {
        switch (state) { case "working": return R.string.working; case "queued": return R.string.queued; case "connecting": return R.string.connecting;
            case "waiting_permission":
            case "permission": return R.string.waiting_permission;
            case "completed": return R.string.completed; case "failed": return R.string.failed; case "cancelled": return R.string.cancelled; default: return R.string.unknown; }
    }
}
