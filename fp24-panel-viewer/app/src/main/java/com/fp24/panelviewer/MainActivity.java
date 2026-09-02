package com.fp24.panelviewer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private static final String START_URL = "https://panel.freeplay24.com/login";
    private static final String ALLOWED_HOST = "panel.freeplay24.com";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2401;
    private static final String APP_PREFS = "fp24_app_state";
    private static final String BATTERY_PROMPT_SHOWN = "battery_prompt_shown_v3";

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefreshLayout;
    private boolean notificationSettingsOffered;
    private boolean batteryDialogVisible;
    private boolean rendererRestarting;
    private int blankPageRetries;
    private String darkThemeScript = "";
    private String passwordAutofillScript = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(5, 8, 13));
        getWindow().setNavigationBarColor(Color.rgb(5, 8, 13));
        NotificationHelper.createChannels(this);
        darkThemeScript = readAssetQuietly("dark_theme.js");
        passwordAutofillScript = readAssetQuietly("password_autofill.js");

        FrameLayout root = buildScreen();
        setContentView(root);
        applySystemBarInsets(root);
        requestNotificationPermissionIfNeeded();

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL);
        }
    }

    private FrameLayout buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 8, 13));

        swipeRefreshLayout = new SwipeRefreshLayout(this);
        swipeRefreshLayout.setColorSchemeColors(
                Color.rgb(24, 195, 126),
                Color.rgb(31, 162, 184),
                Color.rgb(255, 193, 7));
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(Color.rgb(17, 27, 39));
        swipeRefreshLayout.setDistanceToTriggerSync(dp(86));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(5, 8, 13));
        webView.setSaveEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            webView.setAutofillHints(View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_PASSWORD);
        }
        configureWebView();
        swipeRefreshLayout.addView(webView, new SwipeRefreshLayout.LayoutParams(
                SwipeRefreshLayout.LayoutParams.MATCH_PARENT,
                SwipeRefreshLayout.LayoutParams.MATCH_PARENT));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            blankPageRetries = 0;
            webView.reload();
        });
        swipeRefreshLayout.setOnChildScrollUpCallback(
                (parent, child) -> webView.canScrollVertically(-1));

        root.addView(swipeRefreshLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        root.addView(progressBar, progressParams);

        return root;
    }

    private void applySystemBarInsets(View root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSaveFormData(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " FP24PanelViewer/4.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                if (newProgress >= 100) {
                    swipeRefreshLayout.setRefreshing(false);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUri(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.setBackgroundColor(Color.rgb(5, 8, 13));
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                injectPageHelpers(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefreshLayout.setRefreshing(false);
                injectPageHelpers(view);
                CookieManager.getInstance().flush();
                updateMonitoringForUrl(url);
                view.requestLayout();
                view.invalidate();
                verifyPageIsNotBlank(view, url);
            }

            @Override
            public void onReceivedSslError(
                    WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(MainActivity.this, R.string.ssl_error, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onReceivedError(
                    WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    swipeRefreshLayout.setRefreshing(false);
                    Toast.makeText(MainActivity.this, R.string.page_error, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                restartVisibleWebView();
                return true;
            }
        });
    }

    private boolean handleUri(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("https".equalsIgnoreCase(scheme) && ALLOWED_HOST.equalsIgnoreCase(host)) {
            return false;
        }

        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            }
        }
        return true;
    }

    private void updateMonitoringForUrl(String rawUrl) {
        Uri uri = Uri.parse(rawUrl);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
        if (!ALLOWED_HOST.equalsIgnoreCase(uri.getHost())) {
            return;
        }
        if (path.equals("/login") || path.equals("/logout")) {
            MonitorService.stop(this);
        } else {
            NotificationHelper.clearSessionExpired(this);
            MonitorService.start(this);
            webView.postDelayed(this::offerBatteryOptimizationOnce, 900L);
        }
    }

    private void injectPageHelpers(WebView target) {
        if (darkThemeScript != null && !darkThemeScript.isEmpty()) {
            target.evaluateJavascript(darkThemeScript, null);
        }
        if (passwordAutofillScript != null && !passwordAutofillScript.isEmpty()) {
            target.evaluateJavascript(passwordAutofillScript, null);
        }
    }

    private void verifyPageIsNotBlank(WebView target, String expectedUrl) {
        target.postDelayed(() -> {
            if (target != webView || expectedUrl == null
                    || !expectedUrl.equals(target.getUrl())) {
                return;
            }
            target.evaluateJavascript(
                    "(function(){var b=document.body;if(!b)return 0;"
                            + "var t=(b.innerText||b.textContent||'').trim().length;"
                            + "return t+(b.children?b.children.length*10:0);})()",
                    result -> {
                        int score = 0;
                        try {
                            score = Integer.parseInt(String.valueOf(result).replace("\"", ""));
                        } catch (NumberFormatException ignored) {
                            // A non-numeric result is treated as a failed render.
                        }
                        if (score > 10) {
                            blankPageRetries = 0;
                            return;
                        }
                        if (blankPageRetries < 2) {
                            blankPageRetries += 1;
                            target.clearCache(false);
                            target.reload();
                        } else {
                            restartVisibleWebView();
                        }
                    });
        }, 1400L);
    }

    private void restartVisibleWebView() {
        if (rendererRestarting || isFinishing()) {
            return;
        }
        rendererRestarting = true;
        MonitorService.stop(this);
        Toast.makeText(this, R.string.page_recovered, Toast.LENGTH_SHORT).show();
        Intent restart = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        getWindow().getDecorView().postDelayed(() -> {
            startActivity(restart);
            finish();
        }, 250L);
    }

    private String readAssetQuietly(String name) {
        StringBuilder builder = new StringBuilder();
        try (InputStream input = getAssets().open(name);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } catch (IOException ignored) {
            return "";
        }
        return builder.toString();
    }

    private void offerBatteryOptimizationOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || batteryDialogVisible) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(BATTERY_PROMPT_SHOWN, false)) {
            return;
        }
        batteryDialogVisible = true;
        prefs.edit().putBoolean(BATTERY_PROMPT_SHOWN, true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_body)
                .setNegativeButton(R.string.not_now,
                        (dialog, which) -> batteryDialogVisible = false)
                .setPositiveButton(R.string.allow_background, (dialog, which) -> {
                    batteryDialogVisible = false;
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException ignored) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:" + getPackageName())));
                    }
                })
                .setOnDismissListener(dialog -> batteryDialogVisible = false)
                .show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        } else {
            NotificationHelper.showReadyNotificationOnce(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            NotificationHelper.showReadyNotificationOnce(this);
        } else {
            offerNotificationSettings();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null && !NotificationHelper.canPostNotifications(this)) {
            webView.postDelayed(this::offerNotificationSettings, 700L);
        }
    }

    private void offerNotificationSettings() {
        if (notificationSettingsOffered || NotificationHelper.canPostNotifications(this)) {
            return;
        }
        notificationSettingsOffered = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_disabled_title)
                .setMessage(R.string.notification_disabled_body)
                .setNegativeButton(R.string.not_now, null)
                .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(intent);
                })
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null && !rendererRestarting) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.removeAllViews();
                webView.destroy();
            } catch (RuntimeException ignored) {
                // The renderer may already be gone; Android owns the remaining cleanup.
            }
            webView = null;
        }
        super.onDestroy();
    }
}
