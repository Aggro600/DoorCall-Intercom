package de.esphomeremote.klingel;

import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mikrofon an Home Assistant schicken (2026-11-22).
 * Intern (WLAN, direkt zu HA): ein endloser POST - geringste Verzögerung.
 * Extern (über Reverse-Proxy wie Nginx Proxy Manager): Proxys sammeln einen Upload meist komplett, bevor sie ihn
 * weitergeben - ein endloser POST kommt dann nie an. Deshalb dort kurze POSTs mit je ~200 ms Ton (Verbindung bleibt offen).
 */
final class Hochladen {
    interface Quelle {
        /** Liest bis zu buf.length Bytes (blockiert kurz); 0 = gerade nichts. */
        int lesen(byte[] buf) throws Exception;
        boolean weiter();
    }

    private Hochladen() {}

    /** true = sauber beendet (weiter() false), false = Verbindungsfehler (nächste Adresse probieren). */
    static boolean senden(String basis, boolean extern, String pfadMitQuery, Quelle q, int frame, int paketBytes, String name) {
        return extern ? pakete(basis + pfadMitQuery + "&paket=1", q, frame, paketBytes, name)
                : strom(basis + pfadMitQuery, q, frame, name);
    }

    private static boolean strom(String url, Quelle q, int frame, String name) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setChunkedStreamingMode(frame);
            c.setConnectTimeout(3000);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            OutputStream o = c.getOutputStream();
            Log.i(Anruf.TAG, name + " verbunden (Strom)");
            byte[] buf = new byte[frame];
            while (q.weiter()) {
                int n = q.lesen(buf);
                if (n > 0) { o.write(buf, 0, n); o.flush(); }
            }
            o.close();
            c.getResponseCode();
            return true;
        } catch (Exception e) {
            Log.w(Anruf.TAG, name + " (Strom): " + e);
            return false;
        } finally { if (c != null) c.disconnect(); }
    }

    private static boolean pakete(String url, Quelle q, int frame, int paketBytes, String name) {
        byte[] paket = new byte[paketBytes], buf = new byte[frame];
        boolean erstes = true;
        while (q.weiter()) {
            int voll = 0;
            while (voll < paketBytes && q.weiter()) {
                int n;
                try { n = q.lesen(buf); } catch (Exception e) { return false; }
                if (n <= 0) continue;
                n = Math.min(n, paketBytes - voll);
                System.arraycopy(buf, 0, paket, voll, n);
                voll += n;
            }
            if (voll == 0) continue;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(voll);
                c.setConnectTimeout(4000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                OutputStream o = c.getOutputStream();
                o.write(paket, 0, voll);
                o.close();
                int code = c.getResponseCode();
                if (code >= 400) { Log.w(Anruf.TAG, name + " (Pakete): HTTP " + code); return false; }
                if (erstes) { Log.i(Anruf.TAG, name + " verbunden (Pakete)"); erstes = false; }
            } catch (Exception e) {
                Log.w(Anruf.TAG, name + " (Pakete): " + e);
                return false;
            }
            // kein disconnect(): Verbindung für das nächste Paket offen halten (keep-alive)
        }
        return true;
    }
}
