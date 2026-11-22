package de.esphomeremote.klingel;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Start der App (neu gegliedert 2026-11-22): vorne nur Hausruf (alle Ziele als Kacheln, ein Tipp) und ein kurzer Status;
 * Einstellungen, Rechte, Probeanruf und "Wer war da" liegen in eigenen Unterseiten (Zurück-Taste führt zur Startseite).
 */
public class Start extends Activity {
    private static final String START = "start", EINST = "einst", VERLAUF = "verlauf";
    private LinearLayout liste;
    private ScrollView sv;
    private String seite = START;
    private Object zurueck;   // OnBackInvokedCallback (API 33+), nur auf Unterseiten registriert
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        App.ctx = getApplicationContext();
        Anruf.kanaele(this);
        Anruf.kontoAnmelden(this);
        d = getResources().getDisplayMetrics().density;
        sv = new ScrollView(this);
        sv.setBackgroundColor(Gespraech.BG);
        liste = new LinearLayout(this);
        liste.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * d);
        liste.setPadding(p, p, p, p * 2);
        sv.addView(liste);
        sv.setOnApplyWindowInsetsListener((v, ins) -> {   // randlos (targetSdk 36): nicht unter die Systemleisten
            android.graphics.Insets i = ins.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return ins;
        });
        setContentView(sv);
        if (b != null) seite = b.getString("seite", START);
    }

    @Override
    protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putString("seite", seite); }

    @Override
    protected void onResume() {
        super.onResume();
        zeigen();
        if (START.equals(seite)) zieleHolen();
    }

    // ------------------------------------------------------------ Navigation
    private void gehe(String s) {
        seite = s;
        zeigen();
        sv.scrollTo(0, 0);
        if (START.equals(s)) zieleHolen();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {   // bis Android 12
        if (!START.equals(seite)) gehe(START); else super.onBackPressed();
    }

    private void zurueckTaste() {   // ab Android 13: Zurück-Geste/-Taste auf Unterseiten -> Startseite
        if (Build.VERSION.SDK_INT < 33) return;
        android.window.OnBackInvokedDispatcher disp = getOnBackInvokedDispatcher();
        if (zurueck != null) { disp.unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) zurueck); zurueck = null; }
        if (!START.equals(seite)) {
            android.window.OnBackInvokedCallback cb = () -> gehe(START);
            disp.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            zurueck = cb;
        }
    }

    private void zeigen() {
        liste.removeAllViews();
        if (EINST.equals(seite)) einstellungen();
        else if (VERLAUF.equals(seite)) verlauf();
        else startseite();
        zurueckTaste();
    }

    // ------------------------------------------------------------ Startseite
    private void startseite() {
        // Kopf: Name links, Zahnrad rechts
        LinearLayout kopf = new LinearLayout(this);
        kopf.setGravity(Gravity.CENTER_VERTICAL);
        TextView titel = text("Türklingel", 28, Gespraech.SCHRIFT);
        kopf.addView(titel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView zahnrad = text("⚙", 30, Gespraech.SCHRIFT);
        zahnrad.setPadding((int) (12 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        zahnrad.setContentDescription("Einstellungen");
        zahnrad.setOnClickListener(v -> gehe(EINST));
        kopf.addView(zahnrad);
        liste.addView(kopf);
        linie();

        JSONObject l = Anruf.letzter(this);
        if (l.length() == 0) {
            hinweis("Noch nicht mit Home Assistant verbunden", "DoorCall richtet das Gerät von selbst ein (beim Start von Home Assistant, "
                    + "neue Geräte binnen 10 Minuten) – oder in Home Assistant › DoorCall › „Jetzt Geräte suchen“.", Gespraech.GRAU, null);
            fehlendeRechte();
            return;
        }
        fehlendeRechte();
        long bis = Anruf.prefs(this).getLong("stumm_bis", 0);
        if (System.currentTimeMillis() < bis)
            hinweis("🔕  Nicht stören bis " + android.text.format.DateFormat.format("HH:mm", bis),
                    "Klingeln kommt nur still. Antippen: wieder klingeln lassen.", 0xFFFBBF24,
                    () -> { Anruf.prefs(this).edit().putLong("stumm_bis", 0).apply(); zeigen(); });

        // Ziele (zuletzt bekannte sofort, frische von Home Assistant kommen nach)
        JSONArray z;
        try { z = new JSONArray(Anruf.prefs(this).getString("ziele_cache", "[]")); } catch (Exception e) { z = new JSONArray(); }
        java.util.List<String[]> tueren = new java.util.ArrayList<>(), haus = new java.util.ArrayList<>();
        for (int i = 0; i < z.length(); i++) {
            JSONObject o = z.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id"), name = o.optString("name");
            if (id.startsWith("klingel:")) {
                String sym = name.startsWith("📹") ? "📹" : "🚪";
                tueren.add(new String[]{id, name.replaceFirst("^\\S+\\s+", "").replaceFirst("\\s+anrufen$", ""), sym});
            } else if ("alle".equals(id)) haus.add(0, new String[]{id, "Alle", "📢"});
            else if (id.startsWith("gruppe:")) haus.add(new String[]{id, name.replaceFirst("^Gruppe\\s+", ""), "👥"});
            else haus.add(new String[]{id, name, "📱"});
        }
        abschnitt("📢  Hausruf");
        if (haus.isEmpty()) zeile(z.length() == 0 ? "Hole Geräte …" : "Keine anderen Geräte", 15, Gespraech.GRAU);
        else kacheln(haus, Gespraech.BLAU);
        if (!tueren.isEmpty()) {
            abschnitt("Türen & Kameras");
            kacheln(tueren, Gespraech.KACHEL);
        }
        abschnitt("Mehr");
        java.util.List<Object[]> mehr = new java.util.ArrayList<>();
        if (Anruf.verlaufListe(this).length() > 0) mehr.add(new Object[]{"🕘", "Wer war da", (Runnable) () -> gehe(VERLAUF)});
        if (System.currentTimeMillis() >= bis) mehr.add(new Object[]{"🔕", "Nicht stören", (Runnable) this::nichtStoerenWahl});
        mehr.add(new Object[]{"⚙", "Einstellungen", (Runnable) () -> gehe(EINST)});
        klein(mehr);
        long zeit = Anruf.prefs(this).getLong("zeit", 0);
        zeile(l.optBoolean("einrichten") ? "● Mit Home Assistant verbunden" : "● Verbunden · zuletzt geklingelt: " + l.optString("name")
                + " (" + android.text.format.DateFormat.format("dd.MM. HH:mm", zeit) + ")", 13, Gespraech.GRAU);
    }

    /** Ziele von Home Assistant holen und merken; Startseite neu zeichnen, wenn sich etwas geändert hat. */
    private void zieleHolen() {
        if (Anruf.letzter(this).length() == 0) return;
        Anruf.anHa(this, "hausruf_ziele", null, r -> {
            JSONArray z = r == null ? null : r.optJSONArray("ziele");
            if (z == null) return;
            String neu = z.toString();
            if (neu.equals(Anruf.prefs(this).getString("ziele_cache", ""))) return;
            Anruf.prefs(this).edit().putString("ziele_cache", neu).apply();
            if (START.equals(seite) && !isFinishing()) zeigen();
        });
    }

    /** Kachel angetippt: Tür/Kamera sofort, sonst erst fragen wie (normal/laut/lautlos/durchstellen, ggf. wer rangeht). */
    private void rufen(String id, String name) {
        HausrufWahl.artWaehlen(this, name, id, (art, runde) -> {
            JSONObject x = new JSONObject();
            try { x.put("ziele", new JSONArray().put(id)).put("art", art).put("runde", runde); } catch (Exception ignored) {}
            android.widget.Toast.makeText(this, id.startsWith("klingel:") ? "Verbinde mit " + name + " …" : "Rufe " + name + " …",
                    android.widget.Toast.LENGTH_SHORT).show();
            Anruf.anHa(this, "hausruf_start", x, rr -> {
                if (rr == null || !rr.optBoolean("ok", true))
                    android.widget.Toast.makeText(this, rr == null ? "Home Assistant nicht erreichbar" : rr.optString("text"),
                            android.widget.Toast.LENGTH_LONG).show();
            });
        }, null);
    }

    /** Fehlende Rechte deutlich zeigen (führt in die Einstellungen). */
    private void fehlendeRechte() {
        java.util.List<String> fehlt = new java.util.ArrayList<>();
        if (!mikOk()) fehlt.add("Mikrofon");
        if (!benOk()) fehlt.add("Benachrichtigungen");
        if (!vollOk()) fehlt.add("Vollbild-Anrufe");
        if (!ueberOk()) fehlt.add("Fenster über anderen Apps");
        if (!fehlt.isEmpty()) hinweis("⚠  Einrichtung unvollständig", "Es fehlt: " + String.join(", ", fehlt) + ". Antippen zum Erlauben.",
                Gespraech.ROT, () -> gehe(EINST));
    }

    // ------------------------------------------------------------ Einstellungen
    private void einstellungen() {
        unterKopf("Einstellungen");
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}

        abschnitt("Berechtigungen");
        boolean mik = mikOk(), ben = benOk(), voll = vollOk(), ueber = ueberOk();
        zeile((mik ? "✓ " : "✗ ") + "Mikrofon", 16, mik ? Gespraech.GRUEN : Gespraech.ROT);
        zeile((ben ? "✓ " : "✗ ") + "Benachrichtigungen", 16, ben ? Gespraech.GRUEN : Gespraech.ROT);
        zeile((voll ? "✓ " : "✗ ") + "Anruf im Vollbild (auch gesperrt)", 16, voll ? Gespraech.GRUEN : Gespraech.ROT);
        zeile((ueber ? "✓ " : "✗ ") + "Hausruf-Fenster sofort zeigen (über anderen Apps)", 16, ueber ? Gespraech.GRUEN : Gespraech.ROT);
        if (!mik || !ben) knopf("Rechte erteilen", () -> requestPermissions(new String[]{
                Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1));
        if (!voll) knopf("Vollbild-Anrufe erlauben", () -> startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:" + getPackageName()))));
        if (!ueber) knopf("Fenster über anderen Apps erlauben", () -> startActivity(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));

        abschnitt("Nicht stören");
        long bis = Anruf.prefs(this).getLong("stumm_bis", 0);
        if (System.currentTimeMillis() < bis) {
            zeile("🔕 Aktiv bis " + android.text.format.DateFormat.format("HH:mm", bis) + " – Klingeln kommt nur still", 15, 0xFFFBBF24);
            knopf("Wieder klingeln lassen", () -> { Anruf.prefs(this).edit().putLong("stumm_bis", 0).apply(); zeigen(); });
        } else {
            int dauer = Anruf.prefs(this).getInt("stumm_dauer_min", 60), bisMin = Anruf.prefs(this).getInt("stumm_bis_min", 7 * 60);
            knopf("Für " + dauerText(dauer) + " …", () -> stummFuer(dauer));
            knopf(String.format(java.util.Locale.GERMANY, "Bis %02d:%02d Uhr …", bisMin / 60, bisMin % 60), () -> stummBis(bisMin));
        }
        erklaerung("Nur für diese App auf diesem Gerät: Klingeln und Hausruf kommen dann als stille Benachrichtigung.");

        abschnitt("Gespräch");
        boolean schwebend = "schwebend".equals(Anruf.prefs(this).getString("dock_stil", "durchgehend"));
        knopf("Knopfleiste: " + (schwebend ? "schwebend (runde Knöpfe)" : "durchgehend") + "  ⇄", () -> {
            Anruf.prefs(this).edit().putString("dock_stil", schwebend ? "durchgehend" : "schwebend").apply(); zeigen();
        });
        boolean medien = "medien".equals(Anruf.prefs(this).getString("ton_kanal", "anruf"));
        knopf("Lautstärketasten: " + (medien ? "Medien-Lautstärke" : "Anruf-Lautstärke (Medien folgt)") + "  ⇄", () -> {
            Anruf.prefs(this).edit().putString("ton_kanal", medien ? "anruf" : "medien").apply(); zeigen();
        });
        String sp = Anruf.prefs(this).getString("sprechen", "ha");
        knopf("Sprechen mit mehreren: " + ("einer".equals(sp) ? "immer nur einer (schnell)" : "gemischt".equals(sp)
                ? "alle gleichzeitig" : "wie in Home Assistant") + " …", () -> wahl("Wie sollen mehrere sprechen?",
                new String[]{"Wie in Home Assistant eingestellt", "Immer nur einer (schnell)", "Alle gleichzeitig (gemischt)"},
                new String[]{"ha", "einer", "gemischt"}, sp, w -> Anruf.prefs(this).edit().putString("sprechen", w).apply()));
        erklaerung("Schnell: dein Mikrofon geht direkt an die Tür, kaum Verzögerung – es spricht immer nur einer. Gemischt: "
                + "mehrere können gleichzeitig reden, die Stimme kommt etwa 0,3–0,8 s später an. Gilt, wenn du das Gespräch leitest.");
        String sp2 = Anruf.prefs(this).getString("sparen", "aus");
        knopf("Datensparmodus: " + ("immer".equals(sp2) ? "immer" : "mobil".equals(sp2) ? "nur mit mobilen Daten" : "aus") + " …",
                () -> wahl("Datensparmodus", new String[]{"Aus – immer Live-Video", "Nur mit mobilen Daten (unterwegs)", "Immer"},
                        new String[]{"aus", "mobil", "immer"}, sp2, w -> Anruf.prefs(this).edit().putString("sparen", w).apply()));
        erklaerung("Standbild der Tür mit „Aktualisieren“ statt Live-Video; „Live starten“ schaltet Video und Sprechen trotzdem ein.");
        int ks = Anruf.prefs(this).getInt("klingel_s", 0);
        knopf("Klingeln: " + (ks > 0 ? ks + " Sekunden" : "wie in Home Assistant (" + Anruf.letzter(this).optInt("dauer", 30) + " s)") + " …", () -> {
            final int[] werte = {0, 15, 20, 30, 45, 60, 90, 120};
            String[] namen = new String[werte.length], ws = new String[werte.length];
            for (int i = 0; i < werte.length; i++) { namen[i] = werte[i] == 0 ? "wie in Home Assistant" : werte[i] + " Sekunden"; ws[i] = "" + werte[i]; }
            wahl("Wie lange klingeln?", namen, ws, "" + ks, w -> Anruf.prefs(this).edit().putInt("klingel_s", Integer.parseInt(w)).apply());
        });

        JSONObject l = Anruf.letzter(this);
        if (l.length() > 0) {
            abschnitt("Testen");
            knopf("Probeanruf", () -> {   // erst HA (echter Anruf: Foto, Tür öffnen, Schalter); geht das nicht, nur hier üben
                android.widget.Toast.makeText(this, "Probeanruf wird angefordert …", android.widget.Toast.LENGTH_SHORT).show();
                Anruf.probeAnfordern(l, r -> {
                    if (r == null) probeLokal(l);
                    else if (!r.isEmpty()) android.widget.Toast.makeText(this, "Home Assistant: " + r, android.widget.Toast.LENGTH_LONG).show();
                });
            });
            erklaerung("Lässt nur dieses Gerät klingeln – mit Bild der Tür, wie bei einem echten Klingeln.");
        }

        abschnitt("Über");
        zeile(l.length() == 0 ? "Noch nicht mit Home Assistant verbunden" : "Mit Home Assistant verbunden", 15, Gespraech.GRAU);
        knopf("Nach Update suchen (Play Store)", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getPackageName()))); }
            catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName()))); }
        });
        zeile("Türklingel " + ver + " · DoorCall Intercom", 13, Gespraech.GRAU);
    }

    private void nichtStoerenWahl() {
        int dauer = Anruf.prefs(this).getInt("stumm_dauer_min", 60), bisMin = Anruf.prefs(this).getInt("stumm_bis_min", 7 * 60);
        String[] namen = {"Für " + dauerText(dauer), String.format(java.util.Locale.GERMANY, "Bis %02d:%02d Uhr", bisMin / 60, bisMin % 60),
                "Andere Dauer …", "Andere Uhrzeit …"};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Nicht stören")
                .setItems(namen, (dd, w) -> {
                    if (w == 0) stummSetzen(System.currentTimeMillis() + dauer * 60_000L);
                    else if (w == 1) stummSetzen(bisZeit(bisMin));
                    else if (w == 2) stummFuer(dauer);
                    else stummBis(bisMin);
                }).setNegativeButton("Abbrechen", null).show();
    }

    private void stummFuer(int dauer) {
        rad("Wie lange nicht stören? (Std : Min)", dauer, m -> {
            if (m <= 0) return;
            Anruf.prefs(this).edit().putInt("stumm_dauer_min", m).apply();
            stummSetzen(System.currentTimeMillis() + m * 60_000L);
        });
    }

    private void stummBis(int bisMin) {
        rad("Nicht stören bis (Uhrzeit)", bisMin, m -> { Anruf.prefs(this).edit().putInt("stumm_bis_min", m).apply(); stummSetzen(bisZeit(m)); });
    }

    private static long bisZeit(int m) {
        java.util.Calendar k = java.util.Calendar.getInstance();
        k.set(java.util.Calendar.HOUR_OF_DAY, m / 60); k.set(java.util.Calendar.MINUTE, m % 60); k.set(java.util.Calendar.SECOND, 0);
        if (k.getTimeInMillis() <= System.currentTimeMillis()) k.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return k.getTimeInMillis();
    }

    private void stummSetzen(long bis) { Anruf.prefs(this).edit().putLong("stumm_bis", bis).apply(); zeigen(); }

    private void wahl(String titel, String[] namen, String[] werte, String akt, java.util.function.Consumer<String> ok) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(titel)
                .setSingleChoiceItems(namen, Math.max(0, java.util.Arrays.asList(werte).indexOf(akt)), (dd, w) -> {
                    ok.accept(werte[w]); dd.dismiss(); zeigen();
                }).setNegativeButton("Abbrechen", null).show();
    }

    private boolean mikOk() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }
    private boolean benOk() { return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED; }
    private boolean vollOk() { NotificationManager nm = Anruf.nm(this); return Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(); }
    private boolean ueberOk() { return Settings.canDrawOverlays(this); }

    private static String dauerText(int m) {
        return m % 60 == 0 ? (m / 60) + (m == 60 ? " Stunde" : " Stunden") : m < 60 ? m + " Min" : (m / 60) + " Std " + (m % 60) + " Min";
    }

    /** Uhrzeit-Rad (Drehräder, 24 h); Ergebnis in Minuten. */
    private void rad(String titel, int min, java.util.function.IntConsumer ok) {
        android.app.TimePickerDialog dlg = new android.app.TimePickerDialog(this, android.R.style.Theme_Holo_Dialog,
                (v, h, m) -> ok.accept(h * 60 + m), min / 60, min % 60, true);
        dlg.setTitle(titel);
        dlg.show();
    }

    /** Probeanruf ohne HA: altes Foto aus dem Verlauf, Tür öffnen geht dabei nicht. Wird nicht als "Zuletzt" gespeichert. */
    private void probeLokal(JSONObject l) {
        android.widget.Toast.makeText(this, "Home Assistant nicht erreichbar – Probe nur auf diesem Gerät", android.widget.Toast.LENGTH_LONG).show();
        try {
            JSONObject j = new JSONObject(l.toString()).put("id", "probe_" + System.currentTimeMillis()).put("webhook", "")
                    .put("webhook_extern", "").put("oeffnen", false);
            JSONArray v = Anruf.verlaufListe(this);
            for (int i = 0; i < v.length(); i++) {
                String f = v.optJSONObject(i) == null ? "" : v.optJSONObject(i).optString("bild");
                if (!f.isEmpty()) { j.put("bild_lokal", new java.io.File(new java.io.File(getFilesDir(), "verlauf"), f).getPath()); break; }
            }
            Anruf.klingeln(this, j);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ Wer war da
    private void verlauf() {
        unterKopf("Wer war da");
        JSONArray v = Anruf.verlaufListe(this);
        if (v.length() == 0) { zeile("Noch niemand.", 15, Gespraech.GRAU); return; }
        java.io.File ordner = new java.io.File(getFilesDir(), "verlauf");
        for (int i = 0; i < v.length(); i++) {
            JSONObject e = v.optJSONObject(i);
            if (e == null) continue;
            LinearLayout z = new LinearLayout(this);
            z.setGravity(Gravity.CENTER_VERTICAL);
            z.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
            android.widget.ImageView b = new android.widget.ImageView(this);
            b.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            String f = e.optString("bild");
            if (!f.isEmpty()) b.setImageBitmap(android.graphics.BitmapFactory.decodeFile(new java.io.File(ordner, f).getPath()));
            else b.setImageResource(R.drawable.ic_launcher);
            z.addView(b, new LinearLayout.LayoutParams((int) (112 * d), (int) (84 * d)));
            String aus = e.optString("ausgang");
            int farbe = aus.equals("verpasst") ? Gespraech.ROT : aus.equals("angenommen") ? Gespraech.GRUEN : Gespraech.GRAU;
            TextView t = text(e.optString("name") + "\n" + android.text.format.DateFormat.format("EEE dd.MM. HH:mm", e.optLong("t")) + " · " + aus, 15, farbe);
            t.setPadding((int) (14 * d), 0, 0, 0);
            z.addView(t);
            liste.addView(z);
        }
    }

    // ------------------------------------------------------------ Bausteine
    private void unterKopf(String titel) {
        LinearLayout kopf = new LinearLayout(this);
        kopf.setGravity(Gravity.CENTER_VERTICAL);
        TextView pfeil = text("‹", 34, Gespraech.SCHRIFT);
        pfeil.setPadding(0, 0, (int) (14 * d), (int) (4 * d));
        pfeil.setContentDescription("Zurück");
        pfeil.setOnClickListener(v -> gehe(START));
        kopf.addView(pfeil);
        kopf.addView(text(titel, 26, Gespraech.SCHRIFT));
        kopf.setOnClickListener(v -> gehe(START));
        liste.addView(kopf);
        linie();
    }

    private void linie() {
        View linie = new View(this);   // blaue Linie wie auf der Remote
        linie.setBackgroundColor(Gespraech.BLAU);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (3 * d));
        lp.bottomMargin = (int) (8 * d);
        liste.addView(linie, lp);
    }

    private void abschnitt(String t) {
        TextView v = text(t, 20, Gespraech.SCHRIFT);
        v.setPadding(0, (int) (18 * d), 0, (int) (4 * d));
        liste.addView(v);
    }

    private void erklaerung(String t) {
        TextView v = text(t, 13, Gespraech.GRAU);
        v.setPadding((int) (4 * d), (int) (4 * d), (int) (4 * d), 0);
        liste.addView(v);
    }

    private TextView text(String t, int sp, int farbe) {
        TextView v = new TextView(this);
        v.setTypeface(Gespraech.schrift(this));
        v.setText(t); v.setTextSize(sp); v.setTextColor(farbe);
        return v;
    }

    private void zeile(String t, int sp, int farbe) {
        TextView v = text(t, sp, farbe);
        v.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        liste.addView(v);
    }

    private GradientDrawable form(int farbe, int rand) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(14 * d);
        g.setColor(farbe);
        if (rand != 0) g.setStroke((int) d, rand);
        return g;
    }

    /** Farbiger Hinweis-Kasten (Titel + Text), optional antippbar. */
    private void hinweis(String titel, String text, int farbe, Runnable r) {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));
        k.setBackground(form(Gespraech.KACHEL, farbe));
        k.addView(text(titel, 17, farbe));
        TextView t = text(text, 14, Gespraech.GRAU);
        t.setPadding(0, (int) (4 * d), 0, 0);
        k.addView(t);
        if (r != null) k.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (10 * d);
        liste.addView(k, lp);
    }

    /** Große Ziel-Kacheln im Raster (Symbol oben, Name darunter); Spalten nach Bildschirmbreite. */
    private void kacheln(java.util.List<String[]> ziele, int farbe) {
        int breiteDp = (int) (getResources().getDisplayMetrics().widthPixels / d) - 40;
        int spalten = Math.max(2, Math.min(5, breiteDp / 170));
        LinearLayout reihe = null;
        for (int i = 0; i < ziele.size(); i++) {
            if (i % spalten == 0) {
                reihe = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.topMargin = (int) (10 * d);
                liste.addView(reihe, rp);
            }
            String[] z = ziele.get(i);
            LinearLayout k = new LinearLayout(this);
            k.setOrientation(LinearLayout.VERTICAL);
            k.setGravity(Gravity.CENTER);
            k.setPadding((int) (8 * d), (int) (16 * d), (int) (8 * d), (int) (14 * d));
            k.setBackground(form(farbe, farbe == Gespraech.KACHEL ? Gespraech.RAND : 0));
            TextView sym = text(z[2], 30, Gespraech.SCHRIFT);
            sym.setGravity(Gravity.CENTER);
            k.addView(sym, new LinearLayout.LayoutParams(-1, -2));
            TextView n = text(z[1], 15, Gespraech.SCHRIFT);
            n.setGravity(Gravity.CENTER);
            n.setMaxLines(2);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(0, (int) (6 * d), 0, 0);
            k.addView(n, new LinearLayout.LayoutParams(-1, -2));
            k.setOnClickListener(v -> rufen(z[0], z[1]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (112 * d), 1);
            if (i % spalten > 0) lp.leftMargin = (int) (10 * d);
            reihe.addView(k, lp);
        }
        if (reihe != null) for (int r = ziele.size() % spalten; r > 0 && r < spalten; r++) {   // letzte Reihe auffüllen
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (112 * d), 1);
            lp.leftMargin = (int) (10 * d);
            reihe.addView(new View(this), lp);
        }
    }

    /** Kleine Kacheln in einer Reihe (Symbol + Text nebeneinander). */
    private void klein(java.util.List<Object[]> eintraege) {
        LinearLayout reihe = new LinearLayout(this);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = (int) (10 * d);
        liste.addView(reihe, rp);
        for (int i = 0; i < eintraege.size(); i++) {
            Object[] e = eintraege.get(i);
            TextView k = text(e[0] + "  " + e[1], 15, Gespraech.SCHRIFT);
            k.setGravity(Gravity.CENTER);
            k.setPadding((int) (8 * d), (int) (14 * d), (int) (8 * d), (int) (14 * d));
            k.setBackground(form(Gespraech.KACHEL, Gespraech.RAND));
            k.setOnClickListener(v -> ((Runnable) e[2]).run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = (int) (10 * d);
            reihe.addView(k, lp);
        }
    }

    /** Breite Kachel (Liste in den Einstellungen). */
    private void knopf(String t, Runnable r) {
        TextView b = text(t, 16, Gespraech.SCHRIFT);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
        b.setBackground(form(Gespraech.KACHEL, Gespraech.RAND));
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (8 * d);
        liste.addView(b, lp);
    }
}
