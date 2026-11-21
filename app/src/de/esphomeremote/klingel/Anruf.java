package de.esphomeremote.klingel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Zustand und Ablauf eines Tür-Anrufs. Ein Anruf kommt aus Home Assistant (Weckruf) mit einer Beschreibung als JSON:
 *   id      eindeutige Anruf-Kennung (HA beendet damit das Klingeln auf den anderen Geräten)
 *   name    Anzeigename, z. B. "Haustür"
 *   go2rtc  Basis-Adresse von go2rtc, z. B. http://192.168.1.10:1984
 *   src     go2rtc-Stream der Tür-Kamera
 *   webhook Adresse für Rückmeldungen (angenommen, abgelehnt, verpasst, aufgelegt, oeffnen) - optional
 *   oeffnen true = Knopf "Tür öffnen" zeigen
 *   dauer   Sekunden bis "verpasst" (Standard 30)
 *   geraet  Name dieses Geräts in HA (für die Rückmeldung)
 * Der Anruf läuft über Telecom (self-managed). Lehnt Android das ab, klingelt nur die Benachrichtigung.
 */
public final class Anruf {
    static final String TAG = "Tuerklingel";
    static final int AUS = 0, KLINGELT = 1, AKTIV = 2;
    static final String K_KLINGELN = "klingeln_v1", K_LAUFEND = "laufend_v2", K_VERPASST = "verpasst_v1", K_STILL = "still_v1";
    static final int N_ANRUF = 1, N_VERPASST = 2, N_ANGEBOT = 3, N_BEENDET = 4;
    static String endeGrund = "";          // HA hat das Gespräch absichtlich beendet (Mitschauen gesperrt / für alle beendet)
    static final String K_MITSCHAUEN = "mitschauen_v1";
    static JSONObject angebot;             // "Gespräch läuft – mitschauen" (von HA, Gesprächsrunde 2026-11-21)
    static JSONObject runde = new JSONObject();   // letzte Gesprächsrunde aus der Status-Antwort (Leiter, Teilnehmer, Sprecher)

    static int zustand = AUS;
    static JSONObject cfg = new JSONObject();
    static TuerDienst.Verbindung verbindung;
    static long beginn;
    static Runnable beiAenderung;          // Gespraech-Activity hört mit
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable ZEIT_UM = () -> { if (zustand == KLINGELT) verpasst(App.ctx); };

    private Anruf() {}

    /** Adresse einer HA-Schnittstelle: DoorCall schickt "api" (/api/doorcall) mit, ältere Anrufe = ESPHomeRemote-Pfade. */
    static String pfad(JSONObject j, String name) {
        String api = j.optString("api", "");
        return api.isEmpty() ? "/api/esphomeremote/klingel_" + name : api + "/" + name;
    }

