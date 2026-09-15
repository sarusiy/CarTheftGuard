package com.sarusiy.cartheftguard;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Looks for the newest firmware/app build in the project's shared Google
 * Drive folder (see local.properties: driveFolderId) and downloads it --
 * the "automatic" alternative to manually picking a file, so a build
 * uploaded from the PC (see tools/upload-to-drive.ps1 in each repo) shows up
 * here without any file transfer step at the car. Uses a plain, restricted
 * (Drive-API-only) API key against publicly-shared folder contents, not a
 * signed-in Drive account -- read-only, no OAuth, deliberately low-privilege
 * since this key ships inside the APK.
 *
 * Talks to Drive over the phone's normal default network (plain
 * {@code new URL(...).openConnection()}, not BoardLink's scoped board
 * connection) -- Drive isn't reachable through the board's own AP, but the
 * phone keeps its normal route (cellular or another Wi-Fi) active
 * simultaneously, same reasoning as BoardLink's WifiNetworkSpecifier being
 * scoped rather than the default route.
 */
public final class DriveUpdates {
    private static final String TAG = "DriveUpdates";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DriveUpdates() {
    }

    public static final class DriveFile {
        public final String id;
        public final String name;
        public final long size;
        public final String modifiedTime;

        DriveFile(String id, String name, long size, String modifiedTime) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.modifiedTime = modifiedTime;
        }
    }

    /** {@code file} is null unless a match was found; {@code error} is null
     * on a clean lookup (whether or not it matched) and non-null when the
     * lookup itself couldn't complete -- e.g. no network route to Drive
     * (very possible at the car, where the phone may have weak/no cellular
     * signal while connected to the board's own no-internet Wi-Fi AP), a
     * Drive API error, or missing build config. Callers should show
     * {@code error} distinctly from "nothing found here" -- a caller
     * conflating the two previously showed "No firmware/app found in the
     * Drive folder" for what was actually a network failure, with the file
     * sitting right there in Drive the whole time. */
    public static final class LookupResult {
        public final DriveFile file;
        public final String error;

        LookupResult(DriveFile file, String error) {
            this.file = file;
            this.error = error;
        }
    }

    /**
     * Finds the most-recently-modified file in the configured Drive folder
     * whose name ends with {@code suffix} (case-insensitive, e.g. ".bin" or
     * ".apk"). See {@link LookupResult} for how to tell "not found" apart
     * from "couldn't check."
     */
    public static void findLatest(String suffix, Consumer<LookupResult> callback) {
        if (callback == null) {
            return;
        }
        EXECUTOR.execute(() -> {
            DriveFile result = null;
            String error = null;
            try {
                if (BuildConfig.DRIVE_API_KEY.isEmpty() || BuildConfig.DRIVE_FOLDER_ID.isEmpty()) {
                    error = "Drive check not configured (missing API key)";
                    MAIN.post(() -> callback.accept(new LookupResult(null, "Drive check not configured (missing API key)")));
                    return;
                }
                String query = "'" + BuildConfig.DRIVE_FOLDER_ID + "' in parents and trashed = false";
                String url = "https://www.googleapis.com/drive/v3/files"
                        + "?q=" + URLEncoder.encode(query, "UTF-8")
                        + "&fields=" + URLEncoder.encode("files(id,name,modifiedTime,size)", "UTF-8")
                        + "&orderBy=" + URLEncoder.encode("modifiedTime desc", "UTF-8")
                        + "&key=" + BuildConfig.DRIVE_API_KEY;
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                int code = connection.getResponseCode();
                String response = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
                connection.disconnect();
                if (code < 400) {
                    JSONArray files = new JSONObject(response).optJSONArray("files");
                    if (files != null) {
                        String suffixLower = suffix.toLowerCase(Locale.US);
                        for (int i = 0; i < files.length(); i++) {
                            JSONObject file = files.getJSONObject(i);
                            String name = file.optString("name", "");
                            if (name.toLowerCase(Locale.US).endsWith(suffixLower)) {
                                result = new DriveFile(file.optString("id"), name,
                                        file.optLong("size", 0), file.optString("modifiedTime", ""));
                                break; /* list is already ordered newest-first */
                            }
                        }
                    }
                    Log.i(TAG, "findLatest(\"" + suffix + "\") -> HTTP " + code + ", " +
                            (files != null ? files.length() : 0) + " file(s) in folder, match=" + (result != null));
                } else {
                    error = "Drive returned HTTP " + code;
                    Log.w(TAG, "findLatest(\"" + suffix + "\") -> HTTP " + code + ": " + response);
                }
            } catch (java.net.UnknownHostException | java.net.SocketTimeoutException | java.net.ConnectException exception) {
                error = "No internet connection (check mobile data/Wi-Fi, not just the board's own network)";
                Log.w(TAG, "findLatest(\"" + suffix + "\") failed -- likely no internet route", exception);
            } catch (Exception exception) {
                error = "Network error: " + exception.getMessage();
                Log.w(TAG, "findLatest(\"" + suffix + "\") failed", exception);
            }
            DriveFile finalResult = result;
            String finalError = error;
            MAIN.post(() -> callback.accept(new LookupResult(finalResult, finalError)));
        });
    }

    /** Downloads a Drive file's raw bytes to {@code destFile}, overwriting
     * it if present. {@code callback} gets null on success, or an error
     * message on failure (see {@link LookupResult} for the same
     * network-vs-other-failure reasoning). */
    public static void download(String fileId, File destFile, Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        EXECUTOR.execute(() -> {
            String error = null;
            try {
                String url = "https://www.googleapis.com/drive/v3/files/" + fileId
                        + "?alt=media&key=" + BuildConfig.DRIVE_API_KEY;
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(30000);
                int code = connection.getResponseCode();
                if (code < 400) {
                    try (InputStream input = connection.getInputStream();
                         OutputStream output = new FileOutputStream(destFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    }
                } else {
                    error = "Drive returned HTTP " + code;
                }
                connection.disconnect();
            } catch (java.net.UnknownHostException | java.net.SocketTimeoutException | java.net.ConnectException exception) {
                error = "No internet connection (check mobile data/Wi-Fi, not just the board's own network)";
            } catch (Exception exception) {
                error = "Download failed: " + exception.getMessage();
            }
            String finalError = error;
            MAIN.post(() -> callback.accept(finalError));
        });
    }

    /** Downloads a small text file's content directly (no disk write) --
     * used for the version-string companion files publish-*-to-drive.ps1
     * upload alongside each .bin/.apk, so the About screen can show what
     * version a pending update actually is before you commit to it. */
    public static void downloadText(String fileId, Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        EXECUTOR.execute(() -> {
            String result = null;
            try {
                String url = "https://www.googleapis.com/drive/v3/files/" + fileId
                        + "?alt=media&key=" + BuildConfig.DRIVE_API_KEY;
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                int code = connection.getResponseCode();
                if (code < 400) {
                    result = readAll(connection.getInputStream()).trim();
                }
                connection.disconnect();
            } catch (Exception exception) {
                result = null;
            }
            String finalResult = result;
            MAIN.post(() -> callback.accept(finalResult));
        });
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }
}
