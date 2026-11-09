package com.retallack.tina2print;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tina2 Print - minimal client for the WEEDO Tina2S local Wi-Fi (OctoPrint-compatible) API.
 * Protocol recovered from the Poloprint Local APK; see notes/LAN-PROTOCOL.md.
 *
 * Two tabs:
 *   Print  - upload a sliced .gcode file and optionally start printing.
 *   Slice  - offline Kiri:Moto slicer running in a WebView (assets bundled in the APK),
 *            whose G-code is handed straight to the printer.
 */
public class MainActivity extends Activity {

    private static final int PICK_FILE = 1001;   // Print tab: .gcode
    private static final int PICK_STL  = 1002;   // Slice tab: .stl

    // Print tab
    private EditText ipField;
    private TextView fileLabel;
    private TextView log;
    private CheckBox printAfter;
    private Button uploadBtn;
    private Uri fileUri;
    private String fileName;

    // Slice tab
    private WebView webView;
    private LocalServer server;
    private File stlFile;
    private File slicedGcodeFile;
    private TextView sliceLabel;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final String PREFS = "tina2print";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        stlFile = new File(getFilesDir(), "model.stl");
        slicedGcodeFile = new File(getFilesDir(), "sliced.gcode");

        FrameLayout content = new FrameLayout(this);
        View printView = buildPrintView();
        View sliceView = buildSliceView();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button printTab = new Button(this);
        printTab.setText("Print");
        Button sliceTab = new Button(this);
        sliceTab.setText("Slice");
        tabs.addView(printTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(sliceTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabs);

        sliceView.setVisibility(View.GONE);
        content.addView(printView);
        content.addView(sliceView);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        printTab.setOnClickListener(v -> showTab(true, printView, sliceView, printTab, sliceTab));
        sliceTab.setOnClickListener(v -> showTab(false, printView, sliceView, printTab, sliceTab));
        showTab(true, printView, sliceView, printTab, sliceTab);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ipField.setText(prefs.getString("ip", "10.0.0.96"));

        log("Tina2 Print ready. Set the printer IP and upload a sliced .gcode.");
    }

    // ---------- Print tab ----------

