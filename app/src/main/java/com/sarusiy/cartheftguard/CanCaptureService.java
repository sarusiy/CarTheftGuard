package com.sarusiy.cartheftguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Network;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Records raw CAN frames from the P4 incremental capture endpoint into CSV. */
public final class CanCaptureService extends Service {
    public static final String ACTION_START = "com.sarusiy.cartheftguard.action.START_CAN_CAPTURE";
    public static final String ACTION_STOP = "com.sarusiy.cartheftguard.action.STOP_CAN_CAPTURE";
    public static final String ACTION_STATUS = "com.sarusiy.cartheftguard.action.CAN_CAPTURE_STATUS";
    public static final String EXTRA_BOARD_IP = "board_ip";
    public static final String EXTRA_PASSIVE = "passive";
    /** Optional filename prefix (e.g. "learn-lock") instead of the generic
     * "can-" prefix, so guided-learning steps produce clearly-labeled files
     * (see LearnFragment) instead of just a timestamp. */
    public static final String EXTRA_LABEL = "label";
    /** Optional car id (e.g. "fiat500_2014") to nest the recording under
     * can-captures/&lt;car&gt;/ instead of flat can-captures/ -- keeps
     * per-vehicle Learn sessions from mixing together (see LearnFragment). */
    public static final String EXTRA_CAR = "car";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_FRAMES = "frames";
    public static final String EXTRA_DROPPED = "dropped";
    public static final String EXTRA_FILE = "file";
    public static final String EXTRA_ERROR = "error";

    private static final String CHANNEL_ID = "can_capture";
    private static final int NOTIFICATION_ID = 1201;
    private static final long GPS_RECORDING_POLL_MS = 1000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean recording;
    private long frameCount;
    private long droppedCount;
    private String filePath = "";
    private GpsSnapshot gpsSnapshot = GpsSnapshot.empty();

    private static final class GpsSnapshot {
        final boolean fixValid;
        final double lat;
        final double lon;
        final double speedKmh;
        final double headingDeg;
        final int satellites;

        GpsSnapshot(boolean fixValid, double lat, double lon, double speedKmh, double headingDeg, int satellites) {
            this.fixValid = fixValid;
            this.lat = lat;
            this.lon = lon;
            this.speedKmh = speedKmh;
            this.headingDeg = headingDeg;
            this.satellites = satellites;
        }

        static GpsSnapshot empty() {
            return new GpsSnapshot(false, 0, 0, 0, 0, 0);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "CAN recording", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || recording) {
            return START_NOT_STICKY;
        }

        String boardIp = intent.getStringExtra(EXTRA_BOARD_IP);
        Network network = BoardLink.getInstance(getApplicationContext()).getBoardNetwork();
        if (boardIp == null || boardIp.trim().isEmpty() || network == null) {
            publishStatus("Board Wi-Fi is not connected");
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean passive = intent.getBooleanExtra(EXTRA_PASSIVE, true);
        String label = intent.getStringExtra(EXTRA_LABEL);
        String car = intent.getStringExtra(EXTRA_CAR);
        recording = true;
        frameCount = 0;
        droppedCount = 0;
        startForeground(NOTIFICATION_ID, buildNotification("Starting CAN recording"));
        executor.execute(() -> captureLoop(network, boardIp, passive, label, car));
        return START_NOT_STICKY;
    }

