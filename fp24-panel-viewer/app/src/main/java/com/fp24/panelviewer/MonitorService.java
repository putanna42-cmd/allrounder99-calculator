package com.fp24.panelviewer;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.IBinder;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MonitorService extends Service {
    private static final String ACTION_START = "com.fp24.panelviewer.START_MONITORING";
    private static final String ACTION_STOP = "com.fp24.panelviewer.STOP_MONITORING";
    private static final String ALLOWED_HOST = "panel.freeplay24.com";
    private static final String[] MONITOR_URLS = {
            "https://panel.freeplay24.com/deposits",
            "https://panel.freeplay24.com/withdrawals"
    };

    private final List<WebView> monitorViews = new ArrayList<>();

    static void start(Context context) {
        Intent intent = new Intent(context, MonitorService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, MonitorService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createChannels(this);
        Notification notification = NotificationHelper.buildMonitoringNotification(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NotificationHelper.MONITOR_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NotificationHelper.MONITOR_NOTIFICATION_ID, notification);
        }

        for (String url : MONITOR_URLS) {
            createMonitor(url);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void createMonitor(String url) {
        WebView monitor = new WebView(this);
        WebSettings settings = monitor.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " FP24Monitor/2.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(monitor, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        monitor.addJavascriptInterface(new ServiceBridge(), "PanelBridge");
        monitor.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !"https".equalsIgnoreCase(uri.getScheme())
                        || !ALLOWED_HOST.equalsIgnoreCase(uri.getHost());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String rawUrl) {
                Uri uri = Uri.parse(rawUrl);
                return !"https".equalsIgnoreCase(uri.getScheme())
                        || !ALLOWED_HOST.equalsIgnoreCase(uri.getHost());
            }

            @Override
            public void onPageFinished(WebView view, String finishedUrl) {
                super.onPageFinished(view, finishedUrl);
                injectBridge(view);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }
        });
        monitorViews.add(monitor);
        monitor.loadUrl(url);
    }

    private void injectBridge(WebView view) {
        try {
            view.evaluateJavascript(readAsset("notification_bridge.js"), null);
        } catch (IOException ignored) {
            // The foreground service remains alive and will retry on the next page load.
        }
    }

    private String readAsset(String name) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (InputStream input = getAssets().open(name);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private final class ServiceBridge {
        @JavascriptInterface
        public void onEvent(String eventType, String eventId) {
            NotificationHelper.showRequestNotification(
                    MonitorService.this, eventType, eventId);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        for (WebView monitor : monitorViews) {
            monitor.removeJavascriptInterface("PanelBridge");
            monitor.stopLoading();
            monitor.destroy();
        }
        monitorViews.clear();
        super.onDestroy();
    }
}
