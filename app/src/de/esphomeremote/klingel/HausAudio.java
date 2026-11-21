package de.esphomeremote.klingel;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Hausruf (2026-11-21): Gegensprechen zwischen den Geräten ohne Klingel. Kein WebView/WebRTC: Mikrofon über
 * AudioRecord (VOICE_COMMUNICATION, mit Echounterdrückung des Systems) als endloser POST an Home Assistant
 * (/api/doorcall/haus_mik), Wiedergabe über AudioTrack (Sprach-Anruf) aus /api/doorcall/haus_hoeren
 * (HA mischt alle anderen). 16 kHz, mono, 16 bit little endian. Läuft ohne Oberfläche (Anruf startet/stoppt).
 */
final class HausAudio {
    private static final int RATE = 16000, FRAME = 640;   // 20 ms
    private static volatile boolean laeuft;
    static volatile boolean stumm;
    private static Thread auf, ab;

    private HausAudio() {}

    private static String query(JSONObject cfg) throws Exception {
        return "?id=" + URLEncoder.encode(cfg.optString("id"), "UTF-8") + "&geraet=" + URLEncoder.encode(cfg.optString("geraet"), "UTF-8")
                + "&schluessel=" + URLEncoder.encode(cfg.optString("schluessel"), "UTF-8");
    }

    static synchronized void start(JSONObject cfg) {
        if (laeuft) return;
        laeuft = true;
        auf = new Thread(() -> senden(cfg), "hausruf-mik");
        ab = new Thread(() -> hoeren(cfg), "hausruf-hoeren");
        auf.start();
        ab.start();
        Log.i(Anruf.TAG, "Hausruf-Audio gestartet");
    }

    static synchronized void stop() {
        laeuft = false;
    }

    @SuppressWarnings("MissingPermission")
    private static void senden(JSONObject cfg) {
        AudioRecord rec = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 4));
            if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(rec.getAudioSessionId()); if (aec != null) aec.setEnabled(true); }
            if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(rec.getAudioSessionId()); if (ns != null) ns.setEnabled(true); }
            rec.startRecording();
            byte[] buf = new byte[FRAME], leer = new byte[FRAME];
            while (laeuft) {
                for (String basis : new String[]{cfg.optString("ha"), cfg.optString("extern")}) {
                    if (basis.isEmpty() || !laeuft) continue;
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(basis + Anruf.pfad(cfg, "haus_mik") + query(cfg)).openConnection();
                        c.setRequestMethod("POST");
                        c.setDoOutput(true);
                        c.setChunkedStreamingMode(FRAME);
                        c.setConnectTimeout(3000);
                        c.setRequestProperty("Content-Type", "application/octet-stream");
                        OutputStream o = c.getOutputStream();
                        Log.i(Anruf.TAG, "Hausruf-Mikro verbunden (" + basis + ")");
                        while (laeuft) {
                            int n = rec.read(buf, 0, FRAME);
                            if (n <= 0) continue;
                            o.write(stumm ? leer : buf, 0, n);   // stumm: Stille, damit die Verbindung offen bleibt
                            o.flush();
                        }
                        o.close();
                        c.getResponseCode();
                    } catch (Exception e) {
                        Log.w(Anruf.TAG, "Hausruf-Mikro " + basis + ": " + e);
                    } finally { if (c != null) c.disconnect(); }
                }
                if (laeuft) try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        } catch (Exception e) {
            Log.w(Anruf.TAG, "Hausruf-Mikro: " + e);
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); }
            if (aec != null) aec.release();
            if (ns != null) ns.release();
        }
    }

    private static void hoeren(JSONObject cfg) {
        AudioTrack spur = null;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            spur = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, FRAME * 6))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            spur.play();
            byte[] buf = new byte[FRAME];
            while (laeuft) {
                for (String basis : new String[]{cfg.optString("ha"), cfg.optString("extern")}) {
                    if (basis.isEmpty() || !laeuft) continue;
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(basis + Anruf.pfad(cfg, "haus_hoeren") + query(cfg)).openConnection();
                        c.setConnectTimeout(3000);
                        c.setReadTimeout(5000);
                        InputStream in = c.getInputStream();
                        Log.i(Anruf.TAG, "Hausruf hört zu (" + basis + ")");
                        int n;
                        while (laeuft && (n = in.read(buf)) > 0) spur.write(buf, 0, n);
                    } catch (Exception e) {
                        Log.w(Anruf.TAG, "Hausruf hören " + basis + ": " + e);
                    } finally { if (c != null) c.disconnect(); }
                }
                if (laeuft) try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        } catch (Exception e) {
            Log.w(Anruf.TAG, "Hausruf hören: " + e);
        } finally {
            if (spur != null) { try { spur.stop(); } catch (Exception ignored) {} spur.release(); }
        }
    }
}
