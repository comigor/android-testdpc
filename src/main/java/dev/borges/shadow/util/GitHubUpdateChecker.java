package dev.borges.shadow.util;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Manual-only update check against GitHub Releases: compares the latest
 * release tag with the installed versionName and hands the phone APK asset
 * to the existing Fetch2 download/install path. No polling, no startup hook.
 */
public class GitHubUpdateChecker {
    private static final String TAG = "GitHubUpdateChecker";
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/comigor/android-testdpc/releases/latest";

    private final AppCompatActivity activity;
    private final DownloadHelper downloadHelper;

    public GitHubUpdateChecker(AppCompatActivity activity, DownloadHelper downloadHelper) {
        this.activity = activity;
        this.downloadHelper = downloadHelper;
    }

    public void checkNow() {
        Toast.makeText(activity, "Checking for updates...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                handleRelease(fetchLatestRelease());
            } catch (RateLimitedException e) {
                Log.w(TAG, "Update check rate-limited");
                toast("GitHub may have rate-limited this network; try again later");
            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
                toast("Update check failed");
            }
        }).start();
    }

    private static class RateLimitedException extends Exception {
    }

    private JSONObject fetchLatestRelease() throws IOException, JSONException, RateLimitedException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "android-testdpc-shadow");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new RateLimitedException();
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub API returned HTTP " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(readAll(in));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        return body.toString();
    }

    private void handleRelease(JSONObject release) throws JSONException {
        String tag = release.getString("tag_name");
        String installed = installedVersion();
        if (compareVersions(tag, installed) <= 0) {
            Log.d(TAG, "Latest release " + tag + " is not newer than installed " + installed);
            toast("Already up to date (" + installed + ")");
            return;
        }
        String apkUrl = pickApkDownloadUrl(release.getJSONArray("assets"));
        if (apkUrl == null) {
            toast("Latest release has no downloadable APK");
            return;
        }
        Log.d(TAG, "Update available: " + tag + " (installed " + installed + "), downloading " + apkUrl);
        activity.runOnUiThread(() -> downloadHelper.downloadAndInstallApk(activity, apkUrl));
    }

    // Prefer the phone APK; fall back to the first APK asset in the release.
    private static String pickApkDownloadUrl(JSONArray assets) {
        String firstApk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            if (!name.endsWith(".apk") || url.isEmpty()) {
                continue;
            }
            if (name.contains("phone")) {
                return url;
            }
            if (firstApk == null) {
                firstApk = url;
            }
        }
        return firstApk;
    }

    // Component-wise numeric comparison, same algorithm as uhp-android's
    // updater: leading "v" stripped, up to 3 components, missing or
    // non-numeric components count as 0.
    static int compareVersions(String left, String right) {
        String[] a = stripLeadingV(left).split("\\.");
        String[] b = stripLeadingV(right).split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = Integer.compare(component(a, i), component(b, i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static String stripLeadingV(String version) {
        return version != null && version.startsWith("v") ? version.substring(1) : version;
    }

    private static int component(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String installedVersion() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "0.0.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    private void toast(String message) {
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }
}
