package de.esphomeremote.klingel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Kleiner HTTP-POST mit JSON (Webhook, WebRTC-Signal). */
final class Netz {
    static final class Antwort { int code; String text; }

    static Antwort post(String url, String json, int verbinden, int lesen) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(verbinden); h.setReadTimeout(lesen);
        h.setRequestMethod("POST"); h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = h.getOutputStream()) { o.write(json.getBytes("UTF-8")); }
        Antwort a = new Antwort();
        a.code = h.getResponseCode();
        InputStream in = a.code < 400 ? h.getInputStream() : h.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
        a.text = b.toString("UTF-8");
        h.disconnect();
        return a;
    }

    private Netz() {}
}
