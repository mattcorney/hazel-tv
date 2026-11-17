package uk.hazelcrescent.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.PendingIntent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.provider.Settings;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.webkit.WebResourceResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u != null && "hazeltv.local".equals(u.getHost())) {
                    byte[] img = appImage(u.getPath());
                    if (img == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
                    return new WebResourceResponse("image/png", null, new ByteArrayInputStream(img));
                }
                return super.shouldInterceptRequest(view, req);
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
        handler.postDelayed(updateCheck, 30000);           // look for a newer app 30 s after starting, then every 6 hours
        handleInstallStatus(getIntent());
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
    protected void onNewIntent(Intent intent) {        // Home pressed while already open, or the installer reporting back
        super.onNewIntent(intent);
        if (handleInstallStatus(intent)) return;
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
        public String getAppVersion() { return "HazelTV " + myVersionName(); }
        /** Every app on this TV that can be opened: [{pkg, name, banner}] */
        @JavascriptInterface
        public String getApps() { return listApps(); }
        @JavascriptInterface
        public void changeAddress() { handler.post(MainActivity.this::askForAddress); }
        /** {current, currentName, latest, latestName, available} */
        @JavascriptInterface
        public String getAppUpdate() {
            try {
                JSONObject o = new JSONObject();
                o.put("current", myVersionCode()); o.put("currentName", myVersionName());
                JSONObject l = latest;
                o.put("latest", l == null ? 0 : l.optInt("versionCode")); o.put("latestName", l == null ? "" : l.optString("versionName"));
                o.put("available", l != null && l.optInt("versionCode") > myVersionCode());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface
        public void checkForAppUpdate() { checkForUpdate(true); }
        @JavascriptInterface
        public void installAppUpdate() { handler.post(MainActivity.this::downloadAndInstall); }
    }

    /* ---- updating itself ----
       Looks for version.json next to tv.html on Home Assistant (if you copied one there) and on GitHub,
       and offers whichever is newer. The TV shows its own Install screen to confirm. */
    private static final String GITHUB_DOWNLOADS = "https://github.com/mattcorney/hazel-tv/releases/latest/download/";
    private static final String ACTION_INSTALL_STATUS = "uk.hazelcrescent.tv.INSTALL_STATUS";
    private volatile JSONObject latest = null;
    private volatile String latestFrom = null;
    private volatile boolean installing = false;

    private final Runnable updateCheck = new Runnable() {
        @Override public void run() { checkForUpdate(false); handler.postDelayed(this, 6 * 60 * 60 * 1000L); }
    };

    private int myVersionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }
    private String myVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private String[] updateSources() {
        String u = currentUrl();
        int slash = u.lastIndexOf('/');
        String local = slash > 8 ? u.substring(0, slash + 1) : null;      // e.g. http://192.168.68.27:8123/local/
        return local != null ? new String[]{local, GITHUB_DOWNLOADS} : new String[]{GITHUB_DOWNLOADS};
    }

    private static String httpText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000); c.setReadTimeout(15000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private void checkForUpdate(final boolean userAsked) {
        new Thread(() -> {
            JSONObject best = null; String bestFrom = null;
            for (String src : updateSources()) {
                try {
                    JSONObject o = new JSONObject(httpText(src + "version.json"));
                    if (best == null || o.optInt("versionCode") > best.optInt("versionCode")) { best = o; bestFrom = src; }
                } catch (Exception e) { /* that source isn't there or can't be reached */ }
            }
            final JSONObject found = best; final String from = bestFrom;
            handler.post(() -> {
                if (found == null) { if (userAsked) toast("Couldn’t check for an update – is the TV online?"); return; }
                latest = found; latestFrom = from;
                if (found.optInt("versionCode") <= myVersionCode()) { if (userAsked) toast("Hazel Crescent is up to date (version " + myVersionName() + ")"); return; }
                long snoozed = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("snoozeUntil", 0);
                if (userAsked || System.currentTimeMillis() > snoozed) offerUpdate();
            });
        }).start();
    }

    private void offerUpdate() {
        JSONObject l = latest; if (l == null || isFinishing()) return;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Hazel Crescent update")
                .setMessage("Version " + l.optString("versionName") + " is ready (this TV has " + myVersionName() + ").\n\nInstall it now? The TV will ask you to confirm.")
                .setPositiveButton("Install", (d, w) -> downloadAndInstall())
                .setNegativeButton("Later", (d, w) -> getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putLong("snoozeUntil", System.currentTimeMillis() + 24 * 60 * 60 * 1000L).apply())
                .show();
    }

    private void downloadAndInstall() {
        if (installing) return;
        if (latestFrom == null) { checkForUpdate(true); return; }
        // Newer Fire TVs and Google TVs need "Install unknown apps" switched on for this app
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast("Switch on “Allow” for Hazel Crescent, then come back and choose Install again");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { toast("Go to Settings → My Fire TV → Developer options → Install unknown apps and switch on Hazel Crescent"); }
            return;
        }
        installing = true;
        toast("Downloading the update…");
        final String url = latestFrom + "hazel-tv.apk";
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url + "?t=" + System.currentTimeMillis()).openConnection();
                c.setInstanceFollowRedirects(true); c.setConnectTimeout(20000); c.setReadTimeout(60000); c.setUseCaches(false);
                if (c.getResponseCode() != 200) throw new Exception("download failed (HTTP " + c.getResponseCode() + ")");
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                int id = pi.createSession(params);
                PackageInstaller.Session s = pi.openSession(id);
                InputStream in = c.getInputStream();
                OutputStream out = s.openWrite("hazel-tv.apk", 0, -1);
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out); out.close(); in.close();
                Intent back = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getActivity(this, 7, back, flags);
                s.commit(pend.getIntentSender());
                s.close();
            } catch (Exception e) {
                final String msg = e.getMessage();
                handler.post(() -> { installing = false; toast("The update didn’t download: " + msg); });
            } finally { if (c != null) c.disconnect(); }
        }).start();
    }

    /** The system installer reports back here: show its Install screen, or say what went wrong. */
    private boolean handleInstallStatus(Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return false;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(confirm); } catch (Exception e) { toast("Couldn’t open the installer"); } }
            return true;
        }
        installing = false;
        if (status == PackageInstaller.STATUS_SUCCESS) toast("Updated");
        else if (status != -999) toast("The update didn’t install: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        return true;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    /* ---- the TV's own apps, with their own artwork ---- */
    private String listApps() {
        PackageManager pm = getPackageManager();
        Map<String, JSONObject> found = new LinkedHashMap<>();
        String[] cats = Build.VERSION.SDK_INT >= 21
                ? new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}
                : new String[]{Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || found.containsKey(pkg)) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("name", String.valueOf(ri.loadLabel(pm)));
                    o.put("banner", banner(pkg) != null);
                    found.put(pkg, o);
                } catch (Exception e) { /* skip this one */ }
            }
        }
        JSONArray a = new JSONArray();
        for (JSONObject o : found.values()) a.put(o);
        return a.toString();
    }

    private Drawable banner(String pkg) {
        PackageManager pm = getPackageManager();
        if (Build.VERSION.SDK_INT < 20) return null;
        try {
            Intent i = Build.VERSION.SDK_INT >= 21 ? pm.getLeanbackLaunchIntentForPackage(pkg) : null;
            if (i != null) {
                ResolveInfo ri = pm.resolveActivity(i, 0);
                if (ri != null) { Drawable d = ri.activityInfo.loadBanner(pm); if (d != null) return d; }
            }
            return pm.getApplicationBanner(pkg);
        } catch (Exception e) { return null; }
    }

    private final Map<String, byte[]> imgCache = new HashMap<>();

    private byte[] appImage(String path) {
        if (path == null) return null;
        synchronized (imgCache) { if (imgCache.containsKey(path)) return imgCache.get(path); }
        byte[] out = null;
        try {
            String[] parts = path.split("/");          // "", "banner"|"icon", package
            if (parts.length >= 3) {
                String kind = parts[1], pkg = parts[2].replace(".png", "");
                Drawable d = kind.equals("banner") ? banner(pkg) : getPackageManager().getApplicationIcon(pkg);
                out = png(d, kind.equals("banner") ? 320 : 192, kind.equals("banner") ? 180 : 192);
            }
        } catch (Exception e) { out = null; }
        synchronized (imgCache) { imgCache.put(path, out); }
        return out;
    }

    private static byte[] png(Drawable d, int w, int h) {
        if (d == null) return null;
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
        if (iw < w) { ih = ih * w / iw; iw = w; }        // small icons drawn larger so they stay sharp
        Bitmap b = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, iw, ih);
        d.draw(c);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
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
