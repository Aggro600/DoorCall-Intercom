package de.esphomeremote.klingel;

import android.content.Intent;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

/** Telecom-Anbindung: Android führt den Tür-Anruf wie einen normalen Anruf (Headset, Auto, Uhr, Bitte nicht stören). */
public class TuerDienst extends ConnectionService {

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle konto, ConnectionRequest req) {
        App.ctx = getApplicationContext();
        Verbindung v = new Verbindung();
        v.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        v.setConnectionCapabilities(Connection.CAPABILITY_MUTE | Connection.CAPABILITY_SUPPORT_HOLD);
        v.setAudioModeIsVoip(true);
        v.setAddress(req.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
        v.setCallerDisplayName(Anruf.name(), TelecomManager.PRESENTATION_ALLOWED);
        Anruf.verbindung = v;
        if (Anruf.zustand == Anruf.AKTIV) v.aktiv();   // Mitschauen: schon angenommen, nicht klingeln
        else v.setRinging();
        return v;
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle konto, ConnectionRequest req) {
        Log.w(Anruf.TAG, "Telecom hat den Anruf abgelehnt - klingle nur per Benachrichtigung");
        Anruf.verbindung = null;
        if (Anruf.zustand == Anruf.KLINGELT) Anruf.klingelnZeigen(getApplicationContext());
    }

    /** Eine Tür-Verbindung. Telecom ruft die on*-Methoden auf, z. B. Annehmen am Bluetooth-Headset. */
    static final class Verbindung extends Connection {
        @Override public void onShowIncomingCallUi() { if (Anruf.zustand == Anruf.KLINGELT) Anruf.klingelnZeigen(App.ctx); }

        @Override public void onAnswer() { annehmenVonAussen(); }
        @Override public void onAnswer(int videoState) { annehmenVonAussen(); }

        private void annehmenVonAussen() {
            if (Anruf.zustand != Anruf.KLINGELT) return;
            // Gespraech übernimmt Annehmen + WebRTC mit Mikrofon
            App.ctx.startActivity(new Intent(App.ctx, Gespraech.class).setAction(Gespraech.ANNEHMEN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }

        @Override public void onReject() { Anruf.ablehnen(App.ctx); }
        @Override public void onDisconnect() { Anruf.auflegen(App.ctx); }
        @Override public void onAbort() { Anruf.auflegen(App.ctx); }
        @Override public void onSilence() { Anruf.klingelnZeigenLeise(App.ctx); }

        @Override public void onCallAudioStateChanged(CallAudioState s) {
            Log.i(Anruf.TAG, "Audio-Weg: " + CallAudioState.audioRouteToString(s.getRoute()) + (s.isMuted() ? " stumm" : ""));
            Gespraech.audioGeaendert(s);
        }

        void aktiv() {
            setActive();
            lautsprecher(true);
        }

        @SuppressWarnings("deprecation")
        void lautsprecher(boolean an) {
            CallAudioState s = getCallAudioState();
            if (s != null && (s.getSupportedRouteMask() & CallAudioState.ROUTE_BLUETOOTH) != 0
                    && s.getRoute() == CallAudioState.ROUTE_BLUETOOTH && an) return;   // Headset behalten
            setAudioRoute(an ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_WIRED_OR_EARPIECE);
        }

        void trennen(int grund) {
            setDisconnected(new DisconnectCause(grund));
            destroy();
        }
    }
}
