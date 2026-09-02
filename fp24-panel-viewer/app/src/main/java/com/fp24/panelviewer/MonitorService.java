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
import android.webkit.RenderProcessGoneDetail;
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

public final class MonitorService extends Service {
    private static final String ACTION_START = "com.fp24.panelviewer.START_MONITORING";
    private static final String ACTION_STOP = "com.fp24.panelviewer.STOP_MONITORING";
    private static final String ALLOWED_HOST = "panel.freeplay24.com";
    private static final String[] MONITOR_URLS = {
            "https://panel.freeplay24.com/deposits",
            "https://panel.freeplay24.com/withdrawals"
    };
    private static final long HEALTH_CHECK_MS = 20_000L;
    private static final long STALE_BRIDGE_MS = 65_000L;
    private static final long SNAPSHOT_SWITCH_MS = 90_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView monitorView;
    private String bridgeScript = "";
    private long lastHeartbeat;
    private long lastNavigation;
    private int activeUrlIndex;
    private boolean shuttingDown;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            if (shuttingDown) {
                return;
            }
            if (!hasInternet() || monitorView == null) {
                handler.postDelayed(this, HEALTH_CHECK_MS);
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastNavigation >= SNAPSHOT_SWITCH_MS) {
                activeUrlIndex = (activeUrlIndex + 1) % MONITOR_URLS.length;
                loadActiveMonitorUrl();
            } else if (now - lastHeartbeat >= STALE_BRIDGE_MS) {
                reloadMonitor();
            } else {
                WebView current = monitorView;
                current.evaluateJavascript(
                        "(function(){return !!window.__fp24BridgeHealthV4;})()",
                        result -> {
                            if (current == monitorView && !"true".equals(result)) {
                                injectBridge(current);
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
        createMonitor();
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

    private void createMonitor() {
        if (shuttingDown || monitorView != null) {
            return;
        }

        WebView monitor = new WebView(this);
        monitor.setBackgroundColor(0xFF05080D);
        WebSettings settings = monitor.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " FP24Monitor/4.0");

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
                if (view != monitorView) {
                    return;
                }
                Uri uri = Uri.parse(finishedUrl);
                String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
                if (ALLOWED_HOST.equalsIgnoreCase(uri.getHost())
                        && (path.equals("/login") || path.equals("/logout"))) {
                    NotificationHelper.showSessionExpiredNotificationOnce(MonitorService.this);
                    stopSelf();
                    return;
                }
                NotificationHelper.clearSessionExpired(MonitorService.this);
                lastNavigation = System.currentTimeMillis();
                lastHeartbeat = lastNavigation;
                injectBridge(view);
            }

            @Override
            public void onReceivedSslError(
                    WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == monitorView) {
                    disposeMonitor();
                    handler.postDelayed(MonitorService.this::createMonitor, 1200L);
                }
                return true;
            }
        });
        monitorView = monitor;
        loadActiveMonitorUrl();
    }

    private void injectBridge(WebView target) {
        if (target == null || bridgeScript == null || bridgeScript.isEmpty()) {
            return;
        }
        target.evaluateJavascript(bridgeScript, null);
    }

    private void loadActiveMonitorUrl() {
        if (monitorView == null || shuttingDown) {
            return;
        }
        long now = System.currentTimeMillis();
        lastNavigation = now;
        lastHeartbeat = now;
        monitorView.loadUrl(MONITOR_URLS[activeUrlIndex]);
    }

    private void reloadMonitor() {
        if (monitorView == null || shuttingDown) {
            return;
        }
        lastNavigation = System.currentTimeMillis();
        lastHeartbeat = lastNavigation;
        monitorView.reload();
    }

    private void disposeMonitor() {
        WebView old = monitorView;
        monitorView = null;
        if (old == null) {
            return;
        }
        try {
            old.removeJavascriptInterface("PanelBridge");
            old.stopLoading();
            old.removeAllViews();
            old.destroy();
        } catch (RuntimeException ignored) {
            // The renderer may already be gone.
        }
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
                    if (!shuttingDown && monitorView != null) {
                        reloadMonitor();
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
            handler.post(() -> lastHeartbeat = System.currentTimeMillis());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        handler.removeCallbacksAndMessages(null);
        if (connectivityManager != null && networkCallback != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // Already unregistered by Android.
            }
        }
        disposeMonitor();
        super.onDestroy();
    }
}
