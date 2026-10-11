package com.ocpilot.app.core;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import com.ocpilot.app.App;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.R;

public final class Notifier {
    public static final String TASKS = "tasks", UPDATES = "updates";
    public static final int FOREGROUND_ID = 1;
    private Notifier() { }
    public static void channels(App app) {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(TASKS, app.getString(R.string.notification_channel_tasks), NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(UPDATES, app.getString(R.string.notification_channel_events), NotificationManager.IMPORTANCE_DEFAULT));
    }
    private static PendingIntent intent(App app, String project, String session) {
        Intent i = new Intent(app, MainActivity.class).putExtra("project_id", project).putExtra("session_id", session)
            .setData(new Uri.Builder().scheme("ocpilot").authority("project").appendPath(project).build())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(app, project.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    public static Notification running(App app, String project, String session, String name) {
        return new Notification.Builder(app, TASKS).setSmallIcon(R.drawable.ic_pilot)
            .setContentTitle(app.getString(R.string.notification_running, name)).setContentText(app.getString(R.string.notification_body))
            .setContentIntent(intent(app, project, session)).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS).build();
    }
    public static void update(App app, String project, String session, String name, int title) {
        if (!app.store.notifications()) return;
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = app.getSystemService(NotificationManager.class); if (manager == null) return;
        Notification n = new Notification.Builder(app, UPDATES).setSmallIcon(R.drawable.ic_pilot)
            .setContentTitle(app.getString(title, name)).setContentText(app.getString(R.string.notification_body))
            .setContentIntent(intent(app, project, session)).setAutoCancel(true).build();
        try { manager.notify(100 + (project.hashCode() & 0x3fffffff), n); } catch (Exception ex) { Log.failure(ex); }
    }
}
