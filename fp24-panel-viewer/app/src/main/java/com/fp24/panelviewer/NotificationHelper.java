package com.fp24.panelviewer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class NotificationHelper {
    static final String REQUEST_CHANNEL_ID = "fp24_requests";
    static final String MONITOR_CHANNEL_ID = "fp24_monitoring";
    static final int MONITOR_NOTIFICATION_ID = 2402;

    private static final AtomicInteger NEXT_NOTIFICATION_ID = new AtomicInteger(5000);
    private static final Map<String, Long> RECENT_EVENTS = new LinkedHashMap<>();
    private static final Map<String, Long> LAST_TYPE_EVENT = new LinkedHashMap<>();
    private static final String PREFS = "fp24_notification_state";
    private static final String READY_SHOWN_V2 = "ready_shown_v2";

    private NotificationHelper() {
    }

    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build();

        NotificationChannel requests = new NotificationChannel(
                REQUEST_CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        requests.setDescription(context.getString(R.string.channel_description));
        requests.enableVibration(true);
        requests.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes);
        requests.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(requests);

        NotificationChannel monitor = new NotificationChannel(
                MONITOR_CHANNEL_ID,
                context.getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription(context.getString(R.string.monitor_channel_description));
        monitor.enableVibration(false);
        monitor.setSound(null, null);
        monitor.setShowBadge(false);
        monitor.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        manager.createNotificationChannel(monitor);
    }

    static boolean canPostNotifications(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && !manager.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = manager.getNotificationChannel(REQUEST_CHANNEL_ID);
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    static void showReadyNotificationOnce(Context context) {
        if (!canPostNotifications(context)) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(READY_SHOWN_V2, false)) {
            return;
        }
        prefs.edit().putBoolean(READY_SHOWN_V2, true).apply();
        showNotification(
                context,
                context.getString(R.string.ready_title),
                context.getString(R.string.ready_body),
                false,
                NEXT_NOTIFICATION_ID.incrementAndGet());
    }

    static Notification buildMonitoringNotification(Context context) {
        return buildNotification(
                context,
                context.getString(R.string.monitor_title),
                context.getString(R.string.monitor_body),
                true,
                MONITOR_CHANNEL_ID);
    }

    static void showRequestNotification(Context context, String rawType, String eventId) {
        if (!canPostNotifications(context)) {
            return;
        }

        String type = rawType == null ? "" : rawType.toLowerCase(Locale.ROOT);
        if (!type.contains("deposit") && !type.contains("withdraw")) {
            return;
        }
        if (!rememberEvent(type, eventId)) {
            return;
        }

        final String title;
        final String body;
        if (type.contains("withdraw")) {
            title = context.getString(R.string.withdraw_title);
            body = context.getString(R.string.withdraw_body);
        } else {
            title = context.getString(R.string.deposit_title);
            body = context.getString(R.string.deposit_body);
        }
        showNotification(context, title, body, false, NEXT_NOTIFICATION_ID.incrementAndGet());
    }

    private static synchronized boolean rememberEvent(String type, String eventId) {
        long now = System.currentTimeMillis();
        String cleanId = eventId == null ? "" : eventId.trim();

        if (!cleanId.isEmpty()) {
            String key = type + ':' + cleanId;
            if (RECENT_EVENTS.containsKey(key)) {
                return false;
            }
            RECENT_EVENTS.put(key, now);
            while (RECENT_EVENTS.size() > 200) {
                String oldest = RECENT_EVENTS.keySet().iterator().next();
                RECENT_EVENTS.remove(oldest);
            }
            return true;
        }

        Long last = LAST_TYPE_EVENT.get(type);
        if (last != null && now - last < 2000) {
            return false;
        }
        LAST_TYPE_EVENT.put(type, now);
        return true;
    }

    private static void showNotification(
            Context context, String title, String body, boolean ongoing, int id) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(id, buildNotification(
                context, title, body, ongoing, REQUEST_CHANNEL_ID));
    }

    private static Notification buildNotification(
            Context context, String title, String body, boolean ongoing, String channelId) {
        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, ongoing ? 2402 : 0, openIntent, pendingFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, channelId);
        } else {
            builder = new Notification.Builder(context);
        }
        builder.setSmallIcon(R.drawable.ic_notification)
                .setColor(Color.rgb(24, 195, 126))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setVisibility(ongoing ? Notification.VISIBILITY_SECRET : Notification.VISIBILITY_PRIVATE)
                .setCategory(ongoing ? Notification.CATEGORY_SERVICE : Notification.CATEGORY_STATUS);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !ongoing) {
            builder.setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL);
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.build();
    }
}
