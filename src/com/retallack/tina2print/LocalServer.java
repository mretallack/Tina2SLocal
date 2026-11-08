package com.retallack.tina2print;

import android.content.res.AssetManager;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal loopback HTTP server used only to host the offline Kiri:Moto slicer page
 * for the WebView. Serving over http (instead of file://) lets us send the
 * COOP/COEP headers the slicer needs for SharedArrayBuffer, and lets the page
 * fetch large STL files without going through the JS bridge.
 */
public class LocalServer implements Runnable {

    public interface GcodeSink {
        /** Called (on a server thread) with freshly-sliced G-code. */
        void onGcode(String gcode);
    }

    private final AssetManager assets;
    private final GcodeSink sink;
    private final File stlFile;
    private ServerSocket server;
    private volatile boolean running = true;
    private int port = -1;

    public LocalServer(AssetManager assets, File stlFile, GcodeSink sink) {
        this.assets = assets;
        this.stlFile = stlFile;
        this.sink = sink;
    }

    public int start() throws IOException {
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        Thread t = new Thread(this, "local-server");
        t.setDaemon(true);
        t.start();
        return port;
    }

    public int getPort() { return port; }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
    }

    @Override
    public void run() {
        while (running) {
            Socket s = null;
            try {
                s = server.accept();
                handle(s);
            } catch (Exception e) {
                if (running) e.printStackTrace();
            } finally {
                try { if (s != null) s.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream raw = s.getOutputStream();
        BufferedOutputStream out = new BufferedOutputStream(raw);

        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return;
        String[] parts = requestLine.split(" ");
        String method = parts[0];
        String path = parts.length > 1 ? parts[1] : "/";
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
        }

        if ("/gcode".equals(path) && "POST".equals(method)) {
            int len = parseInt(headers.get("content-length"), 0);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int remaining = len;
            while (remaining > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) break;
                body.write(buf, 0, n);
                remaining -= n;
            }
            String gcode = body.toString("UTF-8");
            if (sink != null) sink.onGcode(gcode);
            respond(out, 200, "text/plain", "ok".getBytes("UTF-8"));
            return;
        }

        if ("/stl".equals(path)) {
            if (!stlFile.exists() || stlFile.length() == 0) {
                respond(out, 404, "text/plain", "no stl".getBytes("UTF-8"));
                return;
            }
            byte[] data = readFile(stlFile);
            if ("HEAD".equals(method)) respond(out, 200, "application/octet-stream", null);
            else respond(out, 200, "application/octet-stream", data);
            return;
        }

        // Everything else maps to an asset under /assets.
        String assetPath = "/".equals(path) ? "slice.html" : path.substring(1);
        try {
            InputStream a = assets.open(assetPath);
            byte[] data = readAll(a);
            String ct = contentType(assetPath);
            if ("HEAD".equals(method)) respond(out, 200, ct, null);
            else respond(out, 200, ct, data);
        } catch (IOException e) {
            respond(out, 404, "text/plain", ("not found: " + assetPath).getBytes("UTF-8"));
        }
    }

    private void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " ERR").append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        // Required so the WebView becomes cross-origin isolated (enables SharedArrayBuffer).
        h.append("Cross-Origin-Opener-Policy: same-origin\r\n");
        h.append("Cross-Origin-Embedder-Policy: require-corp\r\n");
        h.append("Cross-Origin-Resource-Policy: same-origin\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        if (body != null) out.write(body);
        out.flush();
    }

    private static String contentType(String p) {
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".stl")) return "application/octet-stream";
        return "application/octet-stream";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static byte[] readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try { return readAll(in); } finally { in.close(); }
    }
}
