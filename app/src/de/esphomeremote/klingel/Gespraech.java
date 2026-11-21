package de.esphomeremote.klingel;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.CallAudioState;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Anruf-Bildschirm: beim Klingeln Live-Bild (ohne Mikrofon) mit Annehmen/Ablehnen, nach dem Annehmen Video + Ton
 * in beide Richtungen per WebRTC zu go2rtc (Rückkanal zur Tür über go2rtc). Die Seite läuft in einer WebView mit
 * https-Basisadresse, damit das Mikrofon erlaubt ist; das Aushandeln (SDP) erledigt Java, so gibt es kein CORS.
 */
public class Gespraech extends Activity {
    static final String ANNEHMEN = "de.esphomeremote.klingel.ANNEHMEN";
    static final String MITSCHAUEN = "de.esphomeremote.klingel.MITSCHAUEN";
    private static WeakReference<Gespraech> aktuell = new WeakReference<>(null);

    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView titel, status;
    private LinearLayout leiste, extras, unten, oben, schalterZeile;
    private TextView anzeigeZeile, rundeZeile;
    private LinearLayout leitungZeile;
    private String leitungSchluessel = "";
    private long sprechenSeit;
    private boolean wunschGesetzt;
    private android.os.PowerManager.WakeLock wach;   // über dem Sperrbildschirm greift FLAG_KEEP_SCREEN_ON nicht (10-s-Abschaltung)
    private final Runnable statusHolen = this::statusHolen;
    private long hinweisBis;
    private boolean bedienungAus;
    private final Runnable entschaerfen = () -> { this.oeffnenScharf = false; tuerSetzen("🔓  Tür sofort öffnen", BLAU); };
    private TextView tuerOben;   // "Tür sofort öffnen" oben über dem Kamerabild (Nutzer 2026-11-21), zweimal tippen
    private boolean mitMikro, stumm = true, laut = true, oeffnenScharf;   // Mikrofon startet stumm (Nutzer 2026-11-20)
    private View knopfMikro, knopfLaut, knopfTuer;
    private int letzterZustand = -1;
    private boolean seiteBereit;
    private String wartet;

