package com.ocpilot.app.core;

import com.ocpilot.app.BuildConfig;

/** Only class names can reach logcat. Never accepts request data or Throwable messages. */
public final class Log {
    private Log() { }
    public static void failure(Throwable error) {
        if (BuildConfig.DEBUG) android.util.Log.d("OCPilot", error.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", ""));
    }
}
