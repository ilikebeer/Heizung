package de.heizung.steuerung;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Energie - die App zeigt die Energie-Weboberflaeche aus dem ioBroker (Script "Heizung_Visu") im Vollbild an.
 *
 * Standardwerte (Adresse, Benutzer, Passwort) stehen direkt unten. Sie lassen sich jederzeit in der App aendern:
 *  - ganz unten auf der Seite steht der Link "App-Einstellungen" (wird von der App eingeblendet)
 *  - bei einem Verbindungsfehler gibt es im Fehlerbild die Taste "Adresse aendern"
 *  - Zuruecksetzen auf die Standardwerte: im Einstellungsfenster die Taste "Standard"
 * Die Einstellungen bleiben nach dem Schliessen der App erhalten.
 */
public class MainActivity extends Activity {

    // ---- Standardwerte (hier anpassen, wenn sich die Adresse aendert) ----
    private static final String DEFAULT_URL  = "http://192.168.178.56:8100";
    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASS = "1234";

    private static final String PREFS    = "heizung";
    private static final String KEY_URL  = "url";
    private static final String KEY_USER = "user";
    private static final String KEY_PASS = "pass";

    private static final int COLOR_BG     = Color.parseColor("#0A0C11");
    private static final int COLOR_ACCENT = Color.parseColor("#FF7A1A");
    private static final int COLOR_MUTED  = Color.parseColor("#8B93A5");
    private static final long STALE_MS    = 10L * 60L * 1000L;   // nach so langer Pause die Seite neu laden

    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView web;
    private ProgressBar progress;
    private LinearLayout errorView;
    private TextView errorTitle;
    private TextView errorText;

    private boolean loadFailed = false;
    private int authTries = 0;
    private long pausedAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        web = new WebView(this);
        web.setBackgroundColor(COLOR_BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(48), dp(48));
        pl.gravity = Gravity.CENTER;
        root.addView(progress, pl);

        buildErrorView();
        root.addView(errorView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        setupWebView();
        loadPage();
    }

    // ------------------------------------------------------------------
    //  Aufbau
    // ------------------------------------------------------------------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildErrorView() {
        errorView = new LinearLayout(this);
        errorView.setOrientation(LinearLayout.VERTICAL);
        errorView.setGravity(Gravity.CENTER);
        errorView.setBackgroundColor(COLOR_BG);
        errorView.setPadding(dp(28), dp(28), dp(28), dp(28));
        errorView.setVisibility(View.GONE);