    // ---------- Lebenszyklus ----------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        App.ctx = getApplicationContext();
        aktuell = new WeakReference<>(this);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bauen();
        if (android.os.Build.VERSION.SDK_INT >= 33) zurueckEinrichten();
        Anruf.beiAenderung = this::zustandZeigen;
        verarbeiten(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); verarbeiten(i); }

    private void verarbeiten(Intent i) {
        if (i != null && ANNEHMEN.equals(i.getAction())) annehmen();
        else if (i != null && MITSCHAUEN.equals(i.getAction()) && Anruf.beitreten(this)) annehmen();
        zustandZeigen();
    }

    /** Zurück (ab Android 13 über OnBackInvokedCallback, Pflicht bei targetSdk 36): Gespräch läuft im Hintergrund weiter. */
    private void zurueckEinrichten() {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (Anruf.zustand == Anruf.AKTIV) moveTaskToBack(true);
            else finishAndRemoveTask();
        });
    }

    /** Wahlschalter Medienkanal: im Gespräch stellen die Tasten direkt die Medien-Lautstärke (Kameraton). */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int k = e.getKeyCode();
        if ((k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
                && Anruf.zustand == Anruf.AKTIV && "medien".equals(Anruf.prefs(this).getString("ton_kanal", "anruf"))) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN)
                getSystemService(android.media.AudioManager.class).adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        k == android.view.KeyEvent.KEYCODE_VOLUME_UP ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER,
                        android.media.AudioManager.FLAG_SHOW_UI);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onResume() { super.onResume(); wachHalten(Anruf.zustand != Anruf.AUS); }

    @Override
    protected void onPause() { wachHalten(false); super.onPause(); }

    @SuppressWarnings("deprecation")
    private void wachHalten(boolean an) {
        if (an && wach == null) {
            wach = getSystemService(android.os.PowerManager.class).newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "Tuerklingel:anruf");
            wach.acquire(30 * 60 * 1000L);
        } else if (!an && wach != null) {
            if (wach.isHeld()) wach.release();
            wach = null;
        }
    }

    @Override
    protected void onDestroy() {
        wachHalten(false);
        if (Anruf.beiAenderung != null && aktuell.get() == this) Anruf.beiAenderung = null;
        h.removeCallbacksAndMessages(null);
        mikHochStoppen();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // ---------- Oberfläche ----------
    // Stil wie die ESPHomeRemote (2026-11-21): fast schwarz, Kacheln mit Rand, Akzentblau, Leiste mit blauer Linie
    static final int BG = 0xFF0E1116, KACHEL = 0xFF1A1F27, RAND = 0xFF2A3340, SCHRIFT = 0xFFF1F3F5, GRAU = 0xFF8B94A3,
            GRUEN_AN = 0xFF16A34A, BLAU = 0xFF3B82F6, BLAU_HELL = 0xFF60A5FA, GRUEN = 0xFF22C55E, ROT = 0xFFEF4444, GELB = 0xFFF59E0B;

    private GradientDrawable kachel(int farbe, int rand) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(farbe);
        if (rand != 0) g.setStroke(dp(1), rand);
        return g;
    }

    /** Schrift der Remote: Audiowide (wie fonts/Audiowide-Regular.ttf in ESPHome). Fehlende Zeichen nimmt Android aus der Systemschrift. */
    static android.graphics.Typeface audiowide;
    static android.graphics.Typeface schrift(android.content.Context c) {
        if (audiowide == null) {
            try { audiowide = c.getResources().getFont(R.font.audiowide); } catch (Exception e) { audiowide = android.graphics.Typeface.DEFAULT; }
        }
        return audiowide;
    }

    private TextView ueberschrift(String t) {
        TextView v = new TextView(this);
        v.setTypeface(schrift(this));
        v.setText(t.toUpperCase(java.util.Locale.GERMANY));
        v.setTextSize(12);
        v.setLetterSpacing(0.08f);
        v.setTextColor(GRAU);
        v.setPadding(dp(2), dp(12), 0, dp(6));
        return v;
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @SuppressLint("SetJavaScriptEnabled")
    private void bauen() {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bruecke(), "A");
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                seiteBereit = true;
                if (wartet != null) { js(wartet); wartet = null; }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest r) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    r.grant(r.getResources());
                else { r.deny(); statusSetzen("Kein Mikrofon-Recht - in der App Türklingel erlauben"); }
            }
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.i(Anruf.TAG, "web: " + m.message()); return true;
            }
        });
        // Schwebend (Nutzer 2026-11-21): Video über den ganzen Bildschirm, darüber oben eine Kopf-Karte und unten das
        // Dock (Stumm/Lautsprecher/Auflegen). Schalter + Schnellantworten klappen per Griff aus dem Dock nach oben.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean quer = dm.widthPixels > dm.heightPixels;
        durchgehend = !"schwebend".equals(Anruf.prefs(this).getString("dock_stil", "durchgehend"));
        int breite = quer ? Math.min(dp(560), (int) (dm.widthPixels * 0.6)) : -1;
        FrameLayout haupt = new FrameLayout(this);
        haupt.setBackgroundColor(Color.BLACK);
        haupt.addView(web, new FrameLayout.LayoutParams(-1, -1));

        // Kopf-Karte
        oben = new LinearLayout(this);
        oben.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable kg = kachel(0xE60E1116, RAND);
        kg.setCornerRadius(dp(18));
        oben.setBackground(kg);
        oben.setPadding(dp(16), dp(10), dp(16), dp(12));
        titel = text(22, SCHRIFT);
        titel.setTypeface(schrift(this));
        status = text(14, GRAU);
        oben.addView(titel);
        oben.addView(status);
        anzeigeZeile = text(13, GRAU);   // Anzeigen aus dem Konfigurator ("Schloss: verriegelt · Tür: zu")
        anzeigeZeile.setPadding(0, dp(2), 0, 0);
        oben.addView(anzeigeZeile);
        rundeZeile = text(13, BLAU_HELL);   // Gesprächsrunde: wer ist dabei, wer spricht, wer leitet
        rundeZeile.setPadding(0, dp(2), 0, 0);
        rundeZeile.setVisibility(View.GONE);
        oben.addView(rundeZeile);
        View linie = new View(this);
        linie.setBackgroundColor(BLAU);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, dp(3));
        llp.topMargin = dp(8);
        oben.addView(linie, llp);
        tuerOben = text(16, Color.WHITE);
        tuerOben.setTypeface(schrift(this));
        tuerOben.setPadding(dp(14), dp(11), dp(14), dp(11));
        tuerOben.setBackground(kachel(BLAU, BLAU_HELL));
        tuerOben.setText("🔓  Tür sofort öffnen");
        tuerOben.setContentDescription("Tür sofort öffnen");
        tuerOben.setOnClickListener(v -> tuerKnopf());
        tuerOben.setVisibility(View.GONE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(10);
        oben.addView(tuerOben, tlp);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(breite, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        olp.setMargins(dp(12), dp(12), dp(12), 0);
        haupt.addView(oben, olp);

        // Unten: ausklappbare Karte (Schalter, Schnellantwort, Knöpfe), Griff, Dock
        unten = new LinearLayout(this);
        unten.setOrientation(LinearLayout.VERTICAL);
        klappe = new LinearLayout(this);
        klappe.setOrientation(LinearLayout.VERTICAL);
        klappe.setPadding(dp(14), 0, dp(14), dp(12));
        LinearLayout blatt = new LinearLayout(this);   // Ziehblatt: Griff oben bleibt stehen, Inhalt rollt
        blatt.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable kl = kachel(0xF00E1116, RAND);
        kl.setCornerRadius(dp(18));
        blatt.setBackground(kl);
        blatt.setClipToOutline(true);
        // Griff oben (zwei Linien): am Finger ziehen = Blatt größer/kleiner, weit nach unten = zu
        LinearLayout ziehen = new LinearLayout(this);
        ziehen.setOrientation(LinearLayout.VERTICAL);
        ziehen.setGravity(Gravity.CENTER);
        ziehen.setPadding(0, dp(10), 0, dp(4));
        for (int i = 0; i < 2; i++) {
            View strich = new View(this);
            GradientDrawable sg = new GradientDrawable(); sg.setColor(GRAU); sg.setCornerRadius(dp(2));
            strich.setBackground(sg);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(i == 0 ? 44 : 30), dp(3));
            slp.topMargin = i == 0 ? 0 : dp(4);
            ziehen.addView(strich, slp);
        }
        ziehen.setContentDescription("Ziehen: größer, kleiner oder zuklappen");
        ziehen.setOnTouchListener(this::ziehenGeste);
        blatt.addView(ziehen, new LinearLayout.LayoutParams(-1, dp(30)));
        tuerKlappe = text(16, Color.WHITE);   // Tür öffnen auch in der Klappe (bequem unten), gleiche Sicherheitsabfrage
        tuerKlappe.setPadding(dp(14), dp(12), dp(14), dp(12));
        tuerKlappe.setBackground(kachel(BLAU, BLAU_HELL));
        tuerKlappe.setText("🔓  Tür sofort öffnen");
        tuerKlappe.setOnClickListener(v -> tuerKnopf());
        tuerKlappe.setVisibility(View.GONE);
        LinearLayout.LayoutParams tkl = new LinearLayout.LayoutParams(-1, -2);
        tkl.topMargin = dp(6);
        klappe.addView(tuerKlappe, tkl);
        leitungZeile = new LinearLayout(this);   // nur für den Leiter: Mitschauen erlauben/sperren, Dazuholen
        leitungZeile.setOrientation(LinearLayout.VERTICAL);
        klappe.addView(leitungZeile, new LinearLayout.LayoutParams(-1, -2));
        schalterZeile = new LinearLayout(this);
        schalterZeile.setOrientation(LinearLayout.VERTICAL);
        klappe.addView(schalterZeile, new LinearLayout.LayoutParams(-1, -2));
        antwortZeile = new LinearLayout(this);
        antwortZeile.setOrientation(LinearLayout.VERTICAL);
        antwortZeile.setVisibility(View.GONE);
        klappe.addView(antwortZeile, new LinearLayout.LayoutParams(-1, -2));
        extras = new LinearLayout(this);
        extras.setOrientation(LinearLayout.VERTICAL);
        klappe.addView(extras, new LinearLayout.LayoutParams(-1, -2));
        android.widget.ScrollView rollen = new android.widget.ScrollView(this);
        rollen.setVerticalScrollBarEnabled(false);
        rollen.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rollen.addView(klappe);
        blatt.addView(rollen, new LinearLayout.LayoutParams(-1, 0, 1));
        blatt.setVisibility(View.GONE);
        klappRahmen = blatt;
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0);
        rlp.bottomMargin = dp(8);
        unten.addView(blatt, rlp);

        griff = text(13, SCHRIFT);   // Griff am Dock: klappt Schalter + Schnellantworten aus/ein
        griff.setPadding(dp(16), dp(6), dp(16), dp(6));
        GradientDrawable gg = kachel(0xE61A1F27, RAND);
        gg.setCornerRadius(dp(14));
        griff.setBackground(gg);
        griff.setOnTouchListener(this::ziehenGeste);   // antippen = aufklappen, nach oben ziehen = Blatt folgt dem Finger
        griff.setVisibility(View.GONE);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(6);
        unten.addView(griff, glp);

        // Dock wie auf der Remote: schwarz, oben blaue Linie; hier schwebend mit runden Ecken und runden Knöpfen
        leiste = new DockLeiste(this);
        GradientDrawable dl = new GradientDrawable(); dl.setColor(BLAU);
        GradientDrawable dk = new GradientDrawable(); dk.setColor(durchgehend ? Color.BLACK : 0xF0000000);
        if (!durchgehend) { dl.setCornerRadius(dp(18)); dk.setCornerRadius(dp(18)); }
        android.graphics.drawable.LayerDrawable dock = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{dl, dk});
        dock.setLayerInset(1, 0, dp(3), 0, 0);
        leiste.setBackground(dock);
        // durchgehend (wie die Aktivitätsleiste der Remote): Linie über die ganze Breite, Knöpfe eckig direkt darunter -
        // der blaue Knopf verschmilzt mit der Linie. schwebend: runde Karte mit runden Knöpfen.
        if (durchgehend) leiste.setPadding(0, dp(3), 0, 0);
        else leiste.setPadding(dp(6), dp(9), dp(6), dp(6));
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        if (durchgehend) {
            LinearLayout karten = unten;   // Klappe + Griff schweben mittig, das Dock geht über die ganze Breite
            unten = new LinearLayout(this);
            unten.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(breite, -2);
            klp.gravity = Gravity.CENTER_HORIZONTAL;
            klp.setMargins(dp(12), 0, dp(12), 0);
            unten.addView(karten, klp);
            unten.addView(leiste, new LinearLayout.LayoutParams(-1, -2));
        } else {
            unten.addView(leiste, new LinearLayout.LayoutParams(-1, -2));
            ulp = new FrameLayout.LayoutParams(breite, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            ulp.setMargins(dp(12), 0, dp(12), dp(12));
        }
        haupt.addView(unten, ulp);

        // Antippen des Bildes: Bedienung aus/ein (Bild frei)
        web.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_UP && e.getEventTime() - e.getDownTime() < 300) {
                bedienungAus = !bedienungAus;
                int sicht = bedienungAus ? View.GONE : View.VISIBLE;
                oben.setVisibility(sicht);
                unten.setVisibility(sicht);
            }
            return false;
        });
        // Randlos (Pflicht ab targetSdk 35): nichts unter Statusleiste/Taskleiste
        haupt.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets i = ins.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return ins;
        });
        setContentView(haupt);
    }

    /** Dock "durchgehend": neben dem blauen Knopf werden die oberen Ecken der Nachbarn rund ausgeschnitten und dahinter
     *  blau gefüllt - die Linie fließt weich in den Knopf (wie die Aktivitätsleiste der Remote, esphomeremote-keys.h). */
    final class DockLeiste extends LinearLayout {
        private final android.graphics.Paint farbe = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        DockLeiste(android.content.Context c) { super(c); setWillNotDraw(false); }
        @Override protected void dispatchDraw(android.graphics.Canvas c) {
            super.dispatchDraw(c);
            if (!durchgehend) return;
            int r = dp(16), n = getChildCount();   // Rundung wie auf der Remote (ca. ein Viertel der Knopfhöhe)
            // Aktiver Knopf: im Gespräch Lautsprecher/Hörer (blau), beim Klingeln Annehmen (grün). Die Linie nimmt seine
            // Farbe an (wie auf der Remote: Linie in der Farbe des aktiven Felds).
            int aktivFarbe = BLAU;
            for (int i = 0; i < n; i++) { int f = farbeVon(getChildAt(i)); if (f == BLAU || f == GRUEN_AN) { aktivFarbe = f; break; } }
            farbe.setColor(aktivFarbe);
            c.drawRect(0, 0, getWidth(), getPaddingTop(), farbe);
            for (int i = 0; i < n; i++) {
                View k = getChildAt(i);
                if (farbeVon(k) != aktivFarbe) continue;
                float oben = k.getTop();
                for (int seite = -1; seite <= 1; seite += 2) {
                    int j = i + seite;
                    if (j < 0 || j >= n) continue;
                    View nb = getChildAt(j);
                    float kante = seite < 0 ? k.getLeft() : k.getRight();      // Kante des blauen Knopfs
                    float nKante = seite < 0 ? nb.getRight() : nb.getLeft();    // Kante des Nachbarn (dazwischen die Fuge)
                    farbe.setColor(aktivFarbe);   // Fuge + Ecke in der Farbe des aktiven Knopfs
                    c.drawRect(Math.min(kante, nKante + seite * r), oben, Math.max(kante, nKante + seite * r), oben + r, farbe);
                    farbe.setColor(farbeVon(nb));   // Nachbar rund ausschneiden
                    c.drawCircle(nKante + seite * r, oben + r, r, farbe);
                    // unter dem Kreis wieder Nachbarfarbe bis zur Ecke (Kreis deckt nur das Viertel)
                }
            }
        }
    }

    private int farbeVon(View k) {
        Object t = k.getTag();
        if (!(t instanceof Object[])) return 0;
        Object f = ((Object[]) t)[3];
        return f instanceof Integer ? (Integer) f : 0;
    }

    private LinearLayout klappe;
    private boolean durchgehend = true;
    private TextView tuerKlappe;
    private View klappRahmen;
    private TextView griff;

    // ---------- Ziehblatt ----------
    private float zStartY, zStartH;
    private boolean zBewegt;
    private android.animation.ValueAnimator zAnim;

    /** Volle Höhe des Blatts (Griff + Inhalt), höchstens gut die halbe Bildschirmhöhe. */
    private int blattVoll() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(1, ((View) klappRahmen.getParent()).getWidth());
        klappe.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        return Math.min(dp(30) + klappe.getMeasuredHeight(), (int) (dm.heightPixels * 0.55));
    }

    private int blattHoehe() { return klappRahmen.getVisibility() == View.VISIBLE ? klappRahmen.getLayoutParams().height : 0; }

    private void blattSetzen(int h) {
        boolean offen = h > 0;
        klappRahmen.getLayoutParams().height = Math.max(0, h);
        klappRahmen.setVisibility(offen ? View.VISIBLE : View.GONE);
        klappRahmen.requestLayout();
        if (Anruf.cfg.optBoolean("oeffnen", false) && Anruf.zustand != Anruf.AUS)   // Tür öffnen nur einmal zeigen
            tuerOben.setVisibility(offen ? View.GONE : View.VISIBLE);
    }

    private void blattAnimieren(int ziel) {
        if (zAnim != null) zAnim.cancel();
        int von = blattHoehe();
        if (von == 0 && ziel > 0) blattSetzen(1);
        zAnim = android.animation.ValueAnimator.ofInt(von, ziel);
        zAnim.setDuration(Math.min(320, 140 + Math.abs(ziel - von) / 4));
        zAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        zAnim.addUpdateListener(a -> blattSetzen((int) a.getAnimatedValue()));
        zAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { griff.setVisibility(ziel > 0 ? View.GONE : View.VISIBLE); }
        });
        if (ziel > 0) griff.setVisibility(View.GONE);
        zAnim.start();
    }

    private void klappen(boolean auf) { griff.setText("⌃  Schalter & Ansagen"); blattAnimieren(auf ? blattVoll() : 0); }

    /** Griff-Linien und Griff am Dock: das Blatt folgt dem Finger; loslassen rastet ein (zu / so lassen / ganz auf). */
    private boolean ziehenGeste(View v, android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                if (zAnim != null) zAnim.cancel();
                zStartY = e.getRawY(); zStartH = blattHoehe(); zBewegt = false;
                v.getParent().requestDisallowInterceptTouchEvent(true);
                v.setPressed(true);
                return true;
            case android.view.MotionEvent.ACTION_MOVE: {
                float dy = e.getRawY() - zStartY;
                if (Math.abs(dy) > dp(6)) zBewegt = true;
                if (zBewegt) blattSetzen((int) Math.max(0, Math.min(blattVoll(), zStartH - dy)));
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                v.setPressed(false);
                int voll = blattVoll(), h = blattHoehe();
                Log.i(Anruf.TAG, "Ziehen: bewegt=" + zBewegt + " h=" + h + " voll=" + voll + " dy=" + (e.getRawY() - zStartY));
                if (!zBewegt) { if (v == griff) blattAnimieren(voll); return true; }   // Griff antippen öffnet; Linien antippen tut nichts
                float tempo = e.getEventTime() > e.getDownTime() ? (e.getRawY() - zStartY) / (e.getEventTime() - e.getDownTime()) : 0;
                if (tempo > 1.2f || h < voll * 0.3f) blattAnimieren(0);          // schnell runter oder fast zu: zu
                else if (tempo < -1.2f || h > voll * 0.85f) blattAnimieren(voll); // schnell hoch oder fast ganz: ganz auf
                else griff.setVisibility(View.GONE);                              // dazwischen: bleibt so groß
                return true;
            }
        }
        return false;
    }

    /** Griff nur zeigen, wenn es etwas auszuklappen gibt. */
    private void griffPruefen() {
        boolean etwas = leitungZeile.getChildCount() > 0 || schalterZeile.getChildCount() > 0 || antwortZeile.getChildCount() > 0 || extras.getChildCount() > 0;
        griff.setVisibility(etwas && Anruf.zustand != Anruf.AUS && klappRahmen.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
        if (!etwas) blattSetzen(0);
        else if (griff.getText().length() == 0) griff.setText("⌃  Schalter & Ansagen");
    }

    private TextView text(int sp, int farbe) {
        TextView t = new TextView(this);
        t.setTypeface(schrift(this));
        t.setTextSize(sp);
        t.setTextColor(farbe);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** Knopf im Dock: runde Kachel, Symbol + Beschriftung; Farbe = Zustand (aus = dunkle Kachel mit Rand). */
    private View knopf(int icon, String beschriftung, int farbe, int groesse, View.OnClickListener klick) {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setGravity(Gravity.CENTER);
        k.setPadding(0, dp(durchgehend ? 9 : 7), 0, dp(durchgehend ? 7 : 5));
        GradientDrawable flaeche = kachel(farbe, farbe == KACHEL && !durchgehend ? RAND : 0);
        flaeche.setCornerRadius(durchgehend ? 0 : dp(12));
        k.setBackground(flaeche);
        ImageView bild = new ImageView(this);
        bild.setImageResource(icon);
        k.addView(bild, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = text(12, Color.WHITE);
        t.setText(beschriftung);
        t.setSingleLine(true);
        t.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP);
        t.setPadding(dp(4), dp(2), dp(4), 0);
        k.addView(t, new LinearLayout.LayoutParams(-1, dp(22)));
        k.setOnClickListener(klick);
        k.setContentDescription(beschriftung);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (durchgehend) lp.setMargins(dp(1), 0, dp(1), 0); else lp.setMargins(dp(3), 0, dp(3), 0);
        k.setLayoutParams(lp);
        k.setTag(new Object[]{bild, flaeche, t, farbe});
        return k;
    }

    private void knopfSetzen(View k, int icon, String beschriftung, int farbe) {
        if (k == null) return;
        Object[] teile = (Object[]) k.getTag();
        teile[3] = farbe;
        leiste.invalidate();
        ((ImageView) teile[0]).setImageResource(icon);
        ((GradientDrawable) teile[1]).setColor(farbe);
        ((GradientDrawable) teile[1]).setStroke(farbe == KACHEL && !durchgehend ? dp(1) : 0, RAND);
        ((TextView) teile[2]).setText(beschriftung);
    }

    private void zustandZeigen() {
        if (isFinishing() || isDestroyed()) return;
        titel.setText(Anruf.name());
        int z = Anruf.zustand;
        if (z != letzterZustand) {
            letzterZustand = z;
            leiste.removeAllViews();
            knopfMikro = knopfLaut = knopfTuer = null;
            if (z == Anruf.KLINGELT) {
                leiste.addView(knopf(R.drawable.ic_auflegen, "Ablehnen", ROT, 72, v -> Anruf.ablehnen(this)));
                tuerOben.setVisibility(Anruf.cfg.optBoolean("oeffnen", false) ? View.VISIBLE : View.GONE);   // auch ohne Annehmen
                tuerKlappe.setVisibility(tuerOben.getVisibility());
                leiste.addView(knopf(R.drawable.ic_annehmen, "Annehmen", GRUEN_AN, 72, v -> annehmen()));
                extrasBauen();
                statusZeigen(Anruf.cfg);
                h.removeCallbacks(statusHolen);
                h.postDelayed(statusHolen, 4000);
                status.setText("klingelt …");
                stumm = true;
                videoStarten(false);
            } else if (z == Anruf.AKTIV) {
                knopfMikro = knopf(R.drawable.ic_mic, "Mikrofon", KACHEL, 60, v -> stummSchalten());
                leiste.addView(knopfMikro);
                knopfLaut = knopf(R.drawable.ic_laut, "Lautsprecher", BLAU, 60, v -> lautSchalten());
                leiste.addView(knopfLaut);
                tuerOben.setVisibility(Anruf.cfg.optBoolean("oeffnen", false) ? View.VISIBLE : View.GONE);
                tuerKlappe.setVisibility(tuerOben.getVisibility());
                leiste.addView(knopf(R.drawable.ic_auflegen, "Auflegen", ROT, 60, v -> auflegenFragen()));
                extrasBauen();
                statusZeigen(Anruf.cfg);
                h.removeCallbacks(statusHolen);
                h.postDelayed(statusHolen, 2000);
                modus = Anruf.cfg.optString("modus", "einer");   // Sprechen-Modus der Klingel (HA), später aus der Runde
                if (!mitMikro) videoStarten(true);
                uhr();
            } else {
                extras.removeAllViews();
                antwortZeile.removeAllViews();
                antwortZeile.setVisibility(View.GONE);
                griff.setVisibility(View.GONE);
                klappRahmen.setVisibility(View.GONE);
                schalterZeile.removeAllViews();
                leitungZeile.removeAllViews();
                leitungSchluessel = "";
                mikHochStoppen();
                wunschGesetzt = false;
                rundeZeile.setVisibility(View.GONE);
                tuerOben.setVisibility(View.GONE);
                tuerKlappe.setVisibility(View.GONE);
                wachHalten(false);
                h.removeCallbacks(statusHolen);
                if (!Anruf.endeGrund.isEmpty()) {   // absichtlich beendet: Grund groß zeigen, länger stehen lassen
                    status.setText(Anruf.endeGrund);
                    status.setTextColor(GELB);
                    Anruf.endeGrund = "";
                    h.postDelayed(this::finishAndRemoveTask, 4000);
                } else {
                    status.setText("beendet");
                    h.postDelayed(this::finishAndRemoveTask, 900);
                }
            }
        }
        if (z == Anruf.AKTIV) {
            knopfSetzen(knopfMikro, stumm ? R.drawable.ic_mic_aus : R.drawable.ic_mic, stumm ? "Stumm" : "Mikrofon",
                    stumm ? ROT : KACHEL);   // stumm: rot, damit das weiße Symbol sichtbar bleibt
            knopfSetzen(knopfLaut, laut ? R.drawable.ic_laut : R.drawable.ic_ohr, laut ? "Lautsprecher" : "Hörer", BLAU);
        }
    }

    private void uhr() {
        if (Anruf.zustand != Anruf.AKTIV || isFinishing()) return;
        long s = (System.currentTimeMillis() - Anruf.beginn) / 1000;
        if (System.currentTimeMillis() > hinweisBis && !status.getText().toString().startsWith("!"))
            status.setText(String.format("%d:%02d", s / 60, s % 60));
        h.postDelayed(this::uhr, 1000);
    }

    private void statusSetzen(String t) { h.post(() -> { if (status != null) status.setText(t); }); }

    // ---------- Aktionen ----------
    private void annehmen() {
        if (Anruf.zustand != Anruf.KLINGELT) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        Anruf.annehmen(this);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) { /* Gespräch geht auch gesperrt, Entsperren nur für Tür öffnen nötig */ }
    }

    private void stummSchalten() {
        stumm = !stumm;
        js("stumm(" + stumm + ")");
        zustandZeigen();
        // Gesprächsrunde: immer nur einer spricht - HA macht mich zum Sprecher, die anderen schalten sich stumm
        if (!stumm) sprechenSeit = System.currentTimeMillis();
        if ("gemischt".equals(modus)) { if (stumm) mikHochStoppen(); else mikHochStarten(); }
        JSONObject z = new JSONObject();
        try { z.put("an", !stumm); } catch (Exception ignored) {}
        Anruf.abfragen("sprechen", -1, z, r -> {
            if (r == null) return;
            if (!r.optString("text").isEmpty()) hinweis(r.optString("text"));
            if (r.optBoolean("ok")) statusZeigen(r);
            else if (!stumm) { stumm = true; js("stumm(true)"); mikHochStoppen(); zustandZeigen(); }   // z. B. von der Leitung gesperrt
        });
    }

    /** Auflegen: leitet man und sind andere dabei, entscheidet man - für alle beenden oder Leitung abgeben. */
    private void auflegenFragen() {
        JSONObject r = Anruf.runde;
        org.json.JSONArray t = r.optJSONArray("teilnehmer");
        if (!r.optBoolean("ich_leite") || t == null || t.length() < 2) { Anruf.auflegen(this); return; }
        java.util.List<String> namen = new java.util.ArrayList<>(), ids = new java.util.ArrayList<>();
        namen.add("Für alle beenden");
        ids.add("");
        for (int i = 0; i < t.length(); i++) {
            JSONObject x = t.optJSONObject(i);
            if (x == null || x.optBoolean("ich")) continue;
            namen.add("Nur ich gehe – " + x.optString("name") + " übernimmt");
            ids.add(x.optString("g"));
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Gespräch verlassen")
                .setItems(namen.toArray(new String[0]), (d, w) -> Anruf.auflegen(this, w == 0, ids.get(w)))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    /** Gesprächsrunde aus HA anzeigen: Teilnehmer, Sprecher, Leitung; Leiter bekommt Mitschauen + Dazuholen. */
    private void rundeZeigen(JSONObject r) {
        if (r == null || Anruf.zustand != Anruf.AKTIV) return;
        Anruf.runde = r;
        org.json.JSONArray t = r.optJSONArray("teilnehmer");
        int n = t == null ? 0 : t.length();
        if (r.optBoolean("ende") && System.currentTimeMillis() - Anruf.beginn > 8000) {   // HA: ich bin nicht (mehr) dabei
            JSONObject e = new JSONObject();
            try { e.put("id", Anruf.id()).put("alle", true).put("grund", r.optString("grund")); } catch (Exception ignored) {}
            Anruf.vonAussenBeenden(this, e);
            return;
        }
        // Eigener Standard aus den App-Einstellungen: leite ich, stelle ich einmal auf meinen Wunsch-Modus um
        String wunsch = Anruf.prefs(this).getString("sprechen", "ha");
        if (!wunschGesetzt && r.optBoolean("ich_leite") && !"ha".equals(wunsch)) {
            wunschGesetzt = true;
            if (!wunsch.equals(r.optString("modus", "einer"))) modusSetzen(wunsch);
        }
        String neuModus = r.optString("modus", "einer");
        if (!neuModus.equals(modus)) {   // Leitung hat umgestellt: WebRTC neu aufbauen (Mikro direkt oder über den Mischer)
            modus = neuModus;
            mikHochStoppen();
            if (!stumm) { stumm = true; zustandZeigen(); }
            hinweis("gemischt".equals(modus) ? "Sprechen: alle gleichzeitig (gemischt)" : "Sprechen: immer nur einer");
            videoStarten(true);
        }
        if (r.optBoolean("gesperrt") && !stumm) {
            stumm = true; js("stumm(true)"); mikHochStoppen(); zustandZeigen();
            hinweis("Die Leitung hat dein Mikrofon gesperrt");
        }
        String sprecher = r.optString("sprecher");
        if ("einer".equals(modus) && !stumm && !r.optBoolean("ich_spreche") && !sprecher.isEmpty() && System.currentTimeMillis() - sprechenSeit > 3000) {
            stumm = true;   // jemand anderes hat übernommen
            js("stumm(true)");
            zustandZeigen();
            hinweis(sprecher + " spricht – du bist stumm");
        }
        StringBuilder sb = new StringBuilder();
        if (n > 1) {
            sb.append("Mit dabei: ");
            boolean erst = true;
            for (int i = 0; i < n; i++) {
                JSONObject x = t.optJSONObject(i);
                if (x == null || x.optBoolean("ich")) continue;
                if (!erst) sb.append(", ");
                sb.append(x.optString("name"));
                erst = false;
            }
        }
        if (!r.optBoolean("ich_leite") && !r.optString("leiter").isEmpty()) sb.append(sb.length() > 0 ? "  ·  " : "").append("Leitung: ").append(r.optString("leiter"));
        org.json.JSONArray sl = r.optJSONArray("sprecher_liste");
        if ("gemischt".equals(modus) && sl != null && sl.length() > 0 && n > 1) {
            sb.append("  ·  🎙 ");
            for (int i = 0; i < sl.length(); i++) sb.append(i > 0 ? ", " : "").append(sl.optString(i));
        } else if (!sprecher.isEmpty() && n > 1) sb.append("  ·  🎙 ").append(r.optBoolean("ich_spreche") ? "du" : sprecher);
        if (r.optBoolean("gesperrt")) sb.append(sb.length() > 0 ? "  ·  " : "").append("Mikro gesperrt");
        rundeZeile.setText(sb);
        rundeZeile.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);

        String schl = r.optBoolean("ich_leite") + "|" + r.optBoolean("frei") + "|" + r.optJSONArray("einladbar") + "|" + modus + "|" + t;
        if (schl.equals(leitungSchluessel)) return;
        leitungSchluessel = schl;
        leitungZeile.removeAllViews();
        if (!r.optBoolean("ich_leite")) { griffPruefen(); return; }
        leitungZeile.addView(ueberschrift("Gespräch – du leitest"));
        LinearLayout reihe = new LinearLayout(this);
        boolean frei = r.optBoolean("frei");
        TextView m = text(15, frei ? Color.WHITE : SCHRIFT);
        m.setGravity(Gravity.CENTER_VERTICAL);
        m.setMaxLines(2);
        m.setText((frei ? "●  " : "○  ") + (frei ? "Mitschauen erlaubt" : "Mitschauen gesperrt"));
        m.setPadding(dp(12), dp(12), dp(8), dp(12));
        m.setMinHeight(dp(52));
        GradientDrawable mg = kachel(frei ? BLAU : KACHEL, frei ? BLAU_HELL : RAND);
        m.setBackground(mg);
        m.setOnClickListener(v -> {
            mg.setColor(GELB);
            JSONObject z = new JSONObject();
            try { z.put("an", !frei); } catch (Exception ignored) {}
            Anruf.abfragen("freigabe", -1, z, rr -> {
                if (rr == null) { hinweis("Keine Verbindung"); return; }
                hinweis(rr.optString("text"));
                statusZeigen(rr);
            });
        });
        reihe.addView(m, new LinearLayout.LayoutParams(0, -2, 1));
        org.json.JSONArray ein = r.optJSONArray("einladbar");
        TextView d = text(15, SCHRIFT);
        d.setGravity(Gravity.CENTER_VERTICAL);
        d.setMaxLines(2);
        d.setText("＋  Dazuholen …");
        d.setPadding(dp(12), dp(12), dp(8), dp(12));
        d.setMinHeight(dp(52));
        d.setBackground(kachel(KACHEL, RAND));
        d.setAlpha(ein != null && ein.length() > 0 ? 1f : 0.5f);
        d.setOnClickListener(v -> dazuholen(ein));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1);
        dl.leftMargin = dp(8);
        reihe.addView(d, dl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = dp(8);
        leitungZeile.addView(reihe, rl);
        // zweite Reihe: Sprechen-Modus (mit Erklärung) und Mikrofone sperren
        LinearLayout reihe2 = new LinearLayout(this);
        boolean gem = "gemischt".equals(modus);
        TextView sm = text(15, SCHRIFT);
        sm.setGravity(Gravity.CENTER_VERTICAL);
        sm.setMaxLines(2);
        sm.setText(gem ? "🎙  Alle gleichzeitig (gemischt)" : "🎙  Immer nur einer (schnell)");
        sm.setPadding(dp(12), dp(12), dp(8), dp(12));
        sm.setMinHeight(dp(52));
        sm.setBackground(kachel(KACHEL, RAND));
        sm.setOnClickListener(v -> modusWaehlen());
        reihe2.addView(sm, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ms = text(15, SCHRIFT);
        ms.setGravity(Gravity.CENTER_VERTICAL);
        ms.setMaxLines(2);
        int gesp = 0;
        for (int i = 0; t != null && i < t.length(); i++) if (t.optJSONObject(i).optBoolean("gesperrt")) gesp++;
        ms.setText(gesp > 0 ? "🔇  Mikros gesperrt: " + gesp : "🔇  Mikros sperren …");
        ms.setPadding(dp(12), dp(12), dp(8), dp(12));
        ms.setMinHeight(dp(52));
        ms.setBackground(kachel(gesp > 0 ? 0xFF7F1D1D : KACHEL, gesp > 0 ? ROT : RAND));
        ms.setAlpha(n > 1 ? 1f : 0.5f);
        ms.setOnClickListener(v -> mikrosSperren(t));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -2, 1);
        ml.leftMargin = dp(8);
        reihe2.addView(ms, ml);
        leitungZeile.addView(reihe2, new LinearLayout.LayoutParams(rl));
        griffPruefen();
    }

    /** Leitung wählt, wie mehrere sprechen - immer mit Erklärung (Nutzer 2026-11-21). */
    private void modusWaehlen() {
        String[] namen = {"Immer nur einer (schnell)", "Alle gleichzeitig (gemischt)"};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Wie sollen mehrere sprechen?")
                .setMessage("Schnell: Das Mikrofon geht direkt an die Tür, kaum Verzögerung. Es spricht immer nur einer – "
                        + "wer sein Mikrofon anmacht, schaltet den vorigen stumm.\n\n"
                        + "Gemischt: Home Assistant mischt alle Mikrofone, mehrere können gleichzeitig reden. Die Stimme kommt "
                        + "dafür etwa 0,3–0,8 s später draußen an. Beim Umstellen baut sich das Bild kurz neu auf.")
                .setPositiveButton(namen[1], (d, w) -> modusSetzen("gemischt"))
                .setNeutralButton(namen[0], (d, w) -> modusSetzen("einer"))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void modusSetzen(String m) {
        JSONObject z = new JSONObject();
        try { z.put("modus", m); } catch (Exception ignored) {}
        Anruf.abfragen("modus", -1, z, rr -> {
            if (rr == null) { hinweis("Keine Verbindung"); return; }
            hinweis(rr.optString("text"));
            statusZeigen(rr);
        });
    }

    /** Leitung sperrt/entsperrt Mikrofone der Gäste (gesperrt = nur zuschauen und zuhören). */
    private void mikrosSperren(org.json.JSONArray t) {
        java.util.List<String> ids = new java.util.ArrayList<>(), namen = new java.util.ArrayList<>();
        java.util.List<Boolean> an = new java.util.ArrayList<>();
        for (int i = 0; t != null && i < t.length(); i++) {
            JSONObject x = t.optJSONObject(i);
            if (x == null || x.optBoolean("ich")) continue;
            ids.add(x.optString("g")); namen.add(x.optString("name")); an.add(x.optBoolean("gesperrt"));
        }
        if (ids.isEmpty()) { hinweis("Keine Gäste im Gespräch"); return; }
        boolean[] wahl = new boolean[ids.size()];
        for (int i = 0; i < wahl.length; i++) wahl[i] = an.get(i);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Mikrofon sperren (nur zuhören)")
                .setMultiChoiceItems(namen.toArray(new String[0]), wahl, (d, w, x) -> wahl[w] = x)
                .setPositiveButton("Übernehmen", (d, w) -> {
                    for (int i = 0; i < wahl.length; i++) {
                        if (wahl[i] == an.get(i)) continue;
                        JSONObject z = new JSONObject();
                        try { z.put("ziel", ids.get(i)).put("an", wahl[i]); } catch (Exception ignored) {}
                        Anruf.abfragen("mik_sperren", -1, z, rr -> { if (rr != null) { hinweis(rr.optString("text")); statusZeigen(rr); } });
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    /** Geräte/Gruppen auswählen, die mit ins Gespräch sollen: sie klingeln mit "<ich> holt dich dazu". */
    private void dazuholen(org.json.JSONArray ein) {
        if (ein == null || ein.length() == 0) { hinweis("Alle sind schon dabei"); return; }
        String[] namen = new String[ein.length()];
        boolean[] wahl = new boolean[ein.length()];
        for (int i = 0; i < namen.length; i++) namen[i] = ein.optJSONObject(i).optString("name");
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Wen dazuholen?")
                .setMultiChoiceItems(namen, wahl, (dd, w, an) -> wahl[w] = an)
                .setPositiveButton("Anklingeln", (dd, w) -> {
                    org.json.JSONArray ziele = new org.json.JSONArray();
                    for (int i = 0; i < wahl.length; i++) if (wahl[i]) ziele.put(ein.optJSONObject(i).optString("id"));
                    if (ziele.length() == 0) return;
                    JSONObject z = new JSONObject();
                    try { z.put("ziele", ziele); } catch (Exception ignored) {}
                    Anruf.abfragen("einladen", -1, z, rr -> hinweis(rr == null ? "Keine Verbindung" : rr.optString("text")));
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void lautSchalten() {
        laut = !laut;
        if (Anruf.verbindung != null) Anruf.verbindung.lautsprecher(laut);
        zustandZeigen();
    }

    private void tuerSetzen(String t, int farbe) {
        for (TextView v : new TextView[]{tuerOben, tuerKlappe}) {
            if (v == null) continue;
            v.setText(t);
            ((GradientDrawable) v.getBackground()).setColor(farbe);
        }
    }

    /** Tür öffnen in zwei Schritten (Sicherheitsabfrage): erster Tipp fragt nach, zweiter innerhalb 4 s öffnet. */
    private void tuerKnopf() {
        if (!oeffnenScharf) {
            oeffnenScharf = true;
            tuerSetzen("Wirklich öffnen? Nochmal tippen", 0xFFF59E0B);
            h.postDelayed(entschaerfen, 4000);
            return;
        }
        oeffnenScharf = false;
        h.removeCallbacks(entschaerfen);
        tuerSetzen("Öffnet …", 0xFF16A34A);
        Anruf.melden("oeffnen", -1, t -> {
            boolean ok = !t.startsWith("!");
            tuerSetzen(ok ? "Geöffnet ✓" : "Nicht geöffnet", ok ? 0xFF16A34A : 0xFFB91C1C);
            hinweis(ok ? "Tür geöffnet" : t.substring(1));
            h.postDelayed(entschaerfen, 4000);
        });
    }

    /** Alle 5 s Schalter/Anzeigen frisch von HA, solange der Anruf läuft (nur wenn welche eingestellt sind). */
    private void statusHolen() {
        if (Anruf.zustand == Anruf.AUS || isFinishing()) return;
        if (Anruf.cfg.optString("webhook").isEmpty() && Anruf.cfg.optString("webhook_extern").isEmpty()) return;
        Anruf.abfragen("status", -1, r -> { if (r != null && r.optBoolean("ok")) statusZeigen(r); });
        // im Gespräch alle 2 s (Gesprächsrunde: Sprecher, Teilnehmer, Lebenszeichen für HA), beim Klingeln alle 5 s
        h.postDelayed(statusHolen, Anruf.zustand == Anruf.AKTIV ? 2000 : 5000);
    }

    private void statusZeigen(JSONObject r) {
        if (isFinishing() || isDestroyed()) return;
        if (r.has("runde")) rundeZeigen(r.optJSONObject("runde"));
        org.json.JSONArray anz = r.optJSONArray("anzeigen");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; anz != null && i < anz.length(); i++) {
            JSONObject x = anz.optJSONObject(i);
            if (x == null) continue;
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(x.optString("name")).append(": ").append(x.optString("text"));
        }
        anzeigeZeile.setText(sb);
        anzeigeZeile.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);
        org.json.JSONArray sch = r.optJSONArray("schalter");
        schalterZeile.removeAllViews();
        if (sch != null && sch.length() > 0) schalterZeile.addView(ueberschrift("Schalter"));
        LinearLayout reihe = null;
        for (int i = 0; sch != null && i < sch.length() && i < 4; i++) {
            JSONObject x = sch.optJSONObject(i);
            if (x == null) continue;
            final int nr = i;
            boolean an = x.optBoolean("an"), da = x.optBoolean("da", true);
            TextView t = text(15, an ? Color.WHITE : SCHRIFT);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setMaxLines(2);   // ganzer Name, notfalls zweizeilig
            t.setText((an ? "●  " : "○  ") + x.optString("name"));
            t.setPadding(dp(12), dp(12), dp(8), dp(12));
            t.setMinHeight(dp(52));
            GradientDrawable g = kachel(!da ? 0xFF14181E : an ? BLAU : KACHEL, an ? BLAU_HELL : RAND);
            t.setBackground(g);
            t.setAlpha(da ? 1f : 0.5f);
            t.setOnClickListener(v -> {
                g.setColor(GELB);
                Anruf.abfragen("schalten", nr, rr -> {
                    if (rr == null) { hinweis("Keine Verbindung"); return; }
                    hinweis(rr.optString("text"));
                    statusZeigen(rr);
                });
            });
            if (reihe == null || reihe.getChildCount() == 2) {
                reihe = new LinearLayout(this);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.bottomMargin = dp(8);
                schalterZeile.addView(reihe, rl);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (reihe.getChildCount() == 1) lp.leftMargin = dp(8);
            reihe.addView(t, lp);
        }
        if (reihe != null && reihe.getChildCount() == 1) {   // ungerade: Platzhalter, damit die Kachel halb breit bleibt
            View leer = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
            lp.leftMargin = dp(8);
            reihe.addView(leer, lp);
        }
        griffPruefen();
    }

    /** Kurzer Hinweis oben statt der Gesprächsdauer (4 s). */
    private void hinweis(String t) {
        hinweisBis = System.currentTimeMillis() + 4000;
        status.setText(t);
    }

    /** Eigene Knöpfe aus dem Konfigurator (Licht, Schnellantwort ...): als flache Pillen über der Hauptleiste. */
    private LinearLayout antwortZeile;

    private void antwortBauen() {
        antwortZeile.removeAllViews();
        org.json.JSONArray l = Anruf.cfg.optJSONArray("antworten");
        if (l == null || l.length() == 0) { antwortZeile.setVisibility(View.GONE); return; }
        antwortZeile.setVisibility(View.VISIBLE);
        antwortZeile.addView(ueberschrift("Schnellantwort"));
        String[] texte = new String[l.length()];
        for (int i = 0; i < texte.length; i++) texte[i] = l.optString(i);
        // Eigene Auswahl statt Spinner (2026-11-21): Liste als Dialog, auf dem Tablet breit (80 %), auf dem Handy fast volle Breite
        final int[] gewaehlt = {0};
        TextView auswahl = text(16, Color.WHITE);
        auswahl.setSingleLine(true);
        auswahl.setEllipsize(android.text.TextUtils.TruncateAt.END);
        auswahl.setGravity(Gravity.CENTER_VERTICAL);
        auswahl.setText(texte[0] + "  ▾");
        auswahl.setTextColor(SCHRIFT);
        auswahl.setBackground(kachel(KACHEL, RAND));
        auswahl.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, texte) {
            // Liste groß und luftig (Nutzer: "sehr eng, Angst den Falschen zu treffen")
            @Override public View getView(int p, View v, android.view.ViewGroup g) {
                TextView t = (TextView) super.getView(p, v, g);
                t.setTypeface(schrift(Gespraech.this));
                t.setTextSize(18);
                t.setSingleLine(false);
                t.setMaxLines(3);
                t.setMinHeight(dp(76));
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(dp(28), dp(16), dp(28), dp(16));
                t.setTextColor(p == gewaehlt[0] ? 0xFFC4B5FD : Color.WHITE);
                t.setBackgroundColor(p % 2 == 0 ? 0x00000000 : 0x14FFFFFF);
                return t;
            }
        };
        auswahl.setOnClickListener(v -> {
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Schnellantwort")
                    .setAdapter(ad, (dlg, w) -> { gewaehlt[0] = w; auswahl.setText(texte[w] + "  ▾"); })
                    .setNegativeButton("Abbrechen", null).create();
            d.show();
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            boolean breit = dm.widthPixels > dm.heightPixels || dm.widthPixels / dm.density >= 600;
            if (d.getWindow() != null) d.getWindow().setLayout((int) (dm.widthPixels * (breit ? 0.8 : 0.95)), -2);
        });
        antwortZeile.addView(auswahl, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout zeile2 = new LinearLayout(this);
        zeile2.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        zeile2.setPadding(0, dp(8), 0, 0);
        antwortZeile.addView(zeile2, new LinearLayout.LayoutParams(-1, -2));
        final android.widget.CheckBox auf = new android.widget.CheckBox(this);
        if (Anruf.cfg.optBoolean("oeffnen", false)) {
            auf.setText("danach Tür öffnen");
            auf.setTypeface(schrift(this));
            auf.setTextColor(SCHRIFT);
            auf.setTextSize(14);
            auf.setButtonTintList(android.content.res.ColorStateList.valueOf(BLAU_HELL));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);   // Häkchen nimmt den Rest, Knopf bleibt einzeilig
            cl.setMargins(dp(10), 0, dp(4), 0);
            zeile2.addView(auf, cl);
        }
        TextView los = text(15, Color.WHITE);
        los.setText("▶ Abspielen");
        los.setPadding(dp(20), dp(12), dp(20), dp(12));
        los.setTypeface(schrift(this));
        los.setSingleLine(true);
        GradientDrawable lg = kachel(BLAU, BLAU_HELL);
        los.setBackground(lg);
        los.setOnClickListener(v -> {
            int nr = gewaehlt[0];
            boolean oe = auf.isChecked() && Anruf.cfg.optBoolean("oeffnen", false);
            lg.setColor(0xFFF59E0B);
            Anruf.melden("antwort", nr, oe, t -> {
                boolean ok = !t.startsWith("!");
                lg.setColor(ok ? GRUEN : ROT);
                hinweis(ok ? (t.isEmpty() ? "Ansage läuft" : t) : t.substring(1));
                h.postDelayed(() -> lg.setColor(BLAU), 3000);
            });
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.setMargins(dp(8), 0, 0, 0);
        zeile2.addView(los, ll);
    }

    private void extrasBauen() {
        antwortBauen();
        extras.removeAllViews();
        org.json.JSONArray l = Anruf.cfg.optJSONArray("aktionen");
        if (l == null || l.length() == 0) { griffPruefen(); return; }
        extras.addView(ueberschrift("Knöpfe"));
        LinearLayout reihe = new LinearLayout(this);
        extras.addView(reihe, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < l.length() && i < 3; i++) {
            final int nr = i;
            final String n = l.optString(i);
            TextView b = text(15, Color.WHITE);
            b.setText(n);
            b.setTextColor(SCHRIFT);
            b.setPadding(dp(10), dp(14), dp(10), dp(14));
            GradientDrawable g = kachel(KACHEL, RAND);
            b.setBackground(g);
            b.setOnClickListener(v -> {
                g.setColor(0xFFF59E0B);
                Anruf.melden("aktion", nr, t -> {
                    boolean ok = !t.startsWith("!");
                    g.setColor(ok ? GRUEN : ROT);
                    hinweis(ok ? (t.isEmpty() ? n + " ✓" : t) : n + ": " + t.substring(1));
                    h.postDelayed(() -> g.setColor(KACHEL), 2500);
                });
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = dp(8);
            reihe.addView(b, lp);
        }
        griffPruefen();
    }

    static void audioGeaendert(CallAudioState s) {
        Gespraech g = aktuell.get();
        if (g == null) return;
        g.h.post(() -> { g.laut = s.getRoute() == CallAudioState.ROUTE_SPEAKER || s.getRoute() == CallAudioState.ROUTE_BLUETOOTH; g.zustandZeigen(); });
    }

    // ---------- WebRTC ----------
    private String modus = "einer";   // Sprechen: "einer" (schnell, WebRTC direkt) oder "gemischt" (HA mischt)
    private final java.util.concurrent.LinkedBlockingQueue<byte[]> mikQ = new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile Thread mikFaden;

    private String startJs(boolean mikro) { return "start(" + mikro + "," + stumm + "," + "gemischt".equals(modus) + ")"; }

    /** Modus gemischt: Mikrofon als endloser POST an Home Assistant (/api/esphomeremote/klingel_mik), nur solange nicht stumm. */
    private void mikHochStarten() {
        if (mikFaden != null) return;
        mikQ.clear();
        Thread t = new Thread(() -> {
            String q;
            try {
                q = Anruf.pfad(Anruf.cfg, "mik") + "?id=" + java.net.URLEncoder.encode(Anruf.id(), "UTF-8")
                        + "&geraet=" + java.net.URLEncoder.encode(Anruf.cfg.optString("geraet"), "UTF-8")
                        + "&schluessel=" + java.net.URLEncoder.encode(Anruf.cfg.optString("schluessel"), "UTF-8");
            } catch (Exception e) { return; }
            for (String basis : new String[]{Anruf.cfg.optString("ha"), Anruf.cfg.optString("extern")}) {
                if (basis.isEmpty() || mikFaden != Thread.currentThread()) continue;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(basis + q).openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setChunkedStreamingMode(1024);
                    c.setConnectTimeout(3000);
                    c.setRequestProperty("Content-Type", "application/octet-stream");
                    OutputStream o = c.getOutputStream();
                    Log.i(Anruf.TAG, "Mischer-Mikro verbunden (" + basis + ")");
                    while (mikFaden == Thread.currentThread()) {
                        byte[] b = mikQ.poll(400, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (b != null) { o.write(b); o.flush(); }
                    }
                    o.close();
                    c.getResponseCode();
                    return;
                } catch (Exception e) {
                    Log.w(Anruf.TAG, "Mischer-Mikro " + basis + ": " + e);
                } finally { if (c != null) c.disconnect(); }
            }
            if (mikFaden == Thread.currentThread()) statusSetzen("! Mikrofon erreicht Home Assistant nicht");
        });
        mikFaden = t;
        t.start();
    }

    private void mikHochStoppen() { mikFaden = null; }

    private void videoStarten(boolean mikro) {
        mitMikro = mikro;
        String go2rtc = Anruf.cfg.optString("go2rtc", "");
        if (go2rtc.isEmpty() || Anruf.cfg.optString("src", "").isEmpty()) { status.setText("Keine Kamera eingestellt"); return; }
        if (web.getUrl() == null || !web.getUrl().startsWith("https://klingel.local/")) {
            seiteBereit = false;
            wartet = startJs(mikro);   // startet in onPageFinished
            web.loadDataWithBaseURL("https://klingel.local/", SEITE, "text/html", "UTF-8", null);
        } else if (!seiteBereit) wartet = startJs(mikro);
        else js(startJs(mikro));
    }

    private void js(String code) { h.post(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    final class Bruecke {
        /** JS hat ein Angebot (SDP): erst direkt an go2rtc im Heimnetz, sonst über Home Assistant von unterwegs. */
        @JavascriptInterface
        public void angebot(String sdp) {
            new Thread(() -> {
                long t0 = SystemClock.elapsedRealtime();
                String sdpA = null, weg = "";
                try {
                    String url = Anruf.cfg.optString("go2rtc").replaceAll("/+$", "") + "/api/webrtc?src="
                            + java.net.URLEncoder.encode(Anruf.cfg.optString("src"), "UTF-8");
                    Netz.Antwort a = Netz.post(url, new JSONObject().put("type", "offer").put("sdp", sdp).toString(), 1500, 8000);
                    if (a.code < 400) { sdpA = new JSONObject(a.text).getString("sdp"); weg = "Heimnetz"; }
                } catch (Exception e) { Log.i(Anruf.TAG, "go2rtc direkt nicht erreichbar: " + e); }
                String ext = Anruf.cfg.optString("extern", "");
                if (sdpA == null && !ext.isEmpty()) {
                    try {
                        JSONObject o = new JSONObject().put("id", Anruf.id()).put("geraet", Anruf.cfg.optString("geraet"))
                                .put("schluessel", Anruf.cfg.optString("schluessel")).put("sdp", sdp);
                        Netz.Antwort a = Netz.post(ext + Anruf.pfad(Anruf.cfg, "signal"), o.toString(), 5000, 12000);
                        if (a.code >= 400) throw new Exception("HTTP " + a.code + " " + a.text);
                        sdpA = new JSONObject(a.text).getString("sdp"); weg = "unterwegs";
                    } catch (Exception e) { Log.w(Anruf.TAG, "Signal über HA: " + e); }
                }
                if (sdpA == null || sdpA.isEmpty()) { statusSetzen("! Keine Verbindung zur Kamera"); return; }
                Log.i(Anruf.TAG, "go2rtc-Antwort (" + weg + ") nach " + (SystemClock.elapsedRealtime() - t0) + " ms");
                js("antwort(" + JSONObject.quote(sdpA) + ")");
            }).start();
        }

        /** Mikrofon-Stück (16 bit, 8 kHz, Base64) im Modus "gemischt" -> Upload an Home Assistant. */
        @JavascriptInterface
        public void pcm(String b64) {
            if (mikQ.size() > 25) mikQ.poll();   // hängt die Leitung: Altes verwerfen statt Verzögerung aufzubauen
            mikQ.offer(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP));
        }

        @JavascriptInterface
        public void meldung(String t) {
            Log.i(Anruf.TAG, "web: " + t);
            if (t.startsWith("!")) statusSetzen(t);
        }
    }

    private static final String SEITE = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
            + "video{width:100%;height:100%;object-fit:contain;background:#000}</style></head><body>"
            + "<video id=v autoplay playsinline muted poster='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'></video><script>"
            + "let pc=null,mik=null,ctx=null,proc=null,leise=true;const v=document.getElementById('v');"
            + "function stop(){if(proc){proc.disconnect();proc=null}if(ctx){ctx.close();ctx=null}"
            + " if(pc){pc.close();pc=null}if(mik){mik.getTracks().forEach(t=>t.stop());mik=null}}"
            // Modus gemischt: Mikro nicht per WebRTC, sondern als 8-kHz-PCM über die App an Home Assistant (Mischer)
            + "function mischStart(){try{ctx=new AudioContext();const q=ctx.createMediaStreamSource(mik);const r=ctx.sampleRate/8000;"
            + " proc=ctx.createScriptProcessor(2048,1,1);proc.onaudioprocess=e=>{if(leise)return;const f=e.inputBuffer.getChannelData(0);"
            + "  const n=Math.floor(f.length/r),b=new Int16Array(n);for(let i=0;i<n;i++){let s=0,a=Math.floor(i*r),z=Math.floor((i+1)*r);"
            + "   for(let k=a;k<z;k++)s+=f[k];let w=s/(z-a)*32767;b[i]=w>32767?32767:w<-32768?-32768:w}"
            + "  const u=new Uint8Array(b.buffer);let t='';for(let i=0;i<u.length;i++)t+=String.fromCharCode(u[i]);A.pcm(btoa(t))};"
            + " q.connect(proc);proc.connect(ctx.destination);A.meldung('Mischer-Mikro '+ctx.sampleRate+' Hz')}catch(e){A.meldung('! Mischer: '+e)}}"
            + "async function start(mitMik,st,gem){stop();leise=st;"
            + " try{pc=new RTCPeerConnection({iceServers:[]});"
            + "  const ms=new MediaStream();"
            + "  pc.ontrack=e=>{ms.addTrack(e.track);v.srcObject=ms;v.muted=!mitMik;v.play().catch(()=>{})};"
            + "  pc.onconnectionstatechange=()=>{A.meldung('Verbindung '+pc.connectionState);"
            + "   if(pc.connectionState==='failed')A.meldung('! Verbindung abgebrochen')};"
            + "  pc.addTransceiver('video',{direction:'recvonly'});"
            + "  if(mitMik){try{mik=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});"
            + "   mik.getAudioTracks().forEach(t=>t.enabled=!st);"
            + "   if(gem){pc.addTransceiver('audio',{direction:'recvonly'});mischStart()}"
            + "   else pc.addTransceiver(mik.getAudioTracks()[0],{direction:'sendrecv'})}"
            + "   catch(e){A.meldung('! Mikrofon: '+e.name);pc.addTransceiver('audio',{direction:'recvonly'})}}"
            + "  else pc.addTransceiver('audio',{direction:'recvonly'});"
            + "  await pc.setLocalDescription(await pc.createOffer());"
            + "  await new Promise(r=>{if(pc.iceGatheringState==='complete')return r();"
            + "   pc.onicegatheringstatechange=()=>{if(pc.iceGatheringState==='complete')r()};setTimeout(r,1500)});"
            + "  A.meldung('sicher='+window.isSecureContext+' mikro='+!!mik);"
            + "  A.angebot(pc.localDescription.sdp)"
            + " }catch(e){A.meldung('! '+e)}}"
            + "async function antwort(sdp){try{await pc.setRemoteDescription({type:'answer',sdp})}catch(e){A.meldung('! '+e)}}"
            + "function stumm(s){leise=s;if(mik)mik.getAudioTracks().forEach(t=>t.enabled=!s)}"
            + "</script></body></html>";
}
