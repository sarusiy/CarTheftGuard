package com.sarusiy.cartheftguard;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Appends a one-line summary of each completed/failed UDS DID sweep to a
 * shared can-captures/uds_scan_log.csv. The raw CAN capture never shows the
 * board's own outgoing UDS requests (see BoardLink.startUdsScan's doc), only
 * genuine replies, so there's no way to tell "scan swept its full range,
 * nobody answered" apart from "scan never ran" after the fact -- this log,
 * built from the same status polling LearnFragment/RecordFragment already
 * do, closes that gap without any firmware change: the firmware's
 * current_did already reaches did_end on a completed sweep, so comparing
 * the two here is enough.
 *
 * Deliberately lives at the top of can-captures/ (not nested per-car) so
 * it's picked up by RecordFragment.listAllCaptureFiles()'s existing
 * top-level *.csv filter with no changes there -- it just rides along with
 * the normal "Saved recordings" / Drive-upload list, and "Clear all
 * recordings" is free to delete it like any other file.
 *
 * One file per app run (timestamped the first time anything is logged,
 * then reused for the rest of that process's lifetime) rather than one
 * ever-growing file -- each visit to the car starts from a clean log,
 * matching how the CSV captures themselves are already one-file-per-
 * recording rather than one accumulating file.
 */
public final class UdsScanLog {
    private static final String TAG = "UdsScanLog";
    private static final Object LOCK = new Object();
    private static volatile String sessionFileName;

    private UdsScanLog() {
    }

    private static String sessionFileName() {
        if (sessionFileName == null) {
            synchronized (LOCK) {
                if (sessionFileName == null) {
                    sessionFileName = "uds_scan_log_"
                            + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
                }
            }
        }
        return sessionFileName;
    }

    /** {@code state} is the raw firmware status ("done" or "error");
     * {@code currentDidHex} is the status JSON's current_did field
     * (e.g. "0x0200"). Completion is current_did having reached did_end --
     * if the sweep stopped early (crash, disconnect, bug), current_did will
     * be short of did_end and this records that plainly. {@code session} is
     * the status JSON's session field ("positive"/"negative"/"timeout"/
     * "none") -- the outcome of the one-shot Diagnostic Session Control
     * (0x10 0x03) request the firmware now sends before a sweep starts, see
     * UDS_BODY_MODULE_RESEARCH.md's 2026-09-15 update. */
    public static void append(Context context, String carId, String stepLabel, UdsTargets.Target target,
                               int didStart, int didEnd, String state, String currentDidHex, int resultCount,
                               String session) {
        synchronized (LOCK) {
            try {
                File dir = new File(context.getExternalFilesDir(null), "can-captures");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "could not create " + dir);
                    return;
                }
                File file = new File(dir, sessionFileName());
                boolean writeHeader = !file.exists();
                int currentDid = parseHex(currentDidHex, didStart);
                boolean completed = "done".equals(state) && currentDid >= didEnd;
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
                try (FileWriter writer = new FileWriter(file, true)) {
                    if (writeHeader) {
                        writer.write("timestamp,car,step,target,req_id,resp_id,did_start,did_end,"
                                + "final_current_did,result_count,state,completed,session\n");
                    }
                    writer.write(String.format(Locale.US,
                            "%s,%s,%s,%s,0x%03X,0x%03X,0x%04X,0x%04X,0x%04X,%d,%s,%b,%s\n",
                            timestamp, csvSafe(carId), csvSafe(stepLabel), csvSafe(target.label),
                            target.requestId, target.responseId, didStart, didEnd, currentDid,
                            resultCount, state, completed, csvSafe(session)));
                }
            } catch (IOException exception) {
                Log.w(TAG, "append failed", exception);
            }
        }
    }

    private static int parseHex(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(hex.replaceFirst("(?i)^0x", ""), 16);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String csvSafe(String value) {
        return value == null ? "" : value.replace(",", ";");
    }
}
