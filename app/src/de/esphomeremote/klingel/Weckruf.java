package de.esphomeremote.klingel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

/**
 * Weckruf aus Home Assistant über die Companion-App:
 *   message: command_broadcast_intent
 *   data: intent_package_name: de.esphomeremote.klingel
 *         intent_action: de.esphomeremote.klingel.KLINGELN   (oder .BEENDEN)
 *         intent_extras: "d:<Base64-URL des JSON>"
 * Base64, weil die Companion-App die Extras an ':' und ',' trennt (Adressen enthalten beides).
 * Zum Testen per ADB geht auch: am broadcast -a ... -n de.esphomeremote.klingel/.Weckruf --es j '{"name":...}'
 */
public class Weckruf extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        App.ctx = c.getApplicationContext();
        JSONObject j = lesen(i);
        String a = String.valueOf(i.getAction());
        Log.i(Anruf.TAG, "Weckruf " + a + " " + j.optString("name") + " id=" + j.optString("id"));
        if (a.endsWith(".EINRICHTEN")) Anruf.einrichten(c, j);   // stiller Gruß von DoorCall: Adressen merken, kein Klingeln
        else if (a.endsWith(".BEENDEN")) Anruf.vonAussenBeenden(c, j);
        else { PendingResult pr = goAsync(); Anruf.klingeln(c, j, pr::finish); }
    }

    static JSONObject lesen(Intent i) {
        try {
            String d = i.getStringExtra("d");
            if (d != null && !d.isEmpty())
                return new JSONObject(new String(Base64.decode(d.trim(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), "UTF-8"));
            String j = i.getStringExtra("j");
            if (j != null) return new JSONObject(j);
        } catch (Exception e) { Log.w(Anruf.TAG, "Weckruf nicht lesbar: " + e); }
        return new JSONObject();
    }
}