    private void captureLoop(Network network, String boardIp, boolean passive, String label, String car) {
        Writer writer = null;
        try {
            setCanMode(network, boardIp, passive);
            File baseDirectory = new File(getExternalFilesDir(null), "can-captures");
            File directory = (car != null && !car.trim().isEmpty())
                    ? new File(baseDirectory, car.trim())
                    : baseDirectory;
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("Cannot create capture directory");
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String prefix = (label != null && !label.trim().isEmpty()) ? label.trim() : "can";
            File file = new File(directory, prefix + "-" + stamp + ".csv");
            filePath = file.getAbsolutePath();
            writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            writer.write("phone_time_ms,board_time_us,sequence,bus,can_id,extended,rtr,dlc,data_hex,gps_fix,gps_lat,gps_lon,gps_speed_kmh,gps_heading_deg,gps_satellites\n");

            JSONObject initialState = fetchBatch(network, boardIp, 0);
            long after = initialState.optLong("latest", 0);
            long lastHardwareOverflow = initialState.optLong("hardware_overflow", 0);
            long lastStatusMs = 0;
            long lastGpsMs = 0;
            while (recording) {
                JSONObject response = fetchBatch(network, boardIp, after);
                droppedCount += response.optLong("dropped", 0);
                long hardwareOverflow = response.optLong("hardware_overflow", lastHardwareOverflow);
                if (hardwareOverflow >= lastHardwareOverflow) {
                    droppedCount += hardwareOverflow - lastHardwareOverflow;
                }
                lastHardwareOverflow = hardwareOverflow;
                long now = System.currentTimeMillis();
                if (now - lastGpsMs >= GPS_RECORDING_POLL_MS) {
                    gpsSnapshot = fetchGpsSnapshot(network, boardIp);
                    lastGpsMs = now;
                }
                JSONArray frames = response.getJSONArray("frames");
                for (int index = 0; index < frames.length(); index++) {
                    JSONObject frame = frames.getJSONObject(index);
                    long sequence = frame.getLong("seq");
                    GpsSnapshot gps = gpsSnapshot;
                    writer.write(System.currentTimeMillis() + ","
                            + frame.getLong("time_us") + ","
                            + sequence + ","
                            + frame.optInt("bus", 0) + ",0x"
                            + Long.toHexString(frame.getLong("id")).toUpperCase(Locale.US) + ","
                            + frame.optBoolean("extended", false) + ","
                            + frame.optBoolean("rtr", false) + ","
                            + frame.getInt("dlc") + ","
                            + frame.getString("data") + ","
                            + gps.fixValid + ","
                            + String.format(Locale.US, "%.6f", gps.lat) + ","
                            + String.format(Locale.US, "%.6f", gps.lon) + ","
                            + String.format(Locale.US, "%.1f", gps.speedKmh) + ","
                            + String.format(Locale.US, "%.1f", gps.headingDeg) + ","
                            + gps.satellites + "\n");
                    after = sequence;
                    frameCount++;
                }
                if (frames.length() == 0) {
                    after = response.optLong("latest", after);
                    Thread.sleep(100);
                }

                if (now - lastStatusMs >= 1000) {
                    writer.flush();
                    lastStatusMs = now;
                    publishStatus(null);
                    getSystemService(NotificationManager.class).notify(
                            NOTIFICATION_ID, buildNotification(frameCount + " CAN frames recorded"));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            publishStatus(exception.getMessage());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
            recording = false;
            publishStatus(null);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void setCanMode(Network network, String boardIp, boolean passive) throws Exception {
        byte[] body = (passive ? "passive" : "active").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL(
                "http://" + boardIp + "/api/can/mode"));
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        try (java.io.OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        int code = connection.getResponseCode();
        connection.disconnect();
        if (code >= 400) {
            throw new IllegalStateException("CAN mode change failed: HTTP " + code);
        }
    }

    private JSONObject fetchBatch(Network network, String boardIp, long after) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL(
                "http://" + boardIp + "/api/can?after=" + after));
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        StringBuilder body = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
        }
        connection.disconnect();
        if (code >= 400) {
            throw new IllegalStateException("Capture request failed: HTTP " + code);
        }
        return new JSONObject(body.toString());
    }

    private GpsSnapshot fetchGpsSnapshot(Network network, String boardIp) {
        try {
            HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL(
                    "http://" + boardIp + "/api/gps"));
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }
            }
            connection.disconnect();
            if (code >= 400) {
                return gpsSnapshot;
            }
            JSONObject gps = new JSONObject(body.toString());
            return new GpsSnapshot(
                    gps.optBoolean("fix_valid", false),
                    gps.optDouble("lat", 0),
                    gps.optDouble("lon", 0),
                    gps.optDouble("speed_kmh", 0),
                    gps.optDouble("heading_deg", 0),
                    gps.optInt("satellites", 0));
        } catch (Exception exception) {
            return gpsSnapshot;
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("CarTheftGuard CAN capture")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private void stopRecording() {
        recording = false;
        executor.shutdownNow();
    }

    private void publishStatus(String error) {
        Intent status = new Intent(ACTION_STATUS).setPackage(getPackageName());
        status.putExtra(EXTRA_RUNNING, recording);
        status.putExtra(EXTRA_FRAMES, frameCount);
        status.putExtra(EXTRA_DROPPED, droppedCount);
        status.putExtra(EXTRA_FILE, filePath);
        if (error != null) {
            status.putExtra(EXTRA_ERROR, error);
        }
        sendBroadcast(status);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        recording = false;
        executor.shutdownNow();
        super.onDestroy();
    }
}
