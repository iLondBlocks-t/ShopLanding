package com.ocpilot.app.core;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import com.ocpilot.app.App;

/** The App owns SSE and reducers; the service owns their foreground lifetime. */
public final class TaskWatchService extends Service {
    private void foreground() {
        App app = (App) getApplication();
        android.app.Notification n = Notifier.running(app, app.watchingProject(), app.watchingSession(), app.watchingName());
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notifier.FOREGROUND_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(Notifier.FOREGROUND_ID, n);
    }
    @Override public void onCreate() { super.onCreate(); foreground(); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { foreground(); return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { stopForeground(true); super.onDestroy(); }
}