    static String appVersion() {
        try { return App.ctx.getPackageManager().getPackageInfo(App.ctx.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    static String name() { return cfg.optString("name", "Tür"); }
    static String id() { return cfg.optString("id", ""); }

    // ---------- Telecom-Konto ----------
    static PhoneAccountHandle konto(Context c) {
        return new PhoneAccountHandle(new ComponentName(c, TuerDienst.class), "tuer");
    }

    static void kontoAnmelden(Context c) {
        TelecomManager tm = c.getSystemService(TelecomManager.class);
        if (tm == null) return;
        // In die Anrufliste der Telefon-App (Nutzer 2026-11-21): Eintrag "Haustür" / "Wohnungstür" (Adresse tuer:<Name>)
        Bundle extras = new Bundle();
        if (Build.VERSION.SDK_INT >= 30) extras.putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, true);
        PhoneAccount pa = PhoneAccount.builder(konto(c), "Türklingel")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED | PhoneAccount.CAPABILITY_VIDEO_CALLING
                        | PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING)
                .setIcon(Icon.createWithResource(c, R.drawable.ic_launcher))
                .setShortDescription("Türklingel")
                .setExtras(extras)
                .addSupportedUriScheme("tuer")
                .build();
        try { tm.registerPhoneAccount(pa); } catch (Exception e) { Log.w(TAG, "PhoneAccount: " + e); }
    }

    // ---------- Ablauf ----------
    static android.graphics.Bitmap bild;    // Klingelbild (Anruferbild, verpasst-Foto)

    static void klingeln(Context c, JSONObject neu) { klingeln(c, neu, null); }

    /** Holt zuerst (höchstens 1,5 s) das Klingelbild, dann klingelt es. fertig: für goAsync des Weckrufs. */
    static void klingeln(Context c, JSONObject neu, Runnable fertig) {
        App.ctx = c.getApplicationContext();
        if (zustand == AKTIV && System.currentTimeMillis() - beginn > 15 * 60_000L) { Log.w(TAG, "Gespräch hängt seit 15 min - beendet"); ende(c, null, DisconnectCause.LOCAL); }
        if (zustand == AKTIV) { Log.i(TAG, "Gespräch läuft, neues Klingeln nur gemeldet"); if (fertig != null) fertig.run(); return; }
        if (neu.optBoolean("sofort")) { sofortAnnehmen(c, neu); if (fertig != null) fertig.run(); return; }   // Hausruf: ich rufe selbst
        if (zustand == KLINGELT && verbindung != null) { cfg = neu; aktualisieren(); if (fertig != null) fertig.run(); return; }
        String lokal = neu.optString("bild_lokal", "");   // Probeanruf aus der App: letztes Foto aus "Wer war da"
        if (!lokal.isEmpty()) {
            bild = android.graphics.BitmapFactory.decodeFile(lokal);
            jetztKlingeln(c, neu); if (fertig != null) fertig.run(); return;
        }
        if (!neu.optBoolean("bild", false)) { bild = null; jetztKlingeln(c, neu); if (fertig != null) fertig.run(); return; }
        final boolean[] erledigt = {false};
        final Runnable los = () -> {
            if (erledigt[0]) return;
            erledigt[0] = true;
            jetztKlingeln(c, neu);
            if (fertig != null) fertig.run();
        };
        bild = null;
        new Thread(() -> {
            // Handy (2026-11-21): nach dem Aufwachen braucht das Netz oft länger als 1,5 s -> weiter laden, Bild nachreichen
            android.graphics.Bitmap b = bildHolen(neu, 8000);
            H.post(() -> {
                if (!erledigt[0]) { bild = b; los.run(); }
                else bildNachgeliefert(c, neu, b);
            });
        }).start();
        H.postDelayed(los, 1500);
    }

    /** Das Klingelbild kam erst nach dem Klingeln: in Verlauf, Anruferbild und ggf. verpasst-Meldung übernehmen. */
    private static void bildNachgeliefert(Context c, JSONObject j, android.graphics.Bitmap b) {
        if (b == null) { Log.i(TAG, "Klingelbild nicht bekommen"); return; }
        String aid = j.optString("id");
        verlaufBild(c, aid, b);
        if (!aid.equals(id())) return;
        bild = b;
        Log.i(TAG, "Klingelbild nachgereicht");
        if (zustand == KLINGELT) klingelnZeigen(c, false, true);
    }

    static android.graphics.Bitmap bildHolen(JSONObject j, int ms) {
        for (String basis : new String[]{j.optString("ha", ""), j.optString("extern", "")}) {
            if (basis.isEmpty()) continue;
            try {
                String url = basis + pfad(j, "bild") + "?id=" + Uri.encode(j.optString("id"))
                        + "&geraet=" + Uri.encode(j.optString("geraet")) + "&schluessel=" + Uri.encode(j.optString("schluessel"));
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                h.setConnectTimeout(ms / 2); h.setReadTimeout(ms);
                if (h.getResponseCode() == 200) {
                    android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(h.getInputStream());
                    h.disconnect();
                    if (b != null) return b;
                }
            } catch (Exception e) { Log.i(TAG, "Klingelbild " + basis + ": " + e); }
        }
        return null;
    }

    static Icon rundesBild() {
        if (bild == null) return Icon.createWithResource(App.ctx, R.drawable.ic_launcher);
        int g = Math.min(bild.getWidth(), bild.getHeight());
        android.graphics.Bitmap q = android.graphics.Bitmap.createBitmap(bild, (bild.getWidth() - g) / 2, (bild.getHeight() - g) / 2, g, g);
        return Icon.createWithAdaptiveBitmap(android.graphics.Bitmap.createScaledBitmap(q, 256, 256, true));
    }

    private static void jetztKlingeln(Context c, JSONObject neu) {
        long stummBis = prefs(c).getLong("stumm_bis", 0);
        if (System.currentTimeMillis() < stummBis) {   // "Nicht stören" in der App: nur still melden
            cfg = neu;
            kanaele(c);
            verlauf(c, "stumm");
            nm(c).notify(N_VERPASST, new Notification.Builder(c, K_STILL)
                    .setSmallIcon(R.drawable.ic_klingel)
                    .setContentTitle(name() + " (stumm)")
                    .setContentText("Geklingelt um " + new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date())
                            + " – Nicht stören bis " + new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(stummBis)))
                    .setLargeIcon(bild)
                    .setStyle(bild == null ? null : new Notification.BigPictureStyle().bigPicture(bild).bigLargeIcon((Icon) null))
                    .setContentIntent(PendingIntent.getActivity(c, 4, new Intent(c, Start.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true).build());
            melden("stumm");
            return;
        }
        cfg = neu;
        angebotWeg(c, "");
        verlauf(c, "klingelt");
        // Übungs-/Testanrufe ohne Home Assistant nicht als "Zuletzt" merken (sonst fehlen Schalter und Ansagen beim Probeanruf)
        if (!id().startsWith("probe") && !(cfg.optString("webhook").isEmpty() && cfg.optString("webhook_extern").isEmpty())) speichern(c);
        zustand = KLINGELT;
        beginn = System.currentTimeMillis();
        H.removeCallbacks(ZEIT_UM);
        H.postDelayed(ZEIT_UM, klingelDauer(c) * 1000L);
        kontoAnmelden(c);
        boolean ueberTelecom = false;
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            Bundle b = new Bundle();
            b.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tuer", name(), null));
            if (tm != null && tm.isIncomingCallPermitted(konto(c))) {
                tm.addNewIncomingCall(konto(c), b);
                ueberTelecom = true;
            } else Log.w(TAG, "Telecom erlaubt gerade keinen Anruf");
        } catch (Exception e) { Log.w(TAG, "Telecom: " + e); }
        if (!ueberTelecom) klingelnZeigen(c);    // ohne Telecom: nur Benachrichtigung
        if (haus()) {   // Hausruf: Anruffenster gleich zeigen (geht aus dem Hintergrund mit "Über anderen Apps anzeigen")
            try { c.startActivity(new Intent(c, Gespraech.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { Log.i(TAG, "Hausruf-Fenster: " + e); }
        }
        aktualisieren();
    }

    /** Telecom hat die Verbindung angelegt (oder abgelehnt) - jetzt die Klingel-Oberfläche zeigen. */
    static void klingelnZeigen(Context c) { klingelnZeigen(c, false); }

    /** Lautstärketaste beim Klingeln: Ton aus, Anruf bleibt stehen. */
    static void klingelnZeigenLeise(Context c) { if (zustand == KLINGELT) klingelnZeigen(c, true); }

    static void klingelnZeigen(Context c, boolean leise) { klingelnZeigen(c, leise, false); }

    /** nurBild: Benachrichtigung nur mit Bild auffrischen, Klingelton läuft weiter (kein neuer Alarm). */
    static void klingelnZeigen(Context c, boolean leise, boolean nurBild) {
        kanaele(c);
        Person p = new Person.Builder().setName(name()).setImportant(true).setIcon(rundesBild()).build();
        PendingIntent voll = PendingIntent.getActivity(c, 1, new Intent(c, Gespraech.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent ja = PendingIntent.getActivity(c, 2, new Intent(c, Gespraech.class).setAction(Gespraech.ANNEHMEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent nein = PendingIntent.getBroadcast(c, 3, new Intent(c, Knopf.class).setAction("ablehnen"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, K_KLINGELN)
                .setSmallIcon(R.drawable.ic_klingel)
                .setContentTitle(name())
                .setContentText(cfg.optString("hinweis", "Es klingelt"))
                .setLargeIcon(bild)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setFullScreenIntent(voll, true)
                .setContentIntent(voll)
                .setStyle(Notification.CallStyle.forIncomingCall(p, nein, ja))
                .setOnlyAlertOnce(leise || nurBild)
                .build();
        if (!leise) n.flags |= Notification.FLAG_INSISTENT;          // Klingelton wiederholen bis angenommen/abgelehnt
        nm(c).notify(N_ANRUF, n);
    }

    static void annehmen(Context c) {
        if (zustand != KLINGELT) return;
        verlaufAusgang(c, "angenommen");
        H.removeCallbacks(ZEIT_UM);
        zustand = AKTIV;
        beginn = System.currentTimeMillis();
        nm(c).cancel(N_ANRUF);
        if (verbindung != null) verbindung.aktiv();
        try { c.startForegroundService(new Intent(c, Laufend.class)); } catch (Exception e) { Log.w(TAG, "Laufend: " + e); }
        runde = new JSONObject();
        melden("angenommen", -1, false, null, t -> {
            // nur wenn HA ausdrücklich ablehnt (Mitschauen gesperrt / vorbei) - nicht bei "Anruf unbekannt" nach HA-Neustart
            if ((t.contains("gesperrt") || t.contains("vorbei")) && zustand == AKTIV) {
                Log.i(TAG, "HA lässt nicht ins Gespräch: " + t);
                verlaufAusgang(c, t.substring(1));
                ende(c, null, DisconnectCause.REJECTED);
            }
        });
        tonKoppeln(c, true);
        if (haus()) { HausAudio.stumm = false; HausAudio.start(cfg); }   // Hausruf: Mikro gleich offen
        aktualisieren();
    }

    // ---------- Lautstärke ----------
    // Der Kameraton kommt aus der WebView (WebRTC) und läuft über die Medien-Lautstärke, die Lautstärketasten stellen im
    // Gespräch aber die Anruf-Lautstärke. Darum folgt die Medien-Lautstärke während des Gesprächs der Anruf-Lautstärke
    // (gleicher Anteil) und wird danach auf den alten Wert zurückgestellt (Fully/Videos sonst plötzlich laut).
    private static int medienVorher = -1;
    private static android.content.BroadcastReceiver tonEmpfaenger;

    private static void tonAngleichen(android.media.AudioManager am) {
        int ruf = am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL);
        int rufMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL);
        int medMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int ziel = Math.max(1, Math.round((float) ruf * medMax / Math.max(1, rufMax)));
        if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) != ziel)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, ziel, 0);
    }

    static void tonKoppeln(Context c, boolean an) {
        Context a = c.getApplicationContext();
        android.media.AudioManager am = a.getSystemService(android.media.AudioManager.class);
        try {
            if (an && "medien".equals(prefs(a).getString("ton_kanal", "anruf"))) return;   // Wahlschalter: Medienkanal bleibt eigenständig
            if (an) {
                if (medienVorher < 0) medienVorher = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                tonAngleichen(am);
                if (tonEmpfaenger == null) {
                    tonEmpfaenger = new android.content.BroadcastReceiver() {
                        @Override public void onReceive(Context x, Intent i) {
                            if (zustand == AKTIV && i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                                    == android.media.AudioManager.STREAM_VOICE_CALL) tonAngleichen(am);
                        }
                    };
                    a.registerReceiver(tonEmpfaenger, new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                            Context.RECEIVER_NOT_EXPORTED);
                }
            } else {
                if (tonEmpfaenger != null) { try { a.unregisterReceiver(tonEmpfaenger); } catch (Exception ignored) {} tonEmpfaenger = null; }
                if (medienVorher >= 0) { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, medienVorher, 0); medienVorher = -1; }
            }
        } catch (Exception e) { Log.w(TAG, "Lautstärke: " + e); }
    }

    static void ablehnen(Context c) { if (zustand == KLINGELT) verlaufAusgang(c, "abgelehnt"); ende(c, "abgelehnt", DisconnectCause.REJECTED); }

    static void auflegen(Context c) { ende(c, "aufgelegt", DisconnectCause.LOCAL); }

    /** Leiter legt auf: alle = für alle beenden, sonst Leitung an nachfolger (HA entscheidet, Gesprächsrunde). */
    static void auflegen(Context c, boolean alle, String nachfolger) {
        if (zustand == AUS) return;
        JSONObject z = new JSONObject();
        try { z.put("alle", alle).put("nachfolger", nachfolger == null ? "" : nachfolger); } catch (Exception ignored) {}
        melden("aufgelegt", -1, false, z, null);
        ende(c, null, DisconnectCause.LOCAL);
    }

    /** Absichtlich beendet (nicht abgebrochen): Grund im Gesprächsfenster und als stille Meldung. */
    static void beendetMelden(Context c, String grund) {
        endeGrund = grund;
        kanaele(c);
        nm(c).notify(N_BEENDET, new Notification.Builder(c, K_MITSCHAUEN)
                .setSmallIcon(R.drawable.ic_klingel)
                .setContentTitle(name() + ": Gespräch beendet")
                .setContentText(grund)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .setContentIntent(PendingIntent.getActivity(c, 8, new Intent(c, Start.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
                .build());
    }

    // ---------- Mitschauen (Gesprächsrunde) ----------
    static void angebotZeigen(Context c, JSONObject a) {
        if (zustand != AUS) return;
        angebot = a;
        kanaele(c);
        PendingIntent los = PendingIntent.getActivity(c, 7, new Intent(c, Gespraech.class).setAction(Gespraech.MITSCHAUEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm(c).notify(N_ANGEBOT, new Notification.Builder(c, K_MITSCHAUEN)
                .setSmallIcon(R.drawable.ic_klingel)
                .setContentTitle(a.optString("name", "Tür") + ": Gespräch läuft")
                .setContentText(a.optString("hinweis", "Jemand spricht mit der Tür") + " – antippen zum Mitschauen")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setTimeoutAfter(30 * 60_000L)
                .setContentIntent(los)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_annehmen), "Mitschauen", los).build())
                .build());
    }

    static void angebotWeg(Context c, String anrufId) {
        if (angebot != null && (anrufId.isEmpty() || anrufId.equals(angebot.optString("id")))) {
            angebot = null;
            nm(c).cancel(N_ANGEBOT);
        }
    }

    /** Hausruf, den dieses Gerät selbst gestartet hat: ohne Klingeln gleich im Gespräch (leitet). */
    static void sofortAnnehmen(Context c, JSONObject neu) {
        if (zustand != AUS) return;
        cfg = neu;
        angebotWeg(c, "");
        bild = null;
        verlauf(c, "Hausruf");
        zustand = KLINGELT;
        beginn = System.currentTimeMillis();
        kontoAnmelden(c);
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            Bundle b = new Bundle();
            b.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tuer", name(), null));
            if (tm != null && tm.isIncomingCallPermitted(konto(c))) tm.addNewIncomingCall(konto(c), b);
        } catch (Exception e) { Log.w(TAG, "Telecom: " + e); }
        annehmen(c);
        try {   // Oberfläche: geht aus dem Hintergrund nur mit "Über anderen Apps anzeigen" - sonst über die Laufend-Meldung
            c.startActivity(new Intent(c, Gespraech.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { Log.i(TAG, "Hausruf-Fenster: " + e); }
    }

    static boolean haus() { return "haus".equals(cfg.optString("art")); }

    /** Webhook ohne laufenden Anruf (Hausruf starten, Ziele holen) mit den Daten des letzten Anrufs. */
    static void anHa(Context c, String aktion, JSONObject zusatz, java.util.function.Consumer<JSONObject> antwort) {
        JSONObject l = letzter(c);
        final JSONObject j = new JSONObject();
        try {
            j.put("tuerklingel", aktion).put("geraet", l.optString("geraet")).put("app", appVersion());
            if (zusatz != null) for (java.util.Iterator<String> it = zusatz.keys(); it.hasNext(); ) { String k = it.next(); j.put(k, zusatz.get(k)); }
        } catch (Exception ignored) {}
        new Thread(() -> {
            for (String url : new String[]{l.optString("webhook", ""), l.optString("webhook_extern", "")}) {
                if (url.isEmpty()) continue;
                try {
                    Netz.Antwort a = Netz.post(url, j.toString(), 2500, 8000);
                    if (a.code < 500) { final JSONObject r = new JSONObject(a.text); H.post(() -> antwort.accept(r)); return; }
                } catch (Exception e) { Log.i(TAG, aktion + ": " + e); }
            }
            H.post(() -> antwort.accept(null));
        }).start();
    }

    /** Mitschauen: als Gast ins laufende Gespräch (Telecom-Anruf ohne Klingeln, gleich aktiv). */
    static boolean beitreten(Context c) {
        if (angebot == null || zustand != AUS) return false;
        cfg = angebot;
        angebotWeg(c, "");
        bild = null;
        verlauf(c, "mitgeschaut");
        zustand = KLINGELT;
        beginn = System.currentTimeMillis();
        kontoAnmelden(c);
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            Bundle b = new Bundle();
            b.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tuer", name(), null));
            if (tm != null && tm.isIncomingCallPermitted(konto(c))) tm.addNewIncomingCall(konto(c), b);
        } catch (Exception e) { Log.w(TAG, "Telecom: " + e); }
        return true;
    }

    static void verpasst(Context c) {
        String n = name();
        verlaufAusgang(c, "verpasst");
        ende(c, "verpasst", DisconnectCause.MISSED);
        kanaele(c);
        PendingIntent auf = PendingIntent.getActivity(c, 4, new Intent(c, Start.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        nm(c).notify(N_VERPASST, new Notification.Builder(c, K_VERPASST)
                .setSmallIcon(R.drawable.ic_klingel)
                .setContentTitle("Verpasst: " + n)
                .setContentText("Geklingelt um " + new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date()))
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setLargeIcon(bild)
                .setStyle(bild == null ? null : new Notification.BigPictureStyle().bigPicture(bild).bigLargeIcon((Icon) null))
                .setContentIntent(auf).setAutoCancel(true).build());
    }

    /** Home Assistant meldet: woanders angenommen oder Klingeln vorbei. */
    static void vonAussenBeenden(Context c, JSONObject j) {
        String anrufId = j.optString("id", "");
        if (j.optBoolean("alle")) {   // HA: Gespräch für alle vorbei / Mitschauen gesperrt -> auch laufendes Gespräch beenden
            angebotWeg(c, anrufId);
            if (zustand != AUS && anrufId.equals(id())) {
                String grund = j.optString("grund", "");
                Log.i(TAG, "HA beendet Gespräch: " + grund);
                verlaufAusgang(c, grund.isEmpty() ? "beendet" : grund);
                if (zustand == AKTIV && !grund.isEmpty()) beendetMelden(c, grund);
                ende(c, null, DisconnectCause.REMOTE);
            }
            return;
        }
        vonAussenBeenden(c, anrufId);
        JSONObject a = j.optJSONObject("angebot");
        if (a != null) angebotZeigen(c, a);
    }

    static void vonAussenBeenden(Context c, String anrufId) {
        // Testanruf ohne Home Assistant (kein Webhook, z. B. per ADB): BEENDEN mit seiner Kennung legt auch im Gespräch auf
        boolean test = cfg.optString("webhook").isEmpty() && cfg.optString("webhook_extern").isEmpty();
        if (zustand == AKTIV && test && !anrufId.isEmpty() && anrufId.equals(id())) { ende(c, null, DisconnectCause.LOCAL); return; }
        if (zustand != KLINGELT) return;
        if (!anrufId.isEmpty() && !anrufId.equals(id())) return;
        verlaufAusgang(c, "woanders angenommen");
        ende(c, null, DisconnectCause.ANSWERED_ELSEWHERE);
    }

    private static void ende(Context c, String meldung, int grund) {
        if (zustand == AUS) return;
        H.removeCallbacks(ZEIT_UM);
        boolean warAktiv = zustand == AKTIV;
        zustand = AUS;
        HausAudio.stop();
        if (warAktiv) tonKoppeln(c, false);
        nm(c).cancel(N_ANRUF);
        if (verbindung != null) { verbindung.trennen(grund); verbindung = null; }
        try { c.stopService(new Intent(c, Laufend.class)); } catch (Exception ignored) {}
        if (meldung != null) melden(meldung);
        aktualisieren();
    }


    static void aktualisieren() { if (beiAenderung != null) H.post(beiAenderung); }

    // ---------- Rückmeldung an Home Assistant ----------
    /** Erst im Heimnetz (webhook), sonst über die öffentliche HA-Adresse (webhook_extern). */
    static void melden(String aktion) { melden(aktion, -1, null); }

    /** antwort: bekommt den Text aus der HA-Antwort ("Tür geöffnet", "nicht freigegeben" ...) - oder "!Keine Verbindung". */
    static void melden(String aktion, int nr, java.util.function.Consumer<String> antwort) { melden(aktion, nr, false, antwort); }

    /** oeffnen: bei "antwort" - nach der Ansage die Tür öffnen. */
    static void melden(String aktion, int nr, boolean oeffnen, java.util.function.Consumer<String> antwort) {
        melden(aktion, nr, oeffnen, null, antwort);
    }

    /** zusatz: weitere Felder für HA (z. B. alle/nachfolger beim Auflegen). */
    static void melden(String aktion, int nr, boolean oeffnen, JSONObject zusatz, java.util.function.Consumer<String> antwort) {
        final String innen = cfg.optString("webhook", ""), aussen = cfg.optString("webhook_extern", "");
        if (innen.isEmpty() && aussen.isEmpty()) return;
        final JSONObject j = new JSONObject();
        try {
            j.put("tuerklingel", aktion).put("id", id()).put("geraet", cfg.optString("geraet", Build.MODEL))
                    .put("name", name()).put("schluessel", cfg.optString("schluessel", "")).put("app", appVersion());
            if (nr >= 0) j.put("nr", nr);
            if (oeffnen) j.put("oeffnen", true);
            if (zusatz != null) for (java.util.Iterator<String> it = zusatz.keys(); it.hasNext(); ) { String k = it.next(); j.put(k, zusatz.get(k)); }
        } catch (Exception ignored) {}
        new Thread(() -> {
            for (String url : new String[]{innen, aussen}) {
                if (url.isEmpty()) continue;
                try {
                    Netz.Antwort a = Netz.post(url, j.toString(), 2500, 6000);
                    Log.i(TAG, "Meldung " + aktion + " (" + (url == innen ? "innen" : "außen") + "): HTTP " + a.code + " " + a.text);
                    if (a.code < 500) {
                        if (antwort != null) {
                            String t;
                            try { JSONObject r = new JSONObject(a.text); t = (r.optBoolean("ok") ? "" : "!") + r.optString("text"); }
                            catch (Exception e) { t = a.code < 300 ? "" : "!HTTP " + a.code; }
                            final String tt = t;
                            H.post(() -> antwort.accept(tt));
                        }
                        return;
                    }
                } catch (Exception e) { Log.w(TAG, "Meldung " + aktion + " " + (url == innen ? "innen" : "außen") + ": " + e); }
            }
            if (antwort != null) H.post(() -> antwort.accept("!Keine Verbindung"));
        }).start();
    }

    /** Status/Schalten: ganze JSON-Antwort von HA (schalter, anzeigen, text); null bei keiner Verbindung. */
    static void abfragen(String aktion, int nr, java.util.function.Consumer<JSONObject> antwort) { abfragen(aktion, nr, null, antwort); }

    static void abfragen(String aktion, int nr, JSONObject zusatz, java.util.function.Consumer<JSONObject> antwort) {
        final String innen = cfg.optString("webhook", ""), aussen = cfg.optString("webhook_extern", "");
        final JSONObject j = new JSONObject();
        try {
            j.put("tuerklingel", aktion).put("id", id()).put("geraet", cfg.optString("geraet", Build.MODEL))
                    .put("schluessel", cfg.optString("schluessel", "")).put("app", appVersion());
            if (nr >= 0) j.put("nr", nr);
            if (zusatz != null) for (java.util.Iterator<String> it = zusatz.keys(); it.hasNext(); ) { String k = it.next(); j.put(k, zusatz.get(k)); }
        } catch (Exception ignored) {}
        new Thread(() -> {
            for (String url : new String[]{innen, aussen}) {
                if (url.isEmpty()) continue;
                try {
                    Netz.Antwort a = Netz.post(url, j.toString(), 2000, 5000);
                    if (a.code < 500) { final JSONObject r = new JSONObject(a.text); H.post(() -> antwort.accept(r)); return; }
                } catch (Exception e) { Log.i(TAG, aktion + ": " + e); }
            }
            H.post(() -> antwort.accept(null));
        }).start();
    }

    // ---------- Verlauf "Wer war da" (letzte 20, Bild als Datei) ----------
    static void verlauf(Context c, String ausgang) {
        try {
            long t = System.currentTimeMillis();
            String datei = bild != null ? bildDatei(c, t, bild) : "";
            org.json.JSONArray alt = verlaufListe(c), neu = new org.json.JSONArray();
            neu.put(new JSONObject().put("t", t).put("name", name()).put("id", id()).put("ausgang", ausgang).put("bild", datei));
            for (int i = 0; i < alt.length() && neu.length() < 20; i++) neu.put(alt.get(i));
            for (int i = 20; i < alt.length(); i++) {   // alte Bilder löschen
                String b = alt.optJSONObject(i) == null ? "" : alt.optJSONObject(i).optString("bild");
                if (!b.isEmpty()) new java.io.File(new java.io.File(c.getFilesDir(), "verlauf"), b).delete();
            }
            prefs(c).edit().putString("verlauf", neu.toString()).apply();
        } catch (Exception e) { Log.w(TAG, "Verlauf: " + e); }
    }

    private static String bildDatei(Context c, long t, android.graphics.Bitmap b) throws java.io.IOException {
        java.io.File d = new java.io.File(c.getFilesDir(), "verlauf");
        d.mkdirs();
        java.io.File f = new java.io.File(d, t + ".jpg");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
            android.graphics.Bitmap.createScaledBitmap(b, 320, Math.max(1, b.getHeight() * 320 / Math.max(1, b.getWidth())), true)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, o);
        }
        return f.getName();
    }

    /** Bild nachträglich an den Verlaufs-Eintrag dieses Anrufs hängen (falls er noch keins hat). */
    static void verlaufBild(Context c, String anrufId, android.graphics.Bitmap b) {
        try {
            org.json.JSONArray l = verlaufListe(c);
            for (int i = 0; i < l.length(); i++) {
                JSONObject e = l.getJSONObject(i);
                if (e.optString("id").equals(anrufId)) {
                    if (e.optString("bild").isEmpty()) e.put("bild", bildDatei(c, e.optLong("t"), b));
                    break;
                }
            }
            prefs(c).edit().putString("verlauf", l.toString()).apply();
        } catch (Exception e) { Log.w(TAG, "Verlauf-Bild: " + e); }
    }

    static void verlaufAusgang(Context c, String ausgang) {
        try {
            org.json.JSONArray l = verlaufListe(c);
            for (int i = 0; i < l.length(); i++) {
                JSONObject e = l.getJSONObject(i);
                if (e.optString("id").equals(id())) { e.put("ausgang", ausgang); break; }
            }
            prefs(c).edit().putString("verlauf", l.toString()).apply();
        } catch (Exception e) { Log.w(TAG, "Verlauf: " + e); }
    }

    static org.json.JSONArray verlaufListe(Context c) {
        try { return new org.json.JSONArray(prefs(c).getString("verlauf", "[]")); } catch (Exception e) { return new org.json.JSONArray(); }
    }

    // ---------- Hilfen ----------
    static NotificationManager nm(Context c) { return c.getSystemService(NotificationManager.class); }

    static void kanaele(Context c) {
        NotificationManager m = nm(c);
        if (m.getNotificationChannel(K_MITSCHAUEN) == null) {
            NotificationChannel k = new NotificationChannel(K_MITSCHAUEN, "Mitschauen", NotificationManager.IMPORTANCE_DEFAULT);
            k.setDescription("Gespräch an der Tür läuft auf einem anderen Gerät – antippen zum Mitschauen");
            k.setSound(null, null);
            k.enableVibration(false);
            m.createNotificationChannel(k);
        }
        if (m.getNotificationChannel(K_KLINGELN) == null) {
            NotificationChannel k = new NotificationChannel(K_KLINGELN, "Klingeln", NotificationManager.IMPORTANCE_HIGH);
            k.setDescription("Es klingelt an der Tür (wie ein Anruf)");
            k.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            k.enableVibration(true);
            k.setVibrationPattern(new long[]{0, 1000, 1000});
            k.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            k.setBypassDnd(true);
            m.createNotificationChannel(k);
        }
        if (m.getNotificationChannel(K_LAUFEND) == null) {   // v2: auch auf dem Sperrbildschirm sichtbar, ohne Ton
            NotificationChannel l = new NotificationChannel(K_LAUFEND, "Laufendes Gespräch", NotificationManager.IMPORTANCE_DEFAULT);
            l.setSound(null, null);
            l.enableVibration(false);
            l.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            m.createNotificationChannel(l);
        }
        if (m.getNotificationChannel("laufend_v1") != null) m.deleteNotificationChannel("laufend_v1");
        if (m.getNotificationChannel(K_STILL) == null)
            m.createNotificationChannel(new NotificationChannel(K_STILL, "Klingeln bei Nicht stören", NotificationManager.IMPORTANCE_LOW));
        if (m.getNotificationChannel(K_VERPASST) == null)
            m.createNotificationChannel(new NotificationChannel(K_VERPASST, "Verpasstes Klingeln", NotificationManager.IMPORTANCE_DEFAULT));
    }

    /** Wie lange es klingelt: Einstellung in der App (Sekunden), sonst der Wert aus Home Assistant. */
    static int klingelDauer(Context c) {
        int eigen = prefs(c).getInt("klingel_s", 0);
        return Math.max(10, eigen > 0 ? eigen : cfg.optInt("dauer", 30));
    }

    static void speichern(Context c) {
        prefs(c).edit().putString("letzter", cfg.toString()).putLong("zeit", System.currentTimeMillis()).apply();
    }

    /** DoorCall richtet neue Geräte still ein (2026-11-21): Adressen merken (nur wenn noch keine oder andere da sind -
     *  ein echter letzter Anruf mit Schaltern bleibt) und mit "hallo" antworten. Danach gehen Probeanruf und Hausruf. */
    static void einrichten(Context c, JSONObject j) {
        JSONObject l = letzter(c);
        if (l.length() == 0 || !l.optString("webhook").equals(j.optString("webhook")) || !l.optString("geraet").equals(j.optString("geraet")))
            prefs(c).edit().putString("letzter", j.toString()).putLong("zeit", System.currentTimeMillis()).apply();
        anHa(c, "hallo", null, r -> Log.i(TAG, "Einrichtung: " + (r == null ? "HA nicht erreichbar" : r.toString())));
    }

    static JSONObject letzter(Context c) {
        try {
            JSONObject j = new JSONObject(prefs(c).getString("letzter", "{}"));
            if (j.has("name")) j.put("name", j.optString("name").replace(" (Probe)", ""));   // Altlast aus 2.2
            return j;
        } catch (Exception e) { return new JSONObject(); }
    }

    /** Probeanruf-Knopf: HA schickt einen echten Anruf nur an dieses Gerät. fertig: null = HA nicht erreichbar, sonst HA-Text ("" = ok). */
    static void probeAnfordern(JSONObject l, java.util.function.Consumer<String> fertig) {
        final JSONObject j = new JSONObject();
        try {
            String id = l.optString("id");
            j.put("tuerklingel", "probeanruf").put("id", id).put("geraet", l.optString("geraet"))
                    .put("klingel", l.optString("klingel", id.contains("_") ? id.substring(0, id.lastIndexOf('_')) : id));
        } catch (Exception ignored) {}
        new Thread(() -> {
            for (String url : new String[]{l.optString("webhook", ""), l.optString("webhook_extern", "")}) {
                if (url.isEmpty()) continue;
                try {
                    Netz.Antwort a = Netz.post(url, j.toString(), 2500, 6000);
                    Log.i(TAG, "Probeanruf anfordern: HTTP " + a.code + " " + a.text);
                    if (a.code < 500) {
                        JSONObject r = new JSONObject(a.text);
                        String t = r.optBoolean("ok") ? "" : r.optString("text", "abgelehnt");
                        H.post(() -> fertig.accept(t)); return;
                    }
                } catch (Exception e) { Log.i(TAG, "Probeanruf anfordern: " + e); }
            }
            H.post(() -> fertig.accept(null));
        }).start();
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("klingel", Context.MODE_PRIVATE); }
}
