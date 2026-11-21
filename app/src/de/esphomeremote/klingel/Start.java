package de.esphomeremote.klingel;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Start-Seite: Rechte prüfen und erteilen, Probeanruf mit der zuletzt empfangenen Einstellung. */
public class Start extends Activity {
    private LinearLayout liste;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        App.ctx = getApplicationContext();
        Anruf.kanaele(this);
        Anruf.kontoAnmelden(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Gespraech.BG);
        liste = new LinearLayout(this);
        liste.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        liste.setPadding(p, p * 2, p, p);
        sv.addView(liste);
        sv.setOnApplyWindowInsetsListener((v, ins) -> {   // randlos (targetSdk 36): nicht unter die Systemleisten
            android.graphics.Insets i = ins.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return ins;
        });
        setContentView(sv);
    }

    @Override
    protected void onResume() { super.onResume(); zeigen(); }

    private void zeigen() {
        liste.removeAllViews();
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        zeile("Türklingel  ·  " + ver, 26, Gespraech.SCHRIFT);   // Versionsnummer gut lesbar im Titel (Nutzer 2026-11-21)
        android.view.View linie = new android.view.View(this);   // blaue Linie wie auf der Remote
        linie.setBackgroundColor(Gespraech.BLAU);
        liste.addView(linie, new LinearLayout.LayoutParams(-1, (int) (3 * getResources().getDisplayMetrics().density)));
        zeile("Klingeln an der Tür kommt als Anruf. Home Assistant weckt diese App über die Home-Assistant-App.", 15, Gespraech.GRAU);
        boolean mik = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean ben = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        NotificationManager nm = Anruf.nm(this);
        boolean voll = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent();
        zeile((mik ? "✓ " : "✗ ") + "Mikrofon", 17, mik ? Gespraech.GRUEN : Gespraech.ROT);
        zeile((ben ? "✓ " : "✗ ") + "Benachrichtigungen", 17, ben ? Gespraech.GRUEN : Gespraech.ROT);
        zeile((voll ? "✓ " : "✗ ") + "Anruf im Vollbild (auch gesperrt)", 17, voll ? Gespraech.GRUEN : Gespraech.ROT);
        if (!mik || !ben) knopf("Rechte erteilen", () -> requestPermissions(new String[]{
                Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1));
        if (!voll) knopf("Vollbild-Anrufe erlauben", () -> startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:" + getPackageName()))));
        // "Nicht stören" nur für diese App auf diesem Gerät
        long bis = Anruf.prefs(this).getLong("stumm_bis", 0);
        if (System.currentTimeMillis() < bis) {
            zeile("🔕 Nicht stören bis " + android.text.format.DateFormat.format("HH:mm", bis) + " – Klingeln kommt nur still", 17, 0xFFFBBF24);
            knopf("Wieder klingeln lassen", () -> { Anruf.prefs(this).edit().putLong("stumm_bis", 0).apply(); zeigen(); });
        } else {
            // Zeiten einstellbar (Nutzer 2026-11-21): Antippen öffnet das Uhrzeit-Rad mit dem zuletzt gewählten Wert
            int dauer = Anruf.prefs(this).getInt("stumm_dauer_min", 60), bisMin = Anruf.prefs(this).getInt("stumm_bis_min", 7 * 60);
            knopf("Nicht stören für " + dauerText(dauer) + " …", () -> rad("Wie lange nicht stören? (Std : Min)", dauer, m -> {
                if (m <= 0) return;
                Anruf.prefs(this).edit().putInt("stumm_dauer_min", m).putLong("stumm_bis", System.currentTimeMillis() + m * 60_000L).apply();
                zeigen();
            }));
            knopf(String.format(java.util.Locale.GERMANY, "Nicht stören bis %02d:%02d Uhr …", bisMin / 60, bisMin % 60),
                    () -> rad("Nicht stören bis (Uhrzeit)", bisMin, m -> {
                java.util.Calendar k = java.util.Calendar.getInstance();
                k.set(java.util.Calendar.HOUR_OF_DAY, m / 60); k.set(java.util.Calendar.MINUTE, m % 60); k.set(java.util.Calendar.SECOND, 0);
                if (k.getTimeInMillis() <= System.currentTimeMillis()) k.add(java.util.Calendar.DAY_OF_MONTH, 1);
                Anruf.prefs(this).edit().putInt("stumm_bis_min", m).putLong("stumm_bis", k.getTimeInMillis()).apply();
                zeigen();
            }));
        }
        JSONObject l = Anruf.letzter(this);
        long zeit = Anruf.prefs(this).getLong("zeit", 0);
        zeile(l.length() == 0 ? "Noch kein Klingeln von Home Assistant empfangen."
                : "Zuletzt: " + l.optString("name") + " (" + android.text.format.DateFormat.format("dd.MM. HH:mm", zeit) + ")", 15, Gespraech.GRAU);
        if (l.length() > 0) knopf("Probeanruf", () -> {
            // Erst Home Assistant bitten (echter Anruf: Foto, Tür öffnen, Schalter); geht das nicht, nur hier üben
            android.widget.Toast.makeText(this, "Probeanruf wird angefordert …", android.widget.Toast.LENGTH_SHORT).show();
            Anruf.probeAnfordern(l, r -> {
                if (r == null) probeLokal(l);
                else if (!r.isEmpty()) android.widget.Toast.makeText(this, "Home Assistant: " + r, android.widget.Toast.LENGTH_LONG).show();
            });
        });
        // Einstellungen (Nutzer 2026-11-21)
        zeile("Einstellungen", 22, Gespraech.SCHRIFT);
        boolean schwebend = "schwebend".equals(Anruf.prefs(this).getString("dock_stil", "durchgehend"));
        knopf("Dock: " + (schwebend ? "schwebend (runde Knöpfe)" : "durchgehend (wie die Remote)") + "  ⇄", () -> {
            Anruf.prefs(this).edit().putString("dock_stil", schwebend ? "durchgehend" : "schwebend").apply(); zeigen();
        });
        boolean medien = "medien".equals(Anruf.prefs(this).getString("ton_kanal", "anruf"));
        knopf("Lautstärketasten im Gespräch: " + (medien ? "Medien-Lautstärke" : "Anruf-Lautstärke (Medien folgt)") + "  ⇄", () -> {
            Anruf.prefs(this).edit().putString("ton_kanal", medien ? "anruf" : "medien").apply(); zeigen();
        });
        // Sprechen mit mehreren Geräten (2026-11-21): eigener Standard, gilt wenn dieses Gerät das Gespräch leitet
        String sp = Anruf.prefs(this).getString("sprechen", "ha");
        knopf("Sprechen mit mehreren: " + ("einer".equals(sp) ? "immer nur einer (schnell)" : "gemischt".equals(sp)
                ? "alle gleichzeitig (gemischt)" : "wie in Home Assistant") + " …", () -> {
            final String[] werte = {"ha", "einer", "gemischt"};
            String[] namen = {"Wie in Home Assistant eingestellt", "Immer nur einer (schnell)", "Alle gleichzeitig (gemischt)"};
            int akt = java.util.Arrays.asList(werte).indexOf(sp);
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Wie sollen mehrere sprechen?")
                    .setSingleChoiceItems(namen, Math.max(0, akt), (d, w) -> {
                        Anruf.prefs(this).edit().putString("sprechen", werte[w]).apply();
                        d.dismiss();
                        zeigen();
                    })
                    .setMessage(null)
                    .setNegativeButton("Abbrechen", null)
                    .show();
        });
        zeile("Schnell: Dein Mikrofon geht direkt an die Tür, kaum Verzögerung – es spricht immer nur einer, wer sein "
                + "Mikrofon anmacht, schaltet den vorigen stumm. Gemischt: Home Assistant mischt alle Mikrofone, mehrere können "
                + "gleichzeitig reden, die Stimme kommt aber etwa 0,3–0,8 s später draußen an. Gilt, wenn du das Gespräch "
                + "leitest; im Gespräch kannst du jederzeit umstellen.", 13, Gespraech.GRAU);
        int ks = Anruf.prefs(this).getInt("klingel_s", 0);
        knopf("Klingeln: " + (ks > 0 ? ks + " Sekunden" : "wie in Home Assistant (" + Anruf.letzter(this).optInt("dauer", 30) + " s)") + " …", () -> {
            final int[] werte = {0, 15, 20, 30, 45, 60, 90, 120};
            String[] namen = new String[werte.length];
            for (int i = 0; i < werte.length; i++) namen[i] = werte[i] == 0 ? "wie in Home Assistant" : werte[i] + " Sekunden";
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Wie lange klingeln?")
                    .setItems(namen, (d, w) -> { Anruf.prefs(this).edit().putInt("klingel_s", werte[w]).apply(); zeigen(); }).show();
        });
        knopf("Nach Update suchen (Play Store)", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getPackageName()))); }
            catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName()))); }
        });
        zeile("Version " + ver, 15, Gespraech.GRAU);
        verlaufZeigen();
    }

    private static String dauerText(int m) {
        return m % 60 == 0 ? (m / 60) + (m == 60 ? " Stunde" : " Stunden") : m < 60 ? m + " Min" : (m / 60) + " Std " + (m % 60) + " Min";
    }

    /** Uhrzeit-Rad (Drehräder, 24 h); Ergebnis in Minuten. */
    private void rad(String titel, int min, java.util.function.IntConsumer ok) {
        android.app.TimePickerDialog d = new android.app.TimePickerDialog(this, android.R.style.Theme_Holo_Dialog,
                (v, h, m) -> ok.accept(h * 60 + m), min / 60, min % 60, true);
        d.setTitle(titel);
        d.show();
    }

    /** Probeanruf ohne HA: altes Foto aus dem Verlauf, Tür öffnen geht dabei nicht. Wird nicht als "Zuletzt" gespeichert. */
    private void probeLokal(JSONObject l) {
        android.widget.Toast.makeText(this, "Home Assistant nicht erreichbar – Probe nur auf diesem Gerät", android.widget.Toast.LENGTH_LONG).show();
        {
            try {
                JSONObject j = new JSONObject(l.toString()).put("id", "probe_" + System.currentTimeMillis()).put("webhook", "")
                        .put("webhook_extern", "").put("oeffnen", false);
                org.json.JSONArray v = Anruf.verlaufListe(this);
                for (int i = 0; i < v.length(); i++) {
                    String f = v.optJSONObject(i) == null ? "" : v.optJSONObject(i).optString("bild");
                    if (!f.isEmpty()) { j.put("bild_lokal", new java.io.File(new java.io.File(getFilesDir(), "verlauf"), f).getPath()); break; }
                }
                Anruf.klingeln(this, j);
            }
            catch (Exception ignored) {}
        }
    }

    /** "Wer war da": letzte 20 Klingeln mit Foto, Zeit und Ausgang. */
    private void verlaufZeigen() {
        org.json.JSONArray v = Anruf.verlaufListe(this);
        if (v.length() == 0) return;
        zeile("Wer war da", 22, Gespraech.SCHRIFT);
        java.io.File ordner = new java.io.File(getFilesDir(), "verlauf");
        float d = getResources().getDisplayMetrics().density;
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
            z.addView(b, new LinearLayout.LayoutParams((int) (96 * d), (int) (72 * d)));
            TextView t = new TextView(this);
            String aus = e.optString("ausgang");
            int farbe = aus.equals("verpasst") ? Gespraech.ROT : aus.equals("angenommen") ? Gespraech.GRUEN : Gespraech.GRAU;
            t.setText(e.optString("name") + "\n" + android.text.format.DateFormat.format("EEE dd.MM. HH:mm", e.optLong("t")) + " · " + aus);
            t.setTextColor(farbe);
            t.setTypeface(Gespraech.schrift(this));
            t.setTextSize(15);
            t.setPadding((int) (12 * d), 0, 0, 0);
            z.addView(t);
            liste.addView(z);
        }
    }

    private void zeile(String t, int sp, int farbe) {
        TextView v = new TextView(this);
        v.setTypeface(Gespraech.schrift(this));
        v.setText(t); v.setTextSize(sp); v.setTextColor(farbe);
        v.setPadding(0, 12, 0, 12);
        liste.addView(v);
    }

    /** Kachel wie auf der Remote (dunkel, Rand, abgerundet). */
    private void knopf(String t, Runnable r) {
        float d = getResources().getDisplayMetrics().density;
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextSize(16);
        b.setTypeface(Gespraech.schrift(this));
        b.setTextColor(Gespraech.SCHRIFT);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(10 * d);
        g.setColor(Gespraech.KACHEL);
        g.setStroke((int) d, Gespraech.RAND);
        b.setBackground(g);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (8 * d);
        liste.addView(b, lp);
    }
}
