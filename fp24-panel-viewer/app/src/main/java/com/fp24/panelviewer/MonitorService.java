package com.fp24.panelviewer;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MonitorService extends Service {
    private static final String ACTION_START = "com.fp24.panelviewer.START_MONITORING";
    private static final String ACTION_STOP = "com.fp24.panelviewer.STOP_MONITORING";
    private static final String ALLOWED_HOST = "panel.freeplay24.com";
    private static final String[] MONITOR_URLS = {
            "https://panel.freeplay24.com/deposits",
            "https://panel.freeplay24.com/withdrawals"
    };
    private static final long HEALTH_CHECK_MS = 20_000L;
    private static final long STALE_BRIDGE_MS = 75_000L;
    private static final long FULL_RECONNECT_MS = 180_000L;

    private final List<WebView> monitorViews = new ArrayList<>();
    private final Map<WebView, String> monitorTypes = new HashMap<>();
    private final Map<WebView, Long> lastReloads = new HashMap<>();
    private final Map<String, Long> lastHeartbeats = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String bridgeScript;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            if (!hasInternet()) {
                handler.postDelayed(this, HEALTH_CHECK_MS);
                return;
            }

            long now = System.currentTimeMillis();
            for (WebView view : new ArrayList<>(monitorViews)) {
                String type = monitorTypes.get(view);
                long heartbeat = lastHeartbeats.containsKey(type)
                        ? lastHeartbeats.get(type) : 0L;
                long reload = lastReloads.containsKey(view) ? lastReloads.get(view) : 0L;

                if (now - reload >= FULL_RECONNECT_MS || now - heartbeat >= STALE_BRIDGE_MS) {
                    reloadMonitor(view);
                    continue;
                }

                view.evaluateJavascript(
                        "(function(){return !!window.__fp24BridgeHealthV3;})()",
                        result -> {
                            if (!"true".equals(result)) {
                                injectBridge(view);
                            }
                        });
            }
            handler.postDelayed(this, HEALTH_CHECK_MS);
        }
    };

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NotificationHelper.MONITOR_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NotificationHelper.MONITOR_NOTIFICATION_ID, notification);
        }

        try {
            bridgeScript = readAsset("notification_bridge.js");
        } catch (IOException ignored) {
            bridgeScript = "";
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().flush();
        for (String url : MONITOR_URLS) {
            createMonitor(url);
        }
        registerNetworkCallback();
        handler.postDelayed(healthCheck, HEALTH_CHECK_MS);
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
        String type = url.contains("withdraw") ? "withdraw" : "deposit";
        WebView monitor = new WebView(this);
        monitor.setBackgroundColor(0xFF05080D);
        WebSettings settings = monitor.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " FP24Monitor/3.0");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(true);
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(monitor, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        monitor.addJavascriptInterface(new ServiceBridge(type), "PanelBridge");
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
                Uri uri = Uri.parse(finishedUrl);
                String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
                if (ALLOWED_HOST.equalsIgnoreCase(uri.getHost())
                        && (path.equals("/login") || path.equals("/logout"))) {
                    NotificationHelper.showSessionExpiredNotificationOnce(MonitorService.this);
                    stopSelf();
                    return;
                }
                NotificationHelper.clearSessionExpired(MonitorService.this);
                lastReloads.put(view, System.currentTimeMillis());
                injectBridge(view);
            }

            @Override
            public void onReceivedSslError(
                    WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }
        });
        monitorViews.add(monitor);
        monitorTypes.put(monitor, type);
        lastReloads.put(monitor, System.currentTimeMillis());
        lastHeartbeats.put(type, System.currentTimeMillis());
        monitor.loadUrl(url);
    }

    private void injectBridge(WebView view) {
        if (bridgeScript == null || bridgeScript.isEmpty()) {
            return;
        }
        view.evaluateJavascript(bridgeScript, null);
    }

    private void reloadMonitor(WebView view) {
        if (view == null) {
            return;
        }
        lastReloads.put(view, System.currentTimeMillis());
        String type = monitorTypes.get(view);
        if (type != null) {
            lastHeartbeats.put(type, System.currentTimeMillis());
        }
        view.reload();
    }

    private boolean hasInternet() {
        if (connectivityManager == null) {
            connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        }
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void registerNetworkCallback() {
        connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.postDelayed(() -> {
                    for (WebView view : new ArrayList<>(monitorViews)) {
                        reloadMonitor(view);
                    }
                }, 1500L);
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            networkCallback = null;
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
        private final String pageType;

        ServiceBridge(String pageType) {
            this.pageType = pageType;
        }

        @JavascriptInterface
        public void onEvent(String eventType, String eventId) {
            handler.post(() -> NotificationHelper.showRequestNotification(
                    MonitorService.this, eventType, eventId));
        }

        @JavascriptInterface
        public void onSnapshot(String eventType, String snapshotJson) {
            handler.post(() -> NotificationHelper.processSnapshot(
                    MonitorService.this, eventType, snapshotJson));
        }

        @JavascriptInterface
        public void onHeartbeat() {
            handler.post(() -> lastHeartbeats.put(pageType, System.currentTimeMillis()));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (connectivityManager != null && networkCallback != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // Already unregistered by Android.
            }
        }
        for (WebView monitor : monitorViews) {
            monitor.removeJavascriptInterface("PanelBridge");
            monitor.stopLoading();
            monitor.destroy();
        }
        monitorViews.clear();
        monitorTypes.clear();
        lastReloads.clear();
        lastHeartbeats.clear();
        super.onDestroy();
    }
}
