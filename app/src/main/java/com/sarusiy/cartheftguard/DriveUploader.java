package com.sarusiy.cartheftguard;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Copies a capture CSV into a Drive folder the user picked once via
 * Android's system document-tree picker (Storage Access Framework), rather
 * than talking to the Drive REST API directly. The phone's own Google Drive
 * app is already registered as a SAF document provider and already signed
 * into the user's account, so this needs no Google Sign-In/OAuth wiring, no
 * Cloud Console client registration, and no extra credentials shipped in the
 * app -- just the persisted tree Uri the picker returns, which keeps working
 * across app restarts and reboots once persistable permission is taken (see
 * RecordFragment's chooseDriveFolderLauncher).
 */
public final class DriveUploader {
    private static final String TAG = "DriveUploader";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DriveUploader() {
    }

    /** Copies {@code file} into the SAF tree at {@code folderUri}. If a file
     * with the same name already exists there, it's deleted first so re-
     * uploading overwrites rather than piling up duplicates. */
    public static void upload(Context context, Uri folderUri, File file, Consumer<Boolean> callback) {
        if (callback == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            boolean success = false;
            try {
                DocumentFile folder = DocumentFile.fromTreeUri(appContext, folderUri);
                if (folder == null || !folder.isDirectory()) {
                    MAIN.post(() -> callback.accept(false));
                    return;
                }
                DocumentFile existing = folder.findFile(file.getName());
                if (existing != null) {
                    existing.delete();
                }
                DocumentFile created = folder.createFile("text/csv", file.getName());
                if (created == null) {
                    MAIN.post(() -> callback.accept(false));
                    return;
                }
                try (InputStream input = new FileInputStream(file);
                     OutputStream output = appContext.getContentResolver().openOutputStream(created.getUri())) {
                    if (output == null) {
                        MAIN.post(() -> callback.accept(false));
                        return;
                    }
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
                success = true;
                Log.i(TAG, "upload(" + file.getName() + ") -> copied to " + folderUri);
            } catch (Exception exception) {
                Log.w(TAG, "upload(" + file.getName() + ") failed", exception);
                success = false;
            }
            boolean finalSuccess = success;
            MAIN.post(() -> callback.accept(finalSuccess));
        });
    }
}
