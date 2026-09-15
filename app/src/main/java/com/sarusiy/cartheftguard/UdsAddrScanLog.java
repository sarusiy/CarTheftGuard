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
 * Appends a one-line summary of each completed/failed UDS address-discovery
 * sweep to a shared can-captures/uds_addr_scan_log_*.csv -- same reasoning
 * and one-file-per-app-run design as {@link UdsScanLog}, but for the
 * address sweep (BoardLink.startUdsAddrScan) instead of the per-module DID
 * sweep. Lives at the top of can-captures/ so it rides along with the
 * normal "Saved recordings" / Drive-upload list.
 */
public final class UdsAddrScanLog {
    private static final String TAG = "UdsAddrScanLog";
    private static final Object LOCK = new Object();
    private static volatile String sessionFileName;

    private UdsAddrScanLog() {
    }

    private static String sessionFileName() {
        if (sessionFileName == null) {
            synchronized (LOCK) {
                if (sessionFileName == null) {
                    sessionFileName = "uds_addr_scan_log_"
                            + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
                }
            }
        }
        return sessionFileName;
    }

    /** {@code hits} is a semicolon-joined "0xNNN:positive/negative" list,
     * already formatted by the caller from the status JSON's results[]. */
    public static void append(Context context, String carId, int reqStart, int reqEnd, int offset,
                               String state, String currentReqHex, int resultCount, String hits) {
        synchronized (LOCK) {
            try {
                File dir = new File(context.getExternalFilesDir(null), "can-captures");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "could not create " + dir);
                    return;
                }
                File file = new File(dir, sessionFileName());
                boolean writeHeader = !file.exists();
                int currentReq = parseHex(currentReqHex, reqStart);
                boolean completed = "done".equals(state) && currentReq >= reqEnd;
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
                try (FileWriter writer = new FileWriter(file, true)) {
                    if (writeHeader) {
                        writer.write("timestamp,car,req_start,req_end,offset,final_current_req,"
                                + "result_count,state,completed,hits\n");
                    }
                    writer.write(String.format(Locale.US,
                            "%s,%s,0x%03X,0x%03X,0x%02X,0x%03X,%d,%s,%b,%s\n",
                            timestamp, csvSafe(carId), reqStart, reqEnd, offset, currentReq,
                            resultCount, state, completed, csvSafe(hits)));
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
