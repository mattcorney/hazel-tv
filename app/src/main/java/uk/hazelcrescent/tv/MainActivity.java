package uk.hazelcrescent.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Hazel Crescent TV launcher.
 * Opens tv.html from Home Assistant full screen. The page does all the work and still
 * updates itself; this app adds what a browser can't: opening other apps, a proper icon,
 * offering itself as a home screen, and videos that start on their own.
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_URL = "http://192.168.68.27:8123/local/tv.html";
    private static final String PREFS = "hazeltv";

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean showingError = false;
    private boolean backLongPressed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(6, 9, 18));
        setContentView(web);
        hideSystemBars();

        if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // keeps the launcher's settings and sign-in
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);    // background videos start by themselves
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " HazelTV/1.0");

        // the page calls fully.startApplication(...) – same name as Fully Kiosk, so tv.html needs no changes
        web.addJavascriptInterface(new Bridge(), "fully");

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (Build.VERSION.SDK_INT >= 21 && req.isForMainFrame()) showOffline();
            }
            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
                if (failingUrl != null && failingUrl.equals(currentUrl())) showOffline();
            }
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) {
                // Older Fire TVs don't trust the certificate on Apple's aerial video server.
                // Allow that one server only; everything else stays strict.
                String host = null;
                try { host = Uri.parse(error.getUrl()).getHost(); } catch (Exception e) { /* ignore */ }
                if (host != null && (host.equals("sylvan.apple.com") || host.endsWith(".apple.com"))) h.proceed();
                else h.cancel();
            }
        });

        load();
    }

    private String currentUrl() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.getString("url", DEFAULT_URL);
    }

    private void load() {
        showingError = false;
        web.loadUrl(currentUrl());
    }

    /** Home Assistant not reachable (Wi-Fi still connecting, HA restarting...): say so and keep trying. */
    private void showOffline() {
        if (showingError) return;
        showingError = true;
        String html = "<html><body style='margin:0;background:#060912;color:#efe8dc;font-family:sans-serif;display:flex;"
                + "align-items:center;justify-content:center;height:100vh;text-align:center'><div>"
                + "<div style='font-size:34px;font-weight:300'>Can’t reach Home Assistant</div>"
                + "<div style='font-size:20px;opacity:.7;margin-top:14px'>" + escape(currentUrl()) + "</div>"
                + "<div style='font-size:20px;opacity:.7;margin-top:28px'>Trying again every 10 seconds.<br>Hold the Back button to change the address.</div>"
                + "</div></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() { @Override public void run() { if (showingError) load(); } }, 10000);
    }

    private static String escape(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void askForAddress() {
        final EditText box = new EditText(this);
        box.setSingleLine(true);
        box.setText(currentUrl());
        box.setSelection(box.getText().length());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Home Assistant address")
                .setMessage("The full address of tv.html, for example\nhttp://192.168.68.27:8123/local/tv.html")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String u = box.getText().toString().trim();
                    if (!u.isEmpty()) {
                        if (!u.startsWith("http")) u = "http://" + u;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("url", u).apply();
                    }
                    load();
                })
                .setNeutralButton("Reload", (d, w) -> load())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /* ---- remote control: Back goes to the page (closes panels); holding Back opens the address box ---- */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) { backLongPressed = false; event.startTracking(); }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { backLongPressed = true; askForAddress(); return true; }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!backLongPressed && event.isTracking() && !event.isCanceled()) sendBackToPage();
            backLongPressed = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() { sendBackToPage(); }    // never close: this is the home screen

    private void sendBackToPage() {
        if (showingError) { load(); return; }
        web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));", null);
    }

    @Override
    protected void onNewIntent(Intent intent) {        // Home pressed while already open
        super.onNewIntent(intent);
        if (showingError) load();
    }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); hideSystemBars(); }

    @Override
    protected void onPause() { web.onPause(); super.onPause(); }   // pauses the background video while an app is open

    @Override
    public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars(); }

    private void hideSystemBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /* ---- what the page can ask the app to do ---- */
    private class Bridge {
        @JavascriptInterface
        public boolean startApplication(final String pkg) {
            handler.post(() -> openApp(pkg));
            return true;
        }
        @JavascriptInterface
        public boolean isAppInstalled(String pkg) {
            try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
        }
        @JavascriptInterface
        public String getAppVersion() { return "HazelTV 1.0"; }
        @JavascriptInterface
        public void changeAddress() { handler.post(MainActivity.this::askForAddress); }
    }

    private void openApp(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = null;
        try {
            if (Build.VERSION.SDK_INT >= 21) i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) {   // e.g. Amazon's own home screen, which only answers to "home"
                Intent h = new Intent(Intent.ACTION_MAIN);
                h.addCategory(Intent.CATEGORY_HOME);
                h.setPackage(pkg);
                if (pm.resolveActivity(h, 0) != null) i = h;
            }
        } catch (Exception e) { i = null; }
        if (i == null) {
            Toast.makeText(this, "That app isn’t installed on this TV (" + pkg + ")", Toast.LENGTH_LONG).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); }
        catch (Exception e) { Toast.makeText(this, "Couldn’t open " + pkg, Toast.LENGTH_LONG).show(); }
    }
}