    private View buildPrintView() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(12), pad, pad);

        root.addView(section("Printer"));

        ipField = new EditText(this);
        ipField.setHint("10.0.0.96");
        ipField.setSingleLine(true);
        root.addView(ipField);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button scanBtn = new Button(this);
        scanBtn.setText("Find printer");
        scanBtn.setOnClickListener(v -> scan());
        Button statusBtn = new Button(this);
        statusBtn.setText("Status");
        statusBtn.setOnClickListener(v -> status());
        row1.addView(scanBtn, weight());
        row1.addView(spacer());
        row1.addView(statusBtn, weight());
        root.addView(row1);

        root.addView(section("G-code file"));

        Button pickBtn = new Button(this);
        pickBtn.setText("Choose .gcode file");
        pickBtn.setOnClickListener(v -> pickFile());
        root.addView(pickBtn);

        fileLabel = new TextView(this);
        fileLabel.setText("No file selected");
        fileLabel.setTextSize(13);
        fileLabel.setPadding(dp(2), dp(6), 0, 0);
        root.addView(fileLabel);

        printAfter = new CheckBox(this);
        printAfter.setText("Start printing after upload");
        printAfter.setChecked(false);
        root.addView(printAfter);

        uploadBtn = new Button(this);
        uploadBtn.setText("Upload to printer");
        uploadBtn.setOnClickListener(v -> upload());
        root.addView(uploadBtn);

        root.addView(section("Print control"));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button startBtn = new Button(this);
        startBtn.setText("Start");
        startBtn.setOnClickListener(v -> job("start", null));
        Button pauseBtn = new Button(this);
        pauseBtn.setText("Pause");
        pauseBtn.setOnClickListener(v -> job("pause", "pause"));
        Button cancelBtn = new Button(this);
        cancelBtn.setText("Cancel");
        cancelBtn.setOnClickListener(v -> job("cancel", null));
        row2.addView(startBtn, weight());
        row2.addView(spacer());
        row2.addView(pauseBtn, weight());
        row2.addView(spacer());
        row2.addView(cancelBtn, weight());
        root.addView(row2);

        root.addView(section("Log"));

        log = new TextView(this);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextIsSelectable(true);
        log.setPadding(dp(10), dp(10), dp(10), dp(10));
        log.setBackgroundColor(0xFF1B1E22);
        log.setTextColor(0xFFC8CDD4);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF1B1E22);
        scroll.addView(log, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    // ---------- Slice tab ----------

    private View buildSliceView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF101214);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new SliceBridge(), "T2");
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        try {
            server = new LocalServer(getAssets(), stlFile, gcode -> onSlicedGcode(gcode));
            int port = server.start();
            webView.loadUrl("http://127.0.0.1:" + port + "/slice.html");
        } catch (Exception e) {
            sliceLabel = new TextView(this);
            sliceLabel.setText("Slicer failed to start: " + e);
            root.addView(sliceLabel);
        }

        return root;
    }

    /** JS -> native bridge for the slicer page. */
    private class SliceBridge {
        @JavascriptInterface
        public void chooseStl() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, PICK_STL);
            });
        }
    }

    /** Called on a server thread when the page POSTs finished G-code. */
    private void onSlicedGcode(String gcode) {
        try {
            FileOutputStream fos = new FileOutputStream(slicedGcodeFile);
            fos.write(gcode.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            log("Failed to save sliced gcode: " + e);
            return;
        }
        ui.post(() -> {
            log("Slicer produced " + gcode.length() + " bytes; sending to printer…");
            uploadFile(slicedGcodeFile, "slice.gcode", true);
        });
    }

    // ---------- UI helpers ----------

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /** Small uppercase section heading, matching the web slicer's styling. */
    private TextView section(String t) {
        TextView tv = new TextView(this);
        tv.setText(t.toUpperCase());
        tv.setTextSize(12);
        tv.setLetterSpacing(0.06f);
        tv.setTextColor(0xFF9AA2AD);
        tv.setPadding(dp(2), dp(18), 0, dp(4));
        return tv;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        return v;
    }

    private void showTab(boolean print, View printView, View sliceView, Button printTab, Button sliceTab) {
        printView.setVisibility(print ? View.VISIBLE : View.GONE);
        sliceView.setVisibility(print ? View.GONE : View.VISIBLE);
        printTab.setAlpha(print ? 1f : 0.5f);
        sliceTab.setAlpha(print ? 0.5f : 1f);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void log(final String msg) {
        ui.post(() -> { if (log != null) log.append(msg + "\n"); });
    }

    private void toast(final String msg) {
        ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private String base() {
        String ip = ipField.getText().toString().trim();
        if (ip.startsWith("http")) return ip.endsWith("/") ? ip.substring(0, ip.length() - 1) : ip;
        return "http://" + ip;
    }

    // ---------- File picker ----------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        String name = queryName(uri);

        if (req == PICK_FILE) {
            fileUri = uri;
            fileName = name;
            fileLabel.setText("Selected: " + fileName);
            log("Selected file: " + fileName);
        } else if (req == PICK_STL) {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                FileOutputStream out = new FileOutputStream(stlFile);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
                log("Loaded STL: " + name + " (" + stlFile.length() + " bytes)");
                if (webView != null) {
                    webView.evaluateJavascript("window.onStlReady(" + JSONObject.quote(name) + ")", null);
                }
            } catch (Exception e) {
                toast("Could not read STL: " + e);
            }
        }
    }

    private String queryName(Uri uri) {
        String name = "upload.gcode";
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return name;
    }

    // ---------- Networking ----------

    private void status() {
        final String b = base();
        exec.execute(() -> {
            try {
                log("GET " + b + "/api/version");
                JSONObject v = new JSONObject(get(b + "/api/version"));
                log("  device: " + v.optString("text") + "  fw " + v.optString("device_version")
                        + "  module " + v.optString("server_hardware"));

                log("GET " + b + "/api/printer");
                JSONObject p = new JSONObject(get(b + "/api/printer"));
                JSONObject st = p.optJSONObject("state");
                String text = st != null ? st.optString("text") : "?";
                JSONObject temp = p.optJSONObject("temperature");
                String t = "";
                if (temp != null) {
                    JSONObject tool = temp.optJSONObject("tool0");
                    JSONObject bed = temp.optJSONObject("bed");
                    if (tool != null) t += "  nozzle " + tool.optInt("actual") + "/" + tool.optInt("target") + "\u00B0C";
                    if (bed != null) t += "  bed " + bed.optInt("actual") + "/" + bed.optInt("target") + "\u00B0C";
                }
                log("  state: " + text + t);

                JSONObject j = new JSONObject(get(b + "/api/job"));
                JSONObject job = j.optJSONObject("job");
                JSONObject prog = j.optJSONObject("progress");
                if (job != null && prog != null) {
                    JSONObject f = job.optJSONObject("file");
                    String fn = f != null ? f.optString("name") : "?";
                    log("  job: " + fn + "  " + prog.optDouble("completion") + "%"
                            + "  left " + prog.optInt("printTimeLeft") + "s");
                }
            } catch (Exception e) {
                log("ERROR: " + e);
            }
        });
    }

    private void scan() {
        final String self = localIpv4Prefix();
        if (self == null) {
            toast("Not on Wi-Fi?");
            return;
        }
        log("Scanning " + self + ".0/24 for the printer...");
        exec.execute(() -> {
            String found = null;
            for (int i = 1; i <= 254; i++) {
                final String ip = self + "." + i;
                try {
                    String r = get("http://" + ip + "/api/version");
                    if (r != null && r.contains("\"text\"")) {
                        JSONObject v = new JSONObject(r);
                        String name = v.optString("text");
                        if (name.toUpperCase().contains("TINA") || name.toUpperCase().contains("WEEDO")) {
                            found = ip;
                            log("FOUND " + name + " at " + ip);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            final String f = found;
            ui.post(() -> {
                if (f != null) {
                    ipField.setText(f);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("ip", f).apply();
                    toast("Found printer at " + f);
                } else {
                    toast("No printer found");
                }
            });
        });
    }

    private String localIpv4Prefix() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (!(n.startsWith("wlan") || n.startsWith("eth"))) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String h = a.getHostAddress();
                        int dot = h.lastIndexOf('.');
                        if (dot > 0) return h.substring(0, dot);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(2500);
        c.setReadTimeout(4000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(in);
        if (code >= 400) throw new Exception("HTTP " + code + " " + body);
        return body;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    // ---------- Upload ----------

    private void upload() {
        if (fileUri == null) {
            toast("Choose a .gcode file first");
            return;
        }
        final boolean print = printAfter.isChecked();
        final Uri uri = fileUri;
        final String name = fileName;
        final long len = querySize(uri);
        uploadBtn.setEnabled(false);
        log("Uploading " + name + " to " + base() + (print ? " (and starting print)" : "") + "...");
        exec.execute(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                String result = uploadStream(in, len, name, print);
                log(result);
                toast("Upload OK");
            } catch (Exception e) {
                log("ERROR uploading: " + e);
                toast("Upload error");
            } finally {
                ui.post(() -> uploadBtn.setEnabled(true));
            }
        });
    }

    private void uploadFile(final File f, final String name, final boolean print) {
        exec.execute(() -> {
            try {
                InputStream in = new java.io.FileInputStream(f);
                String result = uploadStream(in, f.length(), name, print);
                log(result);
            } catch (Exception e) {
                log("ERROR uploading sliced gcode: " + e);
            }
        });
    }

    /**
     * Uploads a G-code stream to POST /api/files/local as multipart (fixed Content-Length —
     * the ESP32 firmware does not accept chunked transfer encoding), then selects/prints it
     * via POST /api/files/sdcard/<name>. Returns a log line.
     */
    private String uploadStream(InputStream in, long fileLen, String name, boolean print) throws Exception {
        String b = base();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("ip", ipField.getText().toString().trim()).apply();
        String boundary = "----Boundary" + Long.toHexString(System.currentTimeMillis());

        byte[] pre = (field(boundary, "filename", name)
                + fileHeader(boundary, "file", name)).getBytes("UTF-8");
        byte[] post = ("\r\n"
                + field(boundary, "path", "")
                + field(boundary, "select", "false")
                + field(boundary, "print", "false")
                + "--" + boundary + "--\r\n").getBytes("UTF-8");

        HttpURLConnection c = (HttpURLConnection) new URL(b + "/api/files/local").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(pre.length + fileLen + post.length);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        OutputStream os = c.getOutputStream();
        os.write(pre);
        byte[] buf = new byte[16384];
        int n; long total = 0;
        while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); total += n; }
        in.close();
        os.write(post);
        os.flush();
        os.close();

        int code = c.getResponseCode();
        InputStream rin = code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(rin);
        String out = "Upload HTTP " + code + ": " + body + "  (" + total + " bytes)";
        if (code >= 400) throw new Exception(out);

        // The printer stores the file on SD under an 8.3 name (e.g. slice.gcode ->
        // SLICE~1.GCO) and the select endpoint ONLY honours that 8.3 name — selecting
        // by the original long name returns HTTP 204 but silently does nothing. Work
        // out the real stored name before selecting.
        String stored = resolveStoredName(name, total);
        log("  stored on SD as: " + stored);

        // The firmware also needs a moment to flush the upload to SD, so the select can
        // transiently fail; retry a few times until the job is actually running.
        String sel = "";
        for (int attempt = 1; attempt <= 8; attempt++) {
            sel = selectFile(stored, print);
            if (!print || sel.contains("started")) break;
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        }
        if (print && sel.contains("started")) armPostPrintHome(base());
        return out + "\n  " + sel;
    }

    /**
     * The Tina2S firmware does not run the G-code after the final extruding move (it
     * resets at end of print), so the head is left wherever it stopped. Watch the job
     * in the background and home the head once the print finishes.
     */
    private void armPostPrintHome(final String b) {
        new Thread(() -> {
            boolean started = false;
            for (int i = 0; i < 30 && !started; i++) {
                started = isPrinting(b);
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
            if (!started) { log("Post-print home: print never started; not armed."); return; }
            log("Post-print home armed — the head will be homed when the print finishes.");
            for (int i = 0; i < 10800; i++) {           // up to ~6 hours
                if (!isPrinting(b)) {
                    try { Thread.sleep(6000); } catch (InterruptedException e) { return; }
                    try {
                        post(b + "/api/printer/command", "{\"command\":\"G28 X0 Y0\"}");
                        log("Print finished — homed the head (G28 X0 Y0).");
                    } catch (Exception e) {
                        log("Post-print home failed: " + e);
                    }
                    return;
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
            log("Post-print home: gave up waiting.");
        }).start();
    }

    /** POST a JSON body to a URL. */
    private void post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        OutputStream os = c.getOutputStream();
        os.write(json.getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        if (code >= 400) throw new Exception("HTTP " + code);
    }

    /**
     * Finds the 8.3 name the printer stored the just-uploaded file under. Lists the
     * printer's files and matches by byte size (the file list has no timestamps); if
     * the listing is not ready yet, falls back to the firmware's naming scheme
     * (6-char base + "~1" + 3-char extension, upper-cased).
     */
    private String resolveStoredName(String longName, long size) {
        String b = base();
        for (int i = 0; i < 10; i++) {
            try {
                JSONArray arr = new JSONObject(get(b + "/api/files/local")).optJSONArray("files");
                if (arr != null) {
                    for (int k = 0; k < arr.length(); k++) {
                        JSONObject f = arr.optJSONObject(k);
                        if (f != null && f.optLong("size") == size) {
                            String n = f.optString("name");
                            if (n.length() > 0) return n;
                        }
                    }
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(700); } catch (InterruptedException ignored) {}
        }
        return shortName(longName);
    }

    /** The firmware's 8.3 conversion: 6-char base + "~1" + 3-char extension, upper-cased. */
    private static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        base = base.replaceAll("[^A-Za-z0-9]", "");
        ext = ext.replaceAll("[^A-Za-z0-9]", "");
        if (base.length() > 6) base = base.substring(0, 6);
        if (ext.length() > 3) ext = ext.substring(0, 3);
        base = base.toUpperCase();
        ext = ext.toUpperCase();
        return ext.isEmpty() ? base + "~1" : base + "~1." + ext;
    }

    /** POST /api/files/sdcard/<name> {"command":"select","print":bool}. Returns a log line. */
    private String selectFile(String name, boolean print) {
        try {
            String b = base();
            HttpURLConnection c = (HttpURLConnection) new URL(
                    b + "/api/files/sdcard/" + name).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            String body = "{\"command\":\"select\",\"print\":" + (print ? "true" : "false") + "}";
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            InputStream rin = code < 400 ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(rin);
            String line = "Select(" + name + ", print=" + print + ") HTTP " + code
                    + (resp.isEmpty() ? "" : " " + resp);
            if (print && code < 400 && isPrinting(b)) line += " — started";
            return line;
        } catch (Exception e) {
            return "Select error: " + e;
        }
    }

    private boolean isPrinting(String b) {
        try {
            JSONObject j = new JSONObject(get(b + "/api/job"));
            return j.optString("state").toLowerCase().contains("printing");
        } catch (Exception e) {
            return false;
        }
    }

    /** POST /api/job with a command. */
    private void job(final String command, final String action) {
        final String b = base();
        exec.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(b + "/api/job").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(30000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                String body = action == null
                        ? "{\"command\":\"" + command + "\"}"
                        : "{\"command\":\"" + command + "\",\"action\":\"" + action + "\"}";
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
                int code = c.getResponseCode();
                InputStream rin = code < 400 ? c.getInputStream() : c.getErrorStream();
                log("Job " + body + " -> HTTP " + code + " " + readAll(rin));
            } catch (Exception e) {
                log("Job error: " + e);
            }
        });
    }

    private static String field(String boundary, String name, String value) {
        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
    }

    private static String fileHeader(String boundary, String name, String filename) {
        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
    }

    private long querySize(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
