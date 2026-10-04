package com.sarusiy.cartheftguard;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Posts a phone notification when the board link comes up or is lost, so the
 * Zepp app can mirror it to the watch (with vibration) even while the watch
 * app is closed. "Link" here means the board's Wi-Fi network: the phone does
 * not hold a BLE connection to the board, it only uses BLE to find it.
 *
 * Users can mute or customize this in Android's per-channel notification
 * settings ("Board link").
 */
public final class LinkNotifier {
    private static final String CHANNEL_ID = "board_link";
    private static final int ID_CONNECTED = 2001;
    private static final int ID_DISCONNECTED = 2002;

    private LinkNotifier() {}

    public static void boardConnected(Context context) {
        post(context, ID_CONNECTED, ID_DISCONNECTED, "Board connected", "Wi-Fi link to the board is ready");
    }

    public static void boardDisconnected(Context context) {
        post(context, ID_DISCONNECTED, ID_CONNECTED, "Board disconnected", "Wi-Fi link to the board was lost");
    }

    private static void post(Context context, int id, int otherId, String title, String text) {
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Board link", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Board Wi-Fi link connected or lost");
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        /* Separate ids per state, cancelling the opposite one, so each change
         * is a fresh notification (a watch mirroring an in-place update may
         * not vibrate again). */
        manager.cancel(otherId);
        manager.notify(id, new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build());
    }
}
