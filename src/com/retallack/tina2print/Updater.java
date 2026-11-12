package com.retallack.tina2print;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-update from GitHub Releases - no Play Store, no F-Droid.
 *
 * On startup the app queries the latest release of {@link #OWNER}/{@link #REPO}
 * on the GitHub Releases API, and if the release tag is newer than this build's
 * versionName it offers to download the release's .apk asset and hand it to
 * Android's PackageInstaller. Nothing else is needed: the installer runs as the
 * system, and because we are updating our own package Android permits it without
 * root, provided the user grants "install unknown apps" once and every build is
 * signed with the same release keystore.
 */
public final class Updater {

    /** Broadcast action the PackageInstaller session reports back on. */
    static final String INSTALL_ACTION = "com.retallack.tina2print.INSTALL_RESULT";

    private static final String OWNER = "mretallack";
    private static final String REPO = "Tina2SLocal";

    private static final String PREFS = "tina2print";
    private static final String SKIP_KEY = "skip_update_version";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private Updater(Activity activity) {
        this.activity = activity;
    }

    /** Kick off a background update check. Safe to call from onCreate; returns at once. */
    public static void checkOnStartup(Activity activity) {
        new Updater(activity).start();
    }

    private void start() {
        exec.execute(() -> {
            try {
                Release release = fetchLatest();
                if (release == null || release.apkUrl == null) return;
                String current = currentVersionName();
                if (!isNewer(release.version, current)) return;
                SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (release.version.equals(prefs.getString(SKIP_KEY, null))) return;
                ui.post(() -> prompt(release, current));
            } catch (Exception e) {
                // Network / parse problems must never bother the user at startup.
            }
        });
    }

    // ---------- GitHub Releases ----------

    private static final class Release {
        String version;
        String apkUrl;
    }

    private Release fetchLatest() throws Exception {
        URL url = new URL("https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Tina2Print");
        try {
            if (c.getResponseCode() != 200) return null;
            JSONObject o = new JSONObject(read(c.getInputStream()));
            Release r = new Release();
            r.version = stripV(o.optString("tag_name", ""));
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                        r.apkUrl = a.optString("browser_download_url", null);
                        break;
                    }
                }
            }
            return r.version.isEmpty() ? null : r;
        } finally {
            c.disconnect();
        }
    }

    // ---------- Version comparison ----------

    private String currentVersionName() {
        try {
            PackageInfo pi = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return pi.versionName == null ? "0" : pi.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private static String stripV(String v) {
        return v == null ? "" : v.trim().replaceFirst("^[vV]", "");
    }

    /** True if version {@code a} is strictly newer than {@code b}, by numeric segment. */
    static boolean isNewer(String a, String b) {
        int[] x = parseVersion(a);
        int[] y = parseVersion(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? x[i] : 0;
            int yi = i < y.length ? y[i] : 0;
            if (xi != yi) return xi > yi;
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        List<Integer> nums = new ArrayList<>();
        for (String s : stripV(v).split("[^0-9]+")) {
            if (s.isEmpty()) continue;
            try {
                nums.add(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                nums.add(0);
            }
        }
        int[] out = new int[nums.size()];
        for (int i = 0; i < out.length; i++) out[i] = nums.get(i);
        return out;
    }

    // ---------- Prompt / download / install ----------

    private void prompt(Release r, String current) {
        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage("Version " + r.version + " is available.\n\n"
                        + "You have " + current + " installed.\n\nDownload and install now?")
                .setPositiveButton("Update", (d, w) -> downloadAndInstall(r))
                .setNegativeButton("Later", null)
                .setNeutralButton("Skip this version", (d, w) ->
                        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putString(SKIP_KEY, r.version).apply())
                .show();
    }

    private void downloadAndInstall(Release r) {
        toast("Downloading update " + r.version + "…");
        exec.execute(() -> {
            try {
                File apk = new File(activity.getCacheDir(), "update-" + r.version + ".apk");
                download(r.apkUrl, apk);
                ui.post(() -> install(apk));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> toast("Update download failed: " + msg));
            }
        });
    }

    private void download(String urlStr, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "Tina2Print");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Hand the downloaded APK to PackageInstaller. Using a session (rather than an
     * ACTION_VIEW intent on a file:// URI) means we never expose a file URI, so no
     * FileProvider or res/xml is needed.
     */
    private void install(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            toast("Allow \"install unknown apps\" for Tina2 Print, then tap Update again");
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(i);
            return;
        }
        try {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            int sessionId = installer.createSession(params);

            PackageInstaller.Session session = installer.openSession(sessionId);
            try (OutputStream os = session.openWrite("tina2print", 0, apk.length());
                 InputStream is = new FileInputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                session.fsync(os);
            }

            Intent result = new Intent(activity, UpdateResultReceiver.class);
            result.setAction(INSTALL_ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(activity, sessionId, result, flags);
            session.commit(pending.getIntentSender());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            toast("Install failed: " + msg);
        }
    }

    // ---------- helpers ----------

    private void toast(String msg) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream is = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }
}
