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
import android.webkit.WebViewClient;
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
    private LinearLayout sliceRoot;
    private String stlName;
    private File stlFile;
    private File slicedGcodeFile;
    private TextView sliceLabel;

    // Status tab
    private View printView, sliceView, statusView;
    private Button printTab, sliceTab, statusTab;
    private TextView stDevice, stState, stNozzle, stBed, stFile, stProgress, stElapsed, stLeft, stUpdated;
    private int currentTab = 0;
    private volatile boolean statusVisible = false;
    private volatile boolean statusInFlight = false;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Re-polls the printer every 2 s while the Status tab is on screen. */
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (!statusVisible) return;
            refreshStatus();
            ui.postDelayed(this, 2000);
        }
    };

    private static final String PREFS = "tina2print";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        stlFile = new File(getFilesDir(), "model.stl");
        slicedGcodeFile = new File(getFilesDir(), "sliced.gcode");

        FrameLayout content = new FrameLayout(this);
        printView = buildPrintView();
        sliceView = buildSliceView();
        statusView = buildStatusView();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        printTab = new Button(this);
        printTab.setText("Print");
        sliceTab = new Button(this);
        sliceTab.setText("Slice");
        statusTab = new Button(this);
        statusTab.setText("Status");
        tabs.addView(printTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(sliceTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(statusTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabs);

        sliceView.setVisibility(View.GONE);
        statusView.setVisibility(View.GONE);
        content.addView(printView);
        content.addView(sliceView);
        content.addView(statusView);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        printTab.setOnClickListener(v -> showTab(0));
        sliceTab.setOnClickListener(v -> showTab(1));
        statusTab.setOnClickListener(v -> showTab(2));
        showTab(0);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ipField.setText(prefs.getString("ip", "10.0.0.96"));

        log("Tina2 Print ready. Set the printer IP and upload a sliced .gcode.");

        // Check GitHub Releases for a newer build (no Play Store / F-Droid). Non-blocking.
        Updater.checkOnStartup(this);
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
        row1.addView(scanBtn, weight());
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
        sliceRoot = new LinearLayout(this);
        sliceRoot.setOrientation(LinearLayout.VERTICAL);
        populateSlicer(sliceRoot);
        return sliceRoot;
    }

    /** Creates the slicer WebView and its loopback server inside the container. */
    private void populateSlicer(LinearLayout root) {
        root.removeAllViews();

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF101214);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new SliceBridge(), "T2");
        // Re-announce the loaded STL once the (fresh) page has finished loading.
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                if (stlName != null && stlFile.exists() && stlFile.length() > 0) {
                    v.evaluateJavascript("window.onStlReady(" + JSONObject.quote(stlName) + ")", null);
                }
            }
        });
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
    }

    /**
     * Tears down and rebuilds the slicer WebView. Reloading the page drops the
     * Kiri:Moto worker pool, which is the only way to clear a worker that hung
     * mid-slice (the engine has no cancel API). A fresh WebView guarantees the
     * old workers and their WASM heaps are released.
     */
    private void rebuildSlicer() {
        if (server != null) { server.stop(); server = null; }
        if (webView != null) {
            webView.removeJavascriptInterface("T2");
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        populateSlicer(sliceRoot);
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

        @JavascriptInterface
        public void resetSlicer() {
            ui.post(MainActivity.this::rebuildSlicer);
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

    private void showTab(int index) {
        currentTab = index;
        printView.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        sliceView.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        statusView.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        printTab.setAlpha(index == 0 ? 1f : 0.5f);
        sliceTab.setAlpha(index == 1 ? 1f : 0.5f);
        statusTab.setAlpha(index == 2 ? 1f : 0.5f);

        statusVisible = (index == 2);
        ui.removeCallbacks(statusPoll);
        if (statusVisible) ui.post(statusPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusVisible = false;
        ui.removeCallbacks(statusPoll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentTab == 2) showTab(2);   // resume polling
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
                stlName = name;
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

    // ---------- Status tab ----------

    private View buildStatusView() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(12), pad, pad);

        root.addView(section("Printer"));
        stDevice = field(root, "Device");
        stState  = field(root, "State");
        stNozzle = field(root, "Nozzle");
        stBed    = field(root, "Bed");

        root.addView(section("Job"));
        stFile     = field(root, "File");
        stProgress = field(root, "Progress");
        stElapsed  = field(root, "Elapsed");
        stLeft     = field(root, "Time left");

        stUpdated = new TextView(this);
        stUpdated.setTextSize(11);
        stUpdated.setTextColor(0xFF9AA2AD);
        stUpdated.setPadding(dp(2), dp(16), 0, 0);
        root.addView(stUpdated);

        View filler = new View(this);
        root.addView(filler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    /** One label/value row; returns the value TextView so it can be updated. */
    private TextView field(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(7), dp(2), dp(7));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(0xFF9AA2AD);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f));

        TextView val = new TextView(this);
        val.setText("\u2014");
        val.setTextSize(15);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);
        val.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f));

        row.addView(l);
        row.addView(val);
        parent.addView(row);
        return val;
    }

    /** Kick off one poll of the printer; safe to call repeatedly. */
    private void refreshStatus() {
        if (statusInFlight) return;
        statusInFlight = true;
        final String b = base();
        exec.execute(() -> {
            try {
                JSONObject v = new JSONObject(get(b + "/api/version"));
                JSONObject p = new JSONObject(get(b + "/api/printer"));
                JSONObject j = new JSONObject(get(b + "/api/job"));
                ui.post(() -> applyStatus(v, p, j));
            } catch (Exception e) {
                ui.post(() -> {
                    stState.setText("unreachable");
                    stUpdated.setText("Last attempt " + clock() + " \u2014 " + e);
                });
            } finally {
                statusInFlight = false;
            }
        });
    }

    private void applyStatus(JSONObject v, JSONObject p, JSONObject j) {
        stDevice.setText(v.optString("text", "?") + "  fw " + v.optString("device_version", "?"));

        JSONObject st = p.optJSONObject("state");
        stState.setText(st != null ? st.optString("text", "?") : "?");

        JSONObject temp = p.optJSONObject("temperature");
        if (temp != null) {
            JSONObject tool = temp.optJSONObject("tool0");
            JSONObject bed = temp.optJSONObject("bed");
            if (tool != null) stNozzle.setText(tool.optInt("actual") + " / " + tool.optInt("target") + " \u00B0C");
            if (bed != null) stBed.setText(bed.optInt("actual") + " / " + bed.optInt("target") + " \u00B0C");
        }

        JSONObject job = j.optJSONObject("job");
        if (job != null) {
            JSONObject f = job.optJSONObject("file");
            stFile.setText(f != null ? f.optString("name", "\u2014") : "\u2014");
        }

        JSONObject prog = j.optJSONObject("progress");
        if (prog != null) {
            stProgress.setText(String.format("%.1f %%", prog.optDouble("completion", 0)));
            stElapsed.setText(hms(prog.optInt("printTime", 0)));
            stLeft.setText(hms(prog.optInt("printTimeLeft", 0)));
        }

        stUpdated.setText("Updated " + clock());
    }

    private String clock() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
    }

    private String hms(int sec) {
        if (sec <= 0) return "\u2014";
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
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
        return out + "\n  " + sel;
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
