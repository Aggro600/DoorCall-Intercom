package de.esphomeremote.klingel;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.util.Log;

/** Vordergrund-Dienst während des Gesprächs: hält Mikrofon und Verbindung, zeigt "Gespräch läuft" mit Auflegen. */
public class Laufend extends Service {
    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        Anruf.kanaele(this);
        Person p = new Person.Builder().setName(Anruf.name()).setImportant(true)
                .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher)).build();
        PendingIntent zu = PendingIntent.getBroadcast(this, 5, new Intent(this, Knopf.class).setAction("auflegen"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent auf = PendingIntent.getActivity(this, 6, new Intent(this, Gespraech.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, Anruf.K_LAUFEND)
                .setSmallIcon(R.drawable.ic_klingel)
                .setContentTitle(Anruf.name())
                .setContentText("Gespräch läuft")
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(Anruf.beginn)
                .setContentIntent(auf)
                .setStyle(Notification.CallStyle.forOngoingCall(p, zu))
                .build();
        try {
            startForeground(Anruf.N_ANRUF + 10, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Exception e) {
            Log.w(Anruf.TAG, "Vordergrund mit Mikrofon nicht erlaubt, nur Anruf: " + e);
            try { startForeground(Anruf.N_ANRUF + 10, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL); }
            catch (Exception e2) { Log.w(Anruf.TAG, "Vordergrund: " + e2); }
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
