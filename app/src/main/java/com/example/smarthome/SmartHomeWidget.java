package com.example.smarthome;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen widget for the most common smart-home controls. */
public final class SmartHomeWidget extends AppWidgetProvider {
    public static final String ACTION_LIGHT1 = "com.example.smarthome.WIDGET_LIGHT1";
    public static final String ACTION_FAN1 = "com.example.smarthome.WIDGET_FAN1";
    public static final String ACTION_LIGHT2 = "com.example.smarthome.WIDGET_LIGHT2";
    public static final String ACTION_FAN2 = "com.example.smarthome.WIDGET_FAN2";
    public static final String ACTION_OPEN = "com.example.smarthome.WIDGET_OPEN";

    private static final String PREFS = "widget_state";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_OPEN.equals(action)) {
            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(open);
            return;
        }
        String command = commandFor(action);
        if (command == null) return;
        WidgetBle.send(context, command);
        refreshAll(context);
    }

    private static String commandFor(String action) {
        if (ACTION_LIGHT1.equals(action)) return "r1_light_toggle";
        if (ACTION_FAN1.equals(action)) return "r1_fan_toggle";
        if (ACTION_LIGHT2.equals(action)) return "r2_light_toggle";
        if (ACTION_FAN2.equals(action)) return "r2_fan_toggle";
        return null;
    }

    private static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName name = new ComponentName(context, SmartHomeWidget.class);
        int[] ids = manager.getAppWidgetIds(name);
        for (int id : ids) update(context, manager, id);
    }

    private static void update(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_smart_home);
        views.setOnClickPendingIntent(R.id.widget_light1, pending(context, ACTION_LIGHT1, id));
        views.setOnClickPendingIntent(R.id.widget_fan1, pending(context, ACTION_FAN1, id));
        views.setOnClickPendingIntent(R.id.widget_light2, pending(context, ACTION_LIGHT2, id));
        views.setOnClickPendingIntent(R.id.widget_fan2, pending(context, ACTION_FAN2, id));
        views.setOnClickPendingIntent(R.id.widget_open, pending(context, ACTION_OPEN, id));
        manager.updateAppWidget(id, views);
    }

    private static PendingIntent pending(Context c, String action, int id) {
        Intent i = new Intent(c, SmartHomeWidget.class).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, action.hashCode() ^ id, i, flags);
    }

    /** Small adapter so widget clicks use the same native BLE bridge implementation. */
    private static final class WidgetBle {
        static void send(Context context, String command) {
            BLEService.send(context, command);
        }
    }
}
