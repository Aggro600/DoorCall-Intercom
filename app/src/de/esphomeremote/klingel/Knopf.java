package de.esphomeremote.klingel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Knöpfe in den Benachrichtigungen, die keine Oberfläche öffnen (Ablehnen, Auflegen). */
public class Knopf extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        App.ctx = c.getApplicationContext();
        String a = String.valueOf(i.getAction());
        if (a.equals("ablehnen")) Anruf.ablehnen(c);
        else if (a.equals("auflegen")) Anruf.auflegen(c);
    }
}
