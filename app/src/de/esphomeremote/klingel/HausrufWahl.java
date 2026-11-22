package de.esphomeremote.klingel;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hausruf vom Dashboard (2026-11-21): doorcall://hausruf öffnet hier die Ziel-Auswahl, doorcall://hausruf?an=<Gerät,
 * gruppe:Name oder alle> ruft direkt an. Absender ist immer DIESES Gerät (Home Assistant kennt den Drücker nicht).
 */
public class HausrufWahl extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        App.ctx = getApplicationContext();
        if (Anruf.letzter(this).length() == 0) {
            Toast.makeText(this, "Türklingel ist noch nicht mit Home Assistant verbunden", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Uri u = getIntent() == null ? null : getIntent().getData();
        String an = u == null ? null : u.getQueryParameter("an");
        if (an != null && !an.trim().isEmpty()) {
            JSONArray z = new JSONArray();
            for (String s : an.split(",")) if (!s.trim().isEmpty()) z.put(s.trim());
            String art = u.getQueryParameter("art"), runde = u.getQueryParameter("runde");
            starten(z, art == null ? "normal" : art, runde == null ? "alle" : runde);
            return;
        }
        Anruf.anHa(this, "hausruf_ziele", null, r -> {
            JSONArray z = r == null ? null : r.optJSONArray("ziele");
            if (z == null || z.length() == 0) {
                Toast.makeText(this, r == null ? "Home Assistant nicht erreichbar" : r.optString("text", "Keine Geräte"), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            String[] namen = new String[z.length()];
            for (int i = 0; i < namen.length; i++) namen[i] = z.optJSONObject(i).optString("name");
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Hausruf an …")
                    .setItems(namen, (d, w) -> artWaehlen(this, z.optJSONObject(w).optString("name"), z.optJSONObject(w).optString("id"),
                            (art, runde) -> starten(new JSONArray().put(z.optJSONObject(w).optString("id")), art, runde), this::finish))
                    .setNegativeButton("Abbrechen", (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
        });
    }

    /** Wie soll es klingeln? normal / laut / durchstellen - bei Gruppe/Alle danach: wer geht ran? einer / alle (2026-11-22). */
    static void artWaehlen(android.app.Activity a, String ziel, String zielId, java.util.function.BiConsumer<String, String> fertig, Runnable abbruch) {
        if (zielId.startsWith("klingel:")) { fertig.accept("normal", "alle"); return; }   // Tür anrufen: keine Fragen
        String[] namen = {"🔔  Normal – wie am Gerät eingestellt", "🔊  Laut – Lautstärke erzwingen", "🔕  Lautlos – nur anzeigen, kein Ton", "⚡  Direkt durchstellen – nimmt automatisch an"};
        String[] werte = {"normal", "laut", "lautlos", "durchstellen"};
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Hausruf an " + ziel)
                .setItems(namen, (d, w) -> {
                    boolean mehrere = "alle".equals(zielId) || zielId.startsWith("gruppe:");
                    if (!mehrere || "durchstellen".equals(werte[w])) { fertig.accept(werte[w], "alle"); return; }
                    rundeWaehlen(a, ziel, r -> fertig.accept(werte[w], r), abbruch);
                })
                .setNegativeButton("Abbrechen", (d, w) -> { if (abbruch != null) abbruch.run(); })
                .setOnCancelListener(d -> { if (abbruch != null) abbruch.run(); })
                .show();
    }

    static void rundeWaehlen(android.app.Activity a, String ziel, java.util.function.Consumer<String> fertig, Runnable abbruch) {
        String[] namen = {"☝️  Einer – wer zuerst annimmt, bei den anderen hört es auf", "👥  Alle – alle klingeln weiter, jeder kann dazukommen"};
        String[] werte = {"einer", "alle"};
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Wer soll rangehen?")
                .setItems(namen, (d, w) -> fertig.accept(werte[w]))
                .setNegativeButton("Abbrechen", (d, w) -> { if (abbruch != null) abbruch.run(); })
                .setOnCancelListener(d -> { if (abbruch != null) abbruch.run(); })
                .show();
    }

    private void starten(JSONArray ziele, String art, String runde) {
        JSONObject x = new JSONObject();
        try { x.put("ziele", ziele).put("art", art).put("runde", runde); } catch (Exception ignored) {}
        Anruf.anHa(this, "hausruf_start", x, rr -> {
            Toast.makeText(this, rr == null ? "Home Assistant nicht erreichbar" : rr.optString("text"), Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
