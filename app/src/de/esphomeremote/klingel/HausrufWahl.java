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
            String art = u.getQueryParameter("art");
            starten(z, art == null ? "normal" : art);
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
                    .setItems(namen, (d, w) -> artWaehlen(this, z.optJSONObject(w).optString("name"), art -> starten(new JSONArray().put(z.optJSONObject(w).optString("id")), art), this::finish))
                    .setNegativeButton("Abbrechen", (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
        });
    }

    /** Wie soll es klingeln? normal / laut / durchstellen (2026-11-22). */
    static void artWaehlen(android.app.Activity a, String ziel, java.util.function.Consumer<String> fertig, Runnable abbruch) {
        String[] namen = {"🔔  Normal – wie am Gerät eingestellt", "🔊  Laut – Lautstärke erzwingen", "⚡  Direkt durchstellen – nimmt automatisch an"};
        String[] werte = {"normal", "laut", "durchstellen"};
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Hausruf an " + ziel)
                .setItems(namen, (d, w) -> fertig.accept(werte[w]))
                .setNegativeButton("Abbrechen", (d, w) -> { if (abbruch != null) abbruch.run(); })
                .setOnCancelListener(d -> { if (abbruch != null) abbruch.run(); })
                .show();
    }

    private void starten(JSONArray ziele, String art) {
        JSONObject x = new JSONObject();
        try { x.put("ziele", ziele).put("art", art); } catch (Exception ignored) {}
        Anruf.anHa(this, "hausruf_start", x, rr -> {
            Toast.makeText(this, rr == null ? "Home Assistant nicht erreichbar" : rr.optString("text"), Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
