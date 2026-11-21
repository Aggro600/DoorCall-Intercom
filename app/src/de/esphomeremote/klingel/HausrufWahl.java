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
            starten(z);
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
                    .setItems(namen, (d, w) -> starten(new JSONArray().put(z.optJSONObject(w).optString("id"))))
                    .setNegativeButton("Abbrechen", (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
        });
    }

    private void starten(JSONArray ziele) {
        JSONObject x = new JSONObject();
        try { x.put("ziele", ziele); } catch (Exception ignored) {}
        Anruf.anHa(this, "hausruf_start", x, rr -> {
            Toast.makeText(this, rr == null ? "Home Assistant nicht erreichbar" : rr.optString("text"), Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
