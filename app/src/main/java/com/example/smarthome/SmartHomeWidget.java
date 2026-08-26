package com.example.smarthome;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/** Home-screen controls. Control buttons execute directly; only the explicit Open button launches the app. */
public final class SmartHomeWidget extends AppWidgetProvider {
    private static final String ACTION_COMMAND = "com.example.smarthome.WIDGET_COMMAND";
    private static final String ACTION_OPEN = "com.example.smarthome.WIDGET_OPEN";
    private static final String EXTRA_COMMAND = "command";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_COMMAND.equals(action)) {
            String command = intent.getStringExtra(EXTRA_COMMAND);
            if (command != null && !command.isEmpty()) {
                Intent service = new Intent(context, WidgetBleService.class)
                        .setAction(WidgetBleService.ACTION_SEND)
                        .putExtra(WidgetBleService.EXTRA_COMMAND, command);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
                    else context.startService(service);
                } catch (RuntimeException ignored) {
                    // The user can still use the explicit Open button if the OS blocks background service start.
                }
            }
        } else if (ACTION_OPEN.equals(action)) {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(i);
        }
    }

    private static void update(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_smart_home);
        views.setOnClickPendingIntent(R.id.widget_light1, pending(context, "01", id));
        views.setOnClickPendingIntent(R.id.widget_fan1, pending(context, "F10", id));
        views.setOnClickPendingIntent(R.id.widget_light2, pending(context, "41", id));
        views.setOnClickPendingIntent(R.id.widget_fan2, pending(context, "F20", id));
        views.setOnClickPendingIntent(R.id.widget_open, pendingOpen(context, id));
        manager.updateAppWidget(id, views);
    }

    private static PendingIntent pending(Context context, String command, int id) {
        Intent i = new Intent(context, SmartHomeWidget.class)
                .setAction(ACTION_COMMAND)
                .putExtra(EXTRA_COMMAND, command)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getBroadcast(context, (id * 31) ^ command.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent pendingOpen(Context context, int id) {
        Intent i = new Intent(context, SmartHomeWidget.class)
                .setAction(ACTION_OPEN)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getBroadcast(context, 0x53484f50 ^ id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
