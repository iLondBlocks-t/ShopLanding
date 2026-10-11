package com.ocpilot.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import com.ocpilot.app.App;

public final class Theme {
    public final boolean dark;
    public final int background, surface, surface2, border, text, muted;
    public final int accent = Color.rgb(124, 92, 255), accent2 = Color.rgb(59, 130, 246);
    public final int success = Color.rgb(34, 197, 94), warning = Color.rgb(245, 158, 11), error = Color.rgb(239, 68, 68);
    private Theme(boolean dark) {
        this.dark = dark;
        background = Color.parseColor(dark ? "#0B0D14" : "#F3F4FA");
        surface = Color.parseColor(dark ? "#131726" : "#FFFFFF");
        surface2 = Color.parseColor(dark ? "#1A1F33" : "#ECEEFA");
        border = Color.parseColor(dark ? "#232842" : "#DADDED");
        text = Color.parseColor(dark ? "#E6E8F2" : "#1B2035");
        muted = Color.parseColor(dark ? "#8B90A8" : "#646B85");
    }
    public static Theme of(Context context) {
        App app = (App) context.getApplicationContext(); String mode = app.ready ? app.store.theme() : "dark";
        boolean systemDark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return new Theme(mode.equals("dark") || (mode.equals("system") && systemDark));
    }
    public static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    public static int alpha(int color, int alpha) { return (color & 0x00ffffff) | (alpha << 24); }
    public static GradientDrawable shape(Context c, int color, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c, 14));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke); return g;
    }
}
