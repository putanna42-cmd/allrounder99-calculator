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

import org.json.JSONArray;
import org.json.JSONException;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class NotificationHelper {
    static final String REQUEST_CHANNEL_ID = "fp24_requests_v3";
    static final String MONITOR_CHANNEL_ID = "fp24_monitoring";
    static final int MONITOR_NOTIFICATION_ID = 2402;

    private static final String OLD_REQUEST_CHANNEL_ID = "fp24_requests";
    private static final String PREFS = "fp24_notification_state";
    private static final String READY_SHOWN_V3 = "ready_shown_v3";
    private static final String SESSION_EXPIRED_SHOWN = "session_expired_shown_v3";
    private static final String SEEN_EVENTS = "seen_events_v3";
    private static final String SNAPSHOT_READY_PREFIX = "snapshot_ready_v3_";
    private static final String NEXT_ID = "next_notification_id_v3";
    private static final int MAX_REMEMBERED_EVENTS = 1200;

    private NotificationHelper() {
    }

    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.deleteNotificationChannel(OLD_REQUEST_CHANNEL_ID);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel requests = new NotificationChannel(
                REQUEST_CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        requests.setDescription(context.getString(R.string.channel_description));
        requests.enableVibration(true);
        requests.setVibrationPattern(new long[]{0, 250, 120, 350});
        requests.enableLights(true);
        requests.setLightColor(Color.rgb(24, 195, 126));
        requests.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes);
        requests.setShowBadge(true);
        requests.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
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
        if (prefs.getBoolean(READY_SHOWN_V3, false)) {
            return;
        }
        prefs.edit().putBoolean(READY_SHOWN_V3, true).apply();
        showNotification(
                context,
                context.getString(R.string.ready_title),
                context.getString(R.string.ready_body),
                false,
                nextNotificationId(context));
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

        String type = normalizeType(rawType);
        if (type.isEmpty()) {
            return;
        }

        String cleanId = cleanEventId(eventId);
        if (!cleanId.isEmpty() && !rememberEvent(context, type + ':' + cleanId)) {
            return;
        }

        final String title;
        final String body;
        if ("withdraw".equals(type)) {
            title = context.getString(R.string.withdraw_title);
            body = context.getString(R.string.withdraw_body);
        } else {
            title = context.getString(R.string.deposit_title);
            body = context.getString(R.string.deposit_body);
        }
        showNotification(context, title, body, false, nextNotificationId(context));
    }

    static synchronized void processSnapshot(
            Context context, String rawType, String snapshotJson) {
        String type = normalizeType(rawType);
        if (type.isEmpty() || snapshotJson == null) {
            return;
        }

        Set<String> snapshotKeys = new HashSet<>();
        try {
            JSONArray values = new JSONArray(snapshotJson);
            for (int i = 0; i < values.length(); i++) {
                String id = cleanEventId(values.optString(i, ""));
                if (!id.isEmpty()) {
                    snapshotKeys.add(type + ':' + id);
                }
            }
        } catch (JSONException ignored) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String baselineKey = SNAPSHOT_READY_PREFIX + type;
        if (!prefs.getBoolean(baselineKey, false)) {
            Set<String> seen = loadSeenEvents(prefs);
            seen.addAll(snapshotKeys);
            trimSeenEvents(seen);
            prefs.edit()
                    .putStringSet(SEEN_EVENTS, seen)
                    .putBoolean(baselineKey, true)
                    .apply();
            return;
        }

        for (String key : snapshotKeys) {
            int separator = key.indexOf(':');
            String id = separator >= 0 ? key.substring(separator + 1) : key;
            showRequestNotification(context, type, id);
        }
    }

    static void showSessionExpiredNotificationOnce(Context context) {
        if (!canPostNotifications(context)) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(SESSION_EXPIRED_SHOWN, false)) {
            return;
        }
        prefs.edit().putBoolean(SESSION_EXPIRED_SHOWN, true).apply();
        showNotification(
                context,
                context.getString(R.string.session_title),
                context.getString(R.string.session_body),
                false,
                nextNotificationId(context));
    }

    static void clearSessionExpired(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(SESSION_EXPIRED_SHOWN, false)
                .apply();
    }

    private static String normalizeType(String rawType) {
        String type = rawType == null ? "" : rawType.toLowerCase(Locale.ROOT);
        if (type.contains("withdraw")) {
            return "withdraw";
        }
        if (type.contains("deposit")) {
            return "deposit";
        }
        return "";
    }

    private static String cleanEventId(String eventId) {
        if (eventId == null) {
            return "";
        }
        String clean = eventId.trim();
        return clean.length() <= 180 ? clean : clean.substring(0, 180);
    }

    private static synchronized boolean rememberEvent(Context context, String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> seen = loadSeenEvents(prefs);
        if (seen.contains(key)) {
            return false;
        }
        seen.add(key);
        trimSeenEvents(seen);
        prefs.edit().putStringSet(SEEN_EVENTS, seen).apply();
        return true;
    }

    private static Set<String> loadSeenEvents(SharedPreferences prefs) {
        return new HashSet<>(prefs.getStringSet(SEEN_EVENTS, new HashSet<>()));
    }

    private static void trimSeenEvents(Set<String> seen) {
        if (seen.size() <= MAX_REMEMBERED_EVENTS) {
            return;
        }
        int removeCount = seen.size() - (MAX_REMEMBERED_EVENTS / 2);
        for (String key : new HashSet<>(seen)) {
            if (removeCount-- <= 0) {
                break;
            }
            seen.remove(key);
        }
    }

    private static synchronized int nextNotificationId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int next = prefs.getInt(NEXT_ID, 10000) + 1;
        if (next >= Integer.MAX_VALUE - 1000) {
            next = 10001;
        }
        prefs.edit().putInt(NEXT_ID, next).apply();
        return next;
    }

    private static void showNotification(
            Context context, String title, String body, boolean ongoing, int id) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(id, buildNotification(
                context, title, body, ongoing,
                ongoing ? MONITOR_CHANNEL_ID : REQUEST_CHANNEL_ID));
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
                context, ongoing ? MONITOR_NOTIFICATION_ID : 0, openIntent, pendingFlags);

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
                .setOnlyAlertOnce(ongoing)
                .setShowWhen(!ongoing)
                .setVisibility(ongoing
                        ? Notification.VISIBILITY_SECRET
                        : Notification.VISIBILITY_PUBLIC)
                .setCategory(ongoing
                        ? Notification.CATEGORY_SERVICE
                        : Notification.CATEGORY_MESSAGE);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !ongoing) {
            builder.setPriority(Notification.PRIORITY_MAX)
                    .setDefaults(Notification.DEFAULT_ALL);
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.build();
    }
}
