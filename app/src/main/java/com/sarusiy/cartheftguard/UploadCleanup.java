package com.sarusiy.cartheftguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Auto-deletes capture files on app launch, but only ones re-verified
 * actually present in Drive at deletion time -- not just ones DriveUploader
 * once reported success for. A real incident (2026-09-15) showed
 * DriveUploader's own immediate post-write check can still be fooled (a
 * local SAF write can succeed against the provider's cache with no real
 * path to Google's servers at the time), and the old design trusted that
 * single flag forever, which ended up deleting local capture files that
 * had never actually reached Drive. Re-checking at delete time -- often
 * hours after the original upload attempt -- gives real background sync
 * time to either have completed or to reveal it never will, which is a
 * materially stronger signal than trusting a stale flag from one instant.
 */
public final class UploadCleanup {
    private static final String TAG = "UploadCleanup";
    private static final String PREFS_NAME = "uploaded_files";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private UploadCleanup() {
    }

    /** {@code driveFolderUri} is the SAF tree the file was uploaded into --
     * stored so deleteUploadedOnLaunch can re-check the same location
     * later, not just trust that this one upload attempt succeeded. */
    public static void markUploaded(Context context, String fileName, Uri driveFolderUri) {
        prefs(context).edit().putString(fileName, driveFolderUri.toString()).apply();
    }

    /** Call once per genuine app launch (not on every fragment recreation) --
     * see MainActivity.onCreate's savedInstanceState == null guard. Runs on
     * a background thread: this now does SAF/ContentProvider queries (real
     * IPC to the Drive app), not just local file I/O, so it shouldn't block
     * app startup. */
    public static void deleteUploadedOnLaunch(Context context) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            SharedPreferences prefs = prefs(appContext);
            File root = new File(appContext.getExternalFilesDir(null), "can-captures");
            File[] entries = root.listFiles();
            if (entries == null) {
                return;
            }
            int deleted = 0;
            int skipped = 0;
            for (File entry : entries) {
                if (entry.isDirectory()) {
                    File[] nested = entry.listFiles();
                    if (nested != null) {
                        for (File file : nested) {
                            int result = deleteIfVerified(appContext, prefs, file);
                            if (result > 0) {
                                deleted++;
                            } else if (result == 0) {
                                skipped++;
                            }
                        }
                    }
                } else {
                    int result = deleteIfVerified(appContext, prefs, entry);
                    if (result > 0) {
                        deleted++;
                    } else if (result == 0) {
                        skipped++;
                    }
                }
            }
            if (deleted > 0 || skipped > 0) {
                Log.i(TAG, "deleteUploadedOnLaunch: removed " + deleted
                        + ", skipped (marked uploaded but not verified in Drive) " + skipped);
            }
        });
    }

    /** Returns 1 if deleted, 0 if it was marked uploaded but verification
     * failed just now (left in place locally, stale mark cleared so a
     * future real upload can mark it again), -1 if never marked uploaded
     * at all (untouched, not our concern). */
    private static int deleteIfVerified(Context context, SharedPreferences prefs, File file) {
        String folderUriString = prefs.getString(file.getName(), null);
        if (folderUriString == null) {
            return -1;
        }
        if (!verifyStillInDrive(context, folderUriString, file)) {
            Log.w(TAG, "not verified in Drive, leaving local file and clearing stale mark: " + file.getName());
            prefs.edit().remove(file.getName()).apply();
            return 0;
        }
        if (!file.delete()) {
            return 0;
        }
        prefs.edit().remove(file.getName()).apply();
        return 1;
    }

    private static boolean verifyStillInDrive(Context context, String folderUriString, File file) {
        try {
            Uri folderUri = Uri.parse(folderUriString);
            DocumentFile folder = DocumentFile.fromTreeUri(context, folderUri);
            if (folder == null || !folder.isDirectory()) {
                return false;
            }
            DocumentFile match = folder.findFile(file.getName());
            return match != null && match.length() == file.length();
        } catch (Exception exception) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