        errorTitle = new TextView(this);
        errorTitle.setTextColor(Color.WHITE);
        errorTitle.setTextSize(22);
        errorTitle.setGravity(Gravity.CENTER);
        errorView.addView(errorTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        errorText = new TextView(this);
        errorText.setTextColor(COLOR_MUTED);
        errorText.setTextSize(15);
        errorText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(12);
        tp.bottomMargin = dp(28);
        errorView.addView(errorText, tp);

        Button retry = new Button(this);
        retry.setText("Erneut versuchen");
        retry.setAllCaps(false);
        retry.setTextSize(17);
        retry.setTextColor(Color.WHITE);
        retry.setBackgroundColor(COLOR_ACCENT);
        retry.setOnClickListener(v -> loadPage());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        errorView.addView(retry, bp);

        Button change = new Button(this);
        change.setText("Adresse ändern");
        change.setAllCaps(false);
        change.setTextColor(COLOR_MUTED);
        change.setBackgroundColor(Color.TRANSPARENT);
        change.setOnClickListener(v -> askSettings());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        cp.topMargin = dp(10);
        errorView.addView(change, cp);
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void openSettings() {
                runOnUiThread(() -> askSettings());
            }
        }, "HeizungApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                loadFailed = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                authTries = 0;
                if (!loadFailed) {
                    errorView.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                    injectSettingsLink();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("Keine Verbindung",
                            "Der ioBroker ist gerade nicht erreichbar.\n\nBitte prüfen, ob das WLAN verbunden ist.\n\n(" + error.getDescription() + ")");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    int code = response.getStatusCode();
                    if (code == 401) {
                        showError("Anmeldung abgelehnt", "Benutzer oder Passwort stimmen nicht.\nÜber „Adresse ändern“ kann beides angepasst werden.");
                    } else if (code == 503) {
                        showError("Keine Antwort", "Der Server läuft, aber das Logik-Script ist nicht aktiv.\nBitte später erneut versuchen.");
                    } else {
                        showError("Fehler " + code, "Die Seite konnte nicht geladen werden.");
                    }
                }
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                authTries++;
                if (authTries <= 2) {
                    handler.proceed(prefs.getString(KEY_USER, DEFAULT_USER), prefs.getString(KEY_PASS, DEFAULT_PASS));
                } else {
                    handler.cancel();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                Uri base = Uri.parse(currentUrl());
                String th = target.getHost();
                String bh = base.getHost();
                if (th != null && bh != null && th.equals(bh)) {
                    return false;                       // eigene Seite bleibt in der App
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, target));
                } catch (Exception e) {
                    // kein Programm zum Oeffnen vorhanden
                }
                return true;
            }
        });

        // Ohne diesen Client zeigt WebView keine Rueckfragen an (z. B. "Steuerung einschalten?")
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setNegativeButton("Abbrechen", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.confirm())
                        .show();
                return true;
            }
        });
    }

    // ------------------------------------------------------------------
    //  Laden / Fehler
    // ------------------------------------------------------------------
    private String currentUrl() {
        return prefs.getString(KEY_URL, DEFAULT_URL);
    }

    private void loadPage() {
        loadFailed = false;
        authTries = 0;
        errorView.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        web.loadUrl(currentUrl());
    }

    private void showError(String title, String text) {
        loadFailed = true;
        progress.setVisibility(View.GONE);
        web.setVisibility(View.INVISIBLE);
        errorTitle.setText(title);
        errorText.setText(text);
        errorView.setVisibility(View.VISIBLE);
    }

    /** Blendet unten auf der Seite (in der Fusszeile) den Link "App-Einstellungen" ein. */
    private void injectSettingsLink() {
        String js = "(function(){"
                + "if(document.getElementById('app-settings-link'))return;"
                + "var f=document.querySelector('footer')||document.body;"
                + "var a=document.createElement('a');"
                + "a.id='app-settings-link';a.href='#';a.textContent='App-Einstellungen';"
                + "a.style.cssText='color:#ffb347;font-weight:600;margin-left:8px';"
                + "a.onclick=function(e){e.preventDefault();HeizungApp.openSettings();return false;};"
                + "f.appendChild(document.createTextNode(' \\u00b7 '));f.appendChild(a);"
                + "})();";
        web.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------------------
    //  Einstellungen
    // ------------------------------------------------------------------
    private EditText field(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(inputType);
        return e;
    }

    private void askSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(10), dp(22), 0);

        final EditText url = field("Adresse, z. B. http://192.168.178.56:8100", currentUrl(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText user = field("Benutzer", prefs.getString(KEY_USER, DEFAULT_USER), InputType.TYPE_CLASS_TEXT);
        final EditText pass = field("Passwort", prefs.getString(KEY_PASS, DEFAULT_PASS), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(url);
        box.addView(user);
        box.addView(pass);

        new AlertDialog.Builder(this)
                .setTitle("Energie verbinden")
                .setView(box)
                .setPositiveButton("Speichern", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (u.length() > 0 && !u.startsWith("http://") && !u.startsWith("https://")) {
                        u = "http://" + u;
                    }
                    while (u.endsWith("/")) {
                        u = u.substring(0, u.length() - 1);
                    }
                    if (u.length() == 0) {
                        u = DEFAULT_URL;
                    }
                    prefs.edit()
                            .putString(KEY_URL, u)
                            .putString(KEY_USER, user.getText().toString().trim())
                            .putString(KEY_PASS, pass.getText().toString())
                            .apply();
                    loadPage();
                })
                .setNeutralButton("Standard", (d, w) -> {
                    prefs.edit().remove(KEY_URL).remove(KEY_USER).remove(KEY_PASS).apply();
                    loadPage();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ------------------------------------------------------------------
    //  Lebenszyklus
    // ------------------------------------------------------------------
    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        boolean stale = pausedAt > 0 && (System.currentTimeMillis() - pausedAt) > STALE_MS;
        if (errorView.getVisibility() == View.VISIBLE || stale) {
            loadPage();
        }
    }

    @Override
    public void onBackPressed() {
        if (errorView.getVisibility() != View.VISIBLE && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }
}
