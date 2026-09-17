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
 * Appends a one-line summary of each completed/failed VWTP 2.0 connection-
 * setup sweep to a shared can-captures/vwtp_scan_log_*.csv -- same
 * one-file-per-app-run design as UdsScanLog/UdsAddrScanLog, for the
 * VWTP probe (BoardLink.startVwtpScan) instead of direct CAN-ID addressing.
 * Lives at the top of can-captures/ so it rides along with the normal
 * "Saved recordings" / Drive-upload list.
 */
public final class VwtpScanLog {
    private static final String TAG = "VwtpScanLog";
    private static final Object LOCK = new Object();
    private static volatile String sessionFileName;

    private VwtpScanLog() {
    }

    private static String sessionFileName() {
        if (sessionFileName == null) {
            synchronized (LOCK) {
                if (sessionFileName == null) {
                    sessionFileName = "vwtp_scan_log_"
                            + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
                }
            }
        }
        return sessionFileName;
    }

    /** {@code hits} is a semicolon-joined "0xNN:positive/negative(tx=0xNNN)"
     * list, already formatted by the caller from the status JSON's results[]. */
    public static void append(Context context, String carId, int addrStart, int addrEnd, int rxId,
                               String state, String currentAddrHex, int resultCount, String hits) {
        synchronized (LOCK) {
            try {
                File dir = new File(context.getExternalFilesDir(null), "can-captures");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "could not create " + dir);
                    return;
                }
                File file = new File(dir, sessionFileName());
                boolean writeHeader = !file.exists();
                int currentAddr = parseHex(currentAddrHex, addrStart);
                boolean completed = "done".equals(state) && currentAddr >= addrEnd;
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
                try (FileWriter writer = new FileWriter(file, true)) {
                    if (writeHeader) {
                        writer.write("timestamp,car,addr_start,addr_end,rx_id,final_current_addr,"
                                + "result_count,state,completed,hits\n");
                    }
                    writer.write(String.format(Locale.US,
                            "%s,%s,0x%02X,0x%02X,0x%03X,0x%02X,%d,%s,%b,%s\n",
                            timestamp, csvSafe(carId), addrStart, addrEnd, rxId, currentAddr,
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
