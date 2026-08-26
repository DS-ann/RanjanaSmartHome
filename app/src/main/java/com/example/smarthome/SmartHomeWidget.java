package com.example.smarthome;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen widget for safe, direct smart-home presets. */
public final class SmartHomeWidget extends AppWidgetProvider {
    private static final String ACTION_COMMAND = "com.example.smarthome.WIDGET_COMMAND";
    private static final String ACTION_OPEN = "com.example.smarthome.WIDGET_OPEN";
    private static final String EXTRA_COMMAND = "command";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_OPEN.equals(action)) {
            openApp(context, null);
        } else if (ACTION_COMMAND.equals(action)) {
            openApp(context, intent.getStringExtra(EXTRA_COMMAND));
        }
    }

    private static void openApp(Context context, String command) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (command != null) i.putExtra(EXTRA_COMMAND, command);
        context.startActivity(i);
    }

    private static void update(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_smart_home);
        // Firmware protocol: relay commands are <relay-index><0|1>; fan commands are F<room><speed>.
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
