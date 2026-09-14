package com.sarusiy.cartheftguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

/**
 * Auto-deletes capture files on app launch, but only ones DriveUploader has
 * already confirmed uploaded -- a file this app has never successfully
 * copied to Drive is never touched, so a session you haven't backed up yet
 * survives even across app restarts. "Uploaded" is tracked by filename
 * alone (capture filenames are timestamped, collisions across cars/sessions
 * aren't realistic) in a small SharedPreferences set that RecordFragment
 * updates via markUploaded() right after a successful DriveUploader.upload().
 */
public final class UploadCleanup {
    private static final String TAG = "UploadCleanup";
    private static final String PREFS_NAME = "uploaded_files";

    private UploadCleanup() {
    }

    public static void markUploaded(Context context, String fileName) {
        prefs(context).edit().putBoolean(fileName, true).apply();
    }

    /** Call once per genuine app launch (not on every fragment recreation) --
     * see MainActivity.onCreate's savedInstanceState == null guard. */
    public static void deleteUploadedOnLaunch(Context context) {
        SharedPreferences prefs = prefs(context);
        File root = new File(context.getExternalFilesDir(null), "can-captures");
        File[] entries = root.listFiles();
        if (entries == null) {
            return;
        }
        int deleted = 0;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                File[] nested = entry.listFiles();
                if (nested != null) {
                    for (File file : nested) {
                        if (deleteIfUploaded(prefs, file)) {
                            deleted++;
                        }
                    }
                }
            } else if (deleteIfUploaded(prefs, entry)) {
                deleted++;
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "deleteUploadedOnLaunch: removed " + deleted + " already-uploaded file(s)");
        }
    }

    private static boolean deleteIfUploaded(SharedPreferences prefs, File file) {
        if (!prefs.getBoolean(file.getName(), false)) {
            return false;
        }
        if (!file.delete()) {
            return false;
        }
        prefs.edit().remove(file.getName()).apply();
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
