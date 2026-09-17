package com.sarusiy.cartheftguard.ui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;
import com.sarusiy.cartheftguard.CanCaptureService;
import com.sarusiy.cartheftguard.DriveUploader;
import com.sarusiy.cartheftguard.UdsAddrScanLog;
import com.sarusiy.cartheftguard.UdsScanLog;
import com.sarusiy.cartheftguard.UdsTargets;
import com.sarusiy.cartheftguard.UploadCleanup;
import com.sarusiy.cartheftguard.VwtpScanLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.List;

/** Starts and monitors raw CAN recording to an app-owned CSV file. */
public final class RecordFragment extends Fragment implements BoardLink.Listener {
    /** Same idea as LearnFragment's car selector, kept as an independent
     * selection/preference (own tab, own use case) -- without this,
     * standalone recordings all land flat in can-captures/ with no way to
     * tell later which vehicle they came from except by cross-referencing
     * timestamps and CAN ID content by hand. "General" (null id) preserves
     * the old flat-file behavior for bench/simulator use. */
    private static final class Car {
        final String id;
        final String label;

        Car(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final Car[] CARS = {
            new Car(null, "General (no car)"),
            new Car("fiat500_2014", "Fiat 500 (2014)"),
            new Car("fabia_2026", "Skoda Fabia (2026)"),
    };

    private static final String PREFS_NAME = "record_prefs";
    private static final String PREF_SELECTED_CAR = "selected_car";
    /* Index into UdsTargets.ALL, or -1 for "None" -- storing the array index
     * rather than the target object itself since Target has no separate id
     * field; stable as long as UdsTargets.ALL's order isn't reshuffled. */
    private static final String PREF_UDS_TARGET_INDEX = "uds_target_index";
    private static final String PREF_UDS_DID_START = "uds_did_start";
    private static final String PREF_UDS_DID_END = "uds_did_end";
    private static final String PREF_DRIVE_FOLDER_URI = "drive_folder_uri";

    private BoardLink boardLink;
    private Button startButton;
    private Button stopButton;
    /** Mirrors the statusReceiver's EXTRA_RUNNING -- lets the address-scan
     * and VWTP-scan Start buttons (which don't otherwise touch recording at
     * all) know whether a recording is already in flight before deciding to
     * auto-start one, without depending on button-enabled state as a proxy. */
    private boolean recordingActive;
    /** True only when the CURRENT recording was started by
     * ensureRecordingWithLabel (address scan / VWTP scan / deep scan), not
     * by the user tapping "Start recording" directly -- lets each scan's
     * poller auto-stop the recording it auto-started once the scan reaches
     * a terminal state, without ever auto-stopping a recording the user
     * started themselves. Reset to false both when a manual "Start
     * recording" begins and when any Stop happens (manual or automatic). */
    private boolean recordingAutoStartedByScan;
    private CheckBox passiveCheckBox;
    private TextView connectionText;
    private TextView countText;
    private TextView droppedText;
    private TextView fileText;
    private TextView capturesText;
    private LinearLayout uploadCheckboxColumn;
    private TextView uploadStatusText;
    private TextView driveFolderText;
    private List<File> uploadCandidateFiles = new ArrayList<>();
    private List<CheckBox> uploadCheckboxes = new ArrayList<>();
    /** Uri of the Drive folder the user picked via the system document-tree
     * picker (see chooseDriveFolderLauncher) -- null until they've chosen
     * one. Persisted so it survives app restarts; the OS grants persistable
     * read/write permission on it once, at pick time. */
    private Uri driveFolderUri;
    private final ActivityResultLauncher<Uri> chooseDriveFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::onDriveFolderChosen);
    private LinearLayout carButtonsRow;
    private String selectedCarId;
    private boolean receiverRegistered;

    /** null = no UDS scan alongside this recording (default -- opt-in, since
     * not every recording is meant to probe a body module). */
    private UdsTargets.Target selectedUdsTarget;
    private int persistedUdsDidStart;
    private int persistedUdsDidEnd;
    private LinearLayout udsTargetButtonsColumn;
    private EditText udsDidStartInput;
    private EditText udsDidEndInput;
    private TextView udsEstimateText;
    private TextView udsStatusText;
    private static final int UDS_POLL_INTERVAL_MS = 1000;
    private final Handler udsPollHandler = new Handler(Looper.getMainLooper());
    /** Snapshot of the scan actually sent -- kept separate from
     * selectedUdsTarget/the DID EditTexts so the eventual log entry
     * (UdsScanLog) reflects what was really swept even if the user edits
     * those fields again before the scan finishes. */
    private UdsTargets.Target activeUdsTarget;
    private int activeUdsDidStart;
    private int activeUdsDidEnd;
    private final Runnable udsPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) {
                return;
            }
            boardLink.fetchUdsScanStatus(json -> {
                if (!isAdded() || udsStatusText == null) {
                    return;
                }
                if (json == null) {
                    udsStatusText.setText("UDS scan: status unavailable");
                    return;
                }
                try {
                    JSONObject status = new JSONObject(json);
                    String state = status.optString("state", "idle");
                    int resultCount = status.optInt("result_count", 0);
                    String currentDid = status.optString("current_did", "");
                    String session = status.optString("session", "none");
                    if ("running".equals(state)) {
                        udsStatusText.setText("UDS scan: running (session " + session + ", probing " + currentDid
                                + ", " + resultCount + " hit(s) so far) -- keep recording until this finishes");
                        udsPollHandler.postDelayed(this, UDS_POLL_INTERVAL_MS);
                    } else if ("done".equals(state)) {
                        udsStatusText.setText((resultCount == 0
                                ? "UDS scan: done, no DID responded in this range"
                                : "UDS scan: done, " + resultCount + " DID(s) responded -- see " + json)
                                + " (session: " + session + ")");
                        logActiveScan(state, currentDid, resultCount, session);
                    } else if ("error".equals(state)) {
                        udsStatusText.setText("UDS scan: failed (board is in Passive mode?)");
                        logActiveScan(state, currentDid, resultCount, session);
                    } else if (activeUdsTarget != null) {
                        /* Same reset-detection reasoning as addrScanPollRunnable
                         * below -- an unmatched state (firmware default "idle")
                         * while we were still expecting running/done/error means
                         * the board rebooted (e.g. a brownout) and silently lost
                         * the scan, instead of the UI freezing on stale text. */
                        udsStatusText.setText("UDS scan: board appears to have reset "
                                + "(lost scan progress) -- check its connection and try again.");
                        activeUdsTarget = null;
                    }
                } catch (JSONException exception) {
                    udsStatusText.setText("UDS scan: malformed status response");
                }
            });
        }

        private void logActiveScan(String state, String currentDid, int resultCount, String session) {
            if (activeUdsTarget == null || !isAdded()) {
                return;
            }
            UdsScanLog.append(requireContext(), selectedCarId, "record", activeUdsTarget,
                    activeUdsDidStart, activeUdsDidEnd, state, currentDid, resultCount, session);
            activeUdsTarget = null;
        }
    };

    /** Address-discovery sweep -- separate from the DID-sweep controls
     * above: probes candidate request IDs directly (see
     * BoardLink.startUdsAddrScan) instead of assuming a specific module
     * address, since the 2026-09-15 real-car test found the Gateway
     * answers (session:positive) while every guessed body-module address
     * timed out -- the mechanism works, the guessed addresses are the
     * likely problem. Fixed response offset of 0x6A matches every
     * candidate address pair researched so far. */
    private EditText addrScanStartInput;
    private EditText addrScanEndInput;
    private TextView addrScanStatusText;
    private static final int ADDR_SCAN_RESPONSE_OFFSET = 0x6A;
    private int activeAddrScanStart;
    private int activeAddrScanEnd;
    private boolean addrScanActive;
    private final Runnable addrScanPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) {
                return;
            }
            boardLink.fetchUdsAddrScanStatus(json -> {
                if (!isAdded() || addrScanStatusText == null) {
                    return;
                }
                if (json == null) {
                    addrScanStatusText.setText("Address scan: status unavailable");
                    return;
                }
                try {
                    JSONObject status = new JSONObject(json);
                    String state = status.optString("state", "idle");
                    int resultCount = status.optInt("result_count", 0);
                    String currentReq = status.optString("current_req", "");
                    if ("running".equals(state)) {
                        addrScanStatusText.setText("Address scan: running (probing " + currentReq + ", "
                                + resultCount + " hit(s) so far)");
                        udsPollHandler.postDelayed(this, UDS_POLL_INTERVAL_MS);
                    } else if ("done".equals(state) || "error".equals(state)) {
                        StringBuilder hits = new StringBuilder();
                        JSONArray results = status.optJSONArray("results");
                        if (results != null) {
                            for (int i = 0; i < results.length(); i++) {
                                JSONObject hit = results.optJSONObject(i);
                                if (hit == null) {
                                    continue;
                                }
                                if (hits.length() > 0) {
                                    hits.append("; ");
                                }
                                hits.append(hit.optString("req", "?")).append(" (")
                                        .append(hit.optString("session", "?")).append(")");
                            }
                        }
                        addrScanStatusText.setText("error".equals(state)
                                ? "Address scan: failed (board is in Passive mode?)"
                                : (resultCount == 0
                                        ? "Address scan: done, no address responded in this range"
                                        : "Address scan: done, " + resultCount + " address(es) responded -- "
                                                + hits));
                        if (addrScanActive) {
                            UdsAddrScanLog.append(requireContext(), selectedCarId, activeAddrScanStart,
                                    activeAddrScanEnd, ADDR_SCAN_RESPONSE_OFFSET, state, currentReq,
                                    resultCount, hits.toString());
                            addrScanActive = false;
                        }
                        stopRecordingIfAutoStarted();
                    } else if (addrScanActive) {
                        /* Any other state (the firmware's own default is
                         * "idle") while we were still expecting "running" or
                         * a terminal state means the board's in-memory scan
                         * state got wiped without us seeing a done/error --
                         * the one thing that does that is a reboot (e.g. the
                         * brownout resets found 2026-09-15 debugging this
                         * exact symptom: a reset silently returns the board
                         * to idle, and without this branch the UI just froze
                         * forever on the last "running" text instead of
                         * reporting what actually happened). */
                        addrScanStatusText.setText("Address scan: board appears to have reset "
                                + "(lost scan progress) -- check its connection and try again.");
                        addrScanActive = false;
                        stopRecordingIfAutoStarted();
                    }
                } catch (JSONException exception) {
                    addrScanStatusText.setText("Address scan: malformed status response");
                }
            });
        }
    };

    private void startAddrScan() {
        int start = parseHexOrDefault(addrScanStartInput, 0x700);
        int end = parseHexOrDefault(addrScanEndInput, 0x7FF);
        if (end < start) {
            addrScanStatusText.setText("Range end must be >= start.");
            return;
        }
        activeAddrScanStart = start;
        activeAddrScanEnd = end;
        addrScanActive = true;
        addrScanStatusText.setText("Address scan: starting 0x" + Integer.toHexString(start)
                + "-0x" + Integer.toHexString(end) + "...");
        ensureRecordingWithLabel("addrscan-" + Integer.toHexString(start) + "-" + Integer.toHexString(end));
        udsPollHandler.removeCallbacksAndMessages(null);
        boardLink.startUdsAddrScan(start, end, ADDR_SCAN_RESPONSE_OFFSET, started -> {
            if (started) {
                udsPollHandler.postDelayed(addrScanPollRunnable, UDS_POLL_INTERVAL_MS);
            } else {
                addrScanStatusText.setText("Address scan: failed to start");
                addrScanActive = false;
            }
        });
    }

    /** VWTP 2.0 connection-setup probe -- a different addressing scheme
     * from the direct address sweep above: candidates are single-byte
     * logical module addresses (VCDS-style numbers), not raw CAN IDs, and
     * every request goes to a fixed CAN ID (0x200) rather than a per-
     * candidate one. Added 2026-09-16 after the full 0x700-0x7FF sweep
     * found only the Gateway reachable -- see BoardLink.startVwtpScan's
     * doc comment and UDS_BODY_MODULE_RESEARCH.md. */
    private EditText vwtpAddrStartInput;
    private EditText vwtpAddrEndInput;
    private EditText vwtpRxIdInput;
    private TextView vwtpScanStatusText;
    private int activeVwtpAddrStart;
    private int activeVwtpAddrEnd;
    private int activeVwtpRxId;
    private boolean vwtpScanActive;
    private final Runnable vwtpScanPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) {
                return;
            }
            boardLink.fetchVwtpScanStatus((json, reason) -> {
                if (!isAdded() || vwtpScanStatusText == null) {
                    return;
                }
                if (json == null) {
                    /* Unlike before the NVS-backed resume was added
                     * (2026-09-17), this no longer means the scan is dead --
                     * the board resumes on its own after a reset, same
                     * reasoning as deepScanPollRunnable's null-json branch.
                     * Keep polling instead of giving up. */
                    if (vwtpScanActive) {
                        vwtpScanStatusText.setText("VWTP scan: board unreachable (" + reason
                                + ") -- it resumes on its own once back, still watching...");
                        udsPollHandler.postDelayed(this, UDS_POLL_INTERVAL_MS);
                    } else {
                        vwtpScanStatusText.setText("VWTP scan: status unavailable -- " + reason);
                    }
                    return;
                }
                try {
                    JSONObject status = new JSONObject(json);
                    String state = status.optString("state", "idle");
                    int resultCount = status.optInt("result_count", 0);
                    String currentAddr = status.optString("current_addr", "");
                    if ("running".equals(state)) {
                        vwtpScanStatusText.setText("VWTP scan: running (probing " + currentAddr + ", "
                                + resultCount + " hit(s) so far)");
                        udsPollHandler.postDelayed(this, UDS_POLL_INTERVAL_MS);
                    } else if ("done".equals(state) || "error".equals(state)) {
                        StringBuilder hits = new StringBuilder();
                        JSONArray results = status.optJSONArray("results");
                        if (results != null) {
                            for (int i = 0; i < results.length(); i++) {
                                JSONObject hit = results.optJSONObject(i);
                                if (hit == null) {
                                    continue;
                                }
                                if (hits.length() > 0) {
                                    hits.append("; ");
                                }
                                hits.append(hit.optString("addr", "?")).append(" (")
                                        .append(hit.optString("session", "?")).append(", tx=")
                                        .append(hit.optString("tx_id", "?")).append(")");
                            }
                        }
                        vwtpScanStatusText.setText("error".equals(state)
                                ? "VWTP scan: failed (board is in Passive mode?)"
                                : (resultCount == 0
                                        ? "VWTP scan: done, no address responded in this range"
                                        : "VWTP scan: done, " + resultCount + " address(es) responded -- "
                                                + hits));
                        if (vwtpScanActive) {
                            VwtpScanLog.append(requireContext(), selectedCarId, activeVwtpAddrStart,
                                    activeVwtpAddrEnd, activeVwtpRxId, state, currentAddr,
                                    resultCount, hits.toString());
                            vwtpScanActive = false;
                        }
                        stopRecordingIfAutoStarted();
                    } else if (vwtpScanActive) {
                        /* With NVS-backed resume in place, a genuine reset
                         * should show up as a status-fetch failure (handled
                         * above) followed by the scan coming back RUNNING on
                         * its own -- reaching this "idle while we expected
                         * running" case now more likely means something else
                         * went wrong (e.g. a fresh scan started from a
                         * different client) than a simple reset. */
                        vwtpScanStatusText.setText("VWTP scan: unexpected idle state "
                                + "(lost scan progress) -- check its connection and try again.");
                        vwtpScanActive = false;
                        stopRecordingIfAutoStarted();
                    }
                } catch (JSONException exception) {
                    vwtpScanStatusText.setText("VWTP scan: malformed status response");
                }
            });
        }
    };

    private void startVwtpScan() {
        int start = parseHexOrDefault(vwtpAddrStartInput, 0x00);
        int end = parseHexOrDefault(vwtpAddrEndInput, 0xFF);
        int rxId = parseHexOrDefault(vwtpRxIdInput, 0x300);
        if (end < start || end > 0xFF) {
            vwtpScanStatusText.setText("Range end must be >= start and <= 0xFF.");
            return;
        }
        activeVwtpAddrStart = start;
        activeVwtpAddrEnd = end;
        activeVwtpRxId = rxId;
        vwtpScanActive = true;
        vwtpScanStatusText.setText("VWTP scan: starting 0x" + Integer.toHexString(start)
                + "-0x" + Integer.toHexString(end) + "...");
        ensureRecordingWithLabel("vwtp-" + Integer.toHexString(start) + "-" + Integer.toHexString(end));
        udsPollHandler.removeCallbacksAndMessages(null);
        boardLink.startVwtpScan(start, end, rxId, (started, reason) -> {
            if (started) {
                udsPollHandler.postDelayed(vwtpScanPollRunnable, UDS_POLL_INTERVAL_MS);
            } else {
                vwtpScanStatusText.setText("VWTP scan: failed to start -- " + reason);
                vwtpScanActive = false;
            }
        });
    }

    /** Deep scan -- see BoardLink.startDeepScan's doc comment. Unlike every
     * other poller on this screen, a failed status fetch here does NOT stop
     * polling: the whole point of this feature is that the firmware keeps
     * (or resumes) the scan on its own across a brownout reset, so a
     * transient "board unreachable" is expected mid-run, not a reason to
     * give up watching. */
    private EditText deepScanStartInput;
    private EditText deepScanEndInput;
    private TextView deepScanStatusText;
    private final Runnable deepScanPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) {
                return;
            }
            boardLink.fetchDeepScanStatus((json, reason) -> {
                if (!isAdded() || deepScanStatusText == null) {
                    return;
                }
                if (json == null) {
                    deepScanStatusText.setText("Deep scan: board unreachable (" + reason
                            + ") -- it resumes on its own once back, still watching...");
                    udsPollHandler.postDelayed(this, UDS_POLL_INTERVAL_MS);
                    return;
                }
                try {
                    JSONObject status = new JSONObject(json);
                    String state = status.optString("state", "idle");
                    int hitCount = status.optInt("hit_count", 0);
                    String currentReq = status.optString("current_req", "");
                    if ("running".equals(state)) {
                        deepScanStatusText.setText("Deep scan: running (probing " + currentReq + ", "
                                + hitCount + " module(s) identified so far)\n" + summarizeDeepScanHits(status));
                        udsPollHandler.postDelayed(this, UDS_POLL_INTERVAL_MS);
                    } else if ("done".equals(state)) {
                        deepScanStatusText.setText((hitCount == 0
                                ? "Deep scan: done, no address responded in this range"
                                : "Deep scan: done, " + hitCount + " module(s) identified") + "\n"
                                + summarizeDeepScanHits(status));
                        stopRecordingIfAutoStarted();
                    } else if ("error".equals(state)) {
                        deepScanStatusText.setText("Deep scan: failed (board is in Passive mode?)");
                        stopRecordingIfAutoStarted();
                    }
                    /* "idle" -- nothing has ever been started, or this is
                     * refreshDeepScanStatusOnce() on a fresh view build with
                     * no scan running; leave whatever text is already there
                     * (blank on first build) rather than overwriting it. */
                } catch (JSONException exception) {
                    deepScanStatusText.setText("Deep scan: malformed status response");
                }
            });
        }
    };

    private String summarizeDeepScanHits(JSONObject status) {
        JSONArray hits = status.optJSONArray("hits");
        if (hits == null || hits.length() == 0) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append("\n");
            }
            summary.append(hit.optString("addr", "?")).append(" (").append(hit.optString("session", "?")).append(")");
            JSONArray didResults = hit.optJSONArray("did_results");
            if (didResults != null) {
                for (int d = 0; d < didResults.length(); d++) {
                    JSONObject didResult = didResults.optJSONObject(d);
                    if (didResult == null) {
                        continue;
                    }
                    String hex = didResult.optString("data", "");
                    summary.append("\n    ").append(didResult.optString("did", "?"))
                            .append(": ").append(hex).append(" (").append(hexToAscii(hex)).append(")");
                }
            }
        }
        return summary.toString();
    }

    /** Best-effort readable rendering of a hex byte string -- identification
     * DIDs (part numbers, system names) are frequently plain ASCII, so
     * showing this alongside the raw hex saves manually decoding it. */
    private String hexToAscii(String hex) {
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            try {
                int value = Integer.parseInt(hex.substring(i, i + 2), 16);
                ascii.append(value >= 0x20 && value < 0x7F ? (char) value : '.');
            } catch (NumberFormatException exception) {
                ascii.append('.');
            }
        }
        return ascii.toString();
    }

    private void startDeepScan() {
        int start = parseHexOrDefault(deepScanStartInput, 0x700);
        int end = parseHexOrDefault(deepScanEndInput, 0x7FF);
        if (end < start) {
            deepScanStatusText.setText("Range end must be >= start.");
            return;
        }
        deepScanStatusText.setText("Deep scan: starting 0x" + Integer.toHexString(start)
                + "-0x" + Integer.toHexString(end) + "...");
        ensureRecordingWithLabel("deepscan-" + Integer.toHexString(start) + "-" + Integer.toHexString(end));
        udsPollHandler.removeCallbacksAndMessages(null);
        boardLink.startDeepScan(start, end, ADDR_SCAN_RESPONSE_OFFSET, false, (started, reason) -> {
            if (started) {
                udsPollHandler.postDelayed(deepScanPollRunnable, UDS_POLL_INTERVAL_MS);
            } else {
                deepScanStatusText.setText("Deep scan: failed to start -- " + reason);
            }
        });
    }

    /** Called once when this view is built (not on a timer) -- a deep scan
     * may already be running (or mid-resume after a reset) from before this
     * screen was even opened, since it's designed to run unattended. If so,
     * start polling immediately instead of showing a blank status until the
     * user taps Start again. */
    private void refreshDeepScanStatusOnce() {
        boardLink.fetchDeepScanStatus((json, reason) -> {
            if (!isAdded() || deepScanStatusText == null || json == null) {
                return;
            }
            try {
                String state = new JSONObject(json).optString("state", "idle");
                if ("running".equals(state)) {
                    udsPollHandler.postDelayed(deepScanPollRunnable, UDS_POLL_INTERVAL_MS);
                }
            } catch (JSONException ignored) {
                /* Leave status blank -- next explicit Start will report any real problem. */
            }
        });
    }

    /** Live raw-frame tail: independent of CanCaptureService/CSV recording --
     * both just poll the same GET /api/can, so you can watch live without
     * needing to be actively recording to a file, and vice versa. Kept
     * intentionally simple (last LIVE_MAX_LINES frames, most recent last) so
     * it stays readable while you're also doing something physical in the
     * car, rather than a full unbounded log. */
    private static final int LIVE_POLL_INTERVAL_MS = 300;
    private static final int LIVE_MAX_LINES = 60;
    private TextView liveText;
    private CheckBox liveCheckBox;
    private long liveAfterSeq;
    private final Deque<String> liveLines = new ArrayDeque<>();
    private final Handler liveHandler = new Handler(Looper.getMainLooper());
    private final Runnable livePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded() && liveCheckBox != null && liveCheckBox.isChecked()) {
                boardLink.fetchCanRaw(liveAfterSeq, RecordFragment.this::onLiveFrames);
            }
            if (isAdded()) {
                liveHandler.postDelayed(this, LIVE_POLL_INTERVAL_MS);
            }
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean running = intent.getBooleanExtra(CanCaptureService.EXTRA_RUNNING, false);
            recordingActive = running;
            long frames = intent.getLongExtra(CanCaptureService.EXTRA_FRAMES, 0);
            long dropped = intent.getLongExtra(CanCaptureService.EXTRA_DROPPED, 0);
            String file = intent.getStringExtra(CanCaptureService.EXTRA_FILE);
            String error = intent.getStringExtra(CanCaptureService.EXTRA_ERROR);
            startButton.setEnabled(!running && boardLink.isWifiReady());
            stopButton.setEnabled(running);
            passiveCheckBox.setEnabled(!running);
            countText.setText("Frames recorded: " + frames);
            droppedText.setText("Frames dropped or overflowed: " + dropped);
            if (file != null && !file.isEmpty()) {
                fileText.setText("File: " + file);
            }
            if (error != null && !error.isEmpty()) {
                connectionText.setText("Recorder error: " + error);
            } else if (running) {
                connectionText.setText("Recording from " + boardLink.getBoardIp());
            } else {
                updateConnectionState();
            }
        }
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        boardLink = BoardLink.getInstance(context);
        selectedCarId = prefs(context).getString(PREF_SELECTED_CAR, null);
        int udsTargetIndex = prefs(context).getInt(PREF_UDS_TARGET_INDEX, -1);
        selectedUdsTarget = (udsTargetIndex >= 0 && udsTargetIndex < UdsTargets.ALL.length)
                ? UdsTargets.ALL[udsTargetIndex] : null;
        persistedUdsDidStart = prefs(context).getInt(PREF_UDS_DID_START, UdsTargets.DEFAULT_DID_START);
        persistedUdsDidEnd = prefs(context).getInt(PREF_UDS_DID_END, UdsTargets.DEFAULT_DID_END);
        String savedFolderUri = prefs(context).getString(PREF_DRIVE_FOLDER_URI, null);
        driveFolderUri = savedFolderUri != null ? Uri.parse(savedFolderUri) : null;
    }

    private void onDriveFolderChosen(Uri uri) {
        if (uri == null || !isAdded()) {
            return;
        }
        requireContext().getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        driveFolderUri = uri;
        prefs(requireContext()).edit().putString(PREF_DRIVE_FOLDER_URI, uri.toString()).apply();
        updateDriveFolderText();
    }

    private void updateDriveFolderText() {
        if (driveFolderText == null) {
            return;
        }
        if (driveFolderUri == null) {
            driveFolderText.setText("No Drive folder chosen yet.");
            return;
        }
        DocumentFile folder = DocumentFile.fromTreeUri(requireContext(), driveFolderUri);
        String name = folder != null ? folder.getName() : null;
        driveFolderText.setText("Drive folder: " + (name != null ? name : driveFolderUri));
    }

    private SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Views.dp(context, 20);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xfff5f2ea);

        root.addView(Views.label(context, "CAN Recorder", 24, true), Views.matchWrap());
        connectionText = Views.label(context, "", 14, false);
        root.addView(connectionText, Views.matchWrapTop(context, 12));

        root.addView(Views.label(context, "Recording for", 16, true), Views.matchWrapTop(context, 16));
        carButtonsRow = new LinearLayout(context);
        carButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(carButtonsRow, Views.matchWrapTop(context, 6));
        TextView carNote = Views.label(context,
                "Tags the file's folder so you can tell vehicles apart later -- pick \"General\" for bench/simulator use.",
                11, false);
        carNote.setTextColor(0xff52616b);
        root.addView(carNote, Views.matchWrapTop(context, 2));

        passiveCheckBox = new CheckBox(context);
        passiveCheckBox.setText("Passive listen-only (real vehicle)");
        passiveCheckBox.setTextColor(0xff1f2933);
        passiveCheckBox.setChecked(false);
        root.addView(passiveCheckBox, Views.matchWrapTop(context, 16));
        TextView modeNote = Views.label(context,
                "Active (unchecked, recommended) also ACKs frames on the bus, which avoids a real "
                        + "distortion Passive mode causes -- see FABIA_ANALYSIS.md. Passive adds no traffic "
                        + "of its own but sees less real traffic as a result.",
                12, false);
        modeNote.setTextColor(0xff52616b);
        root.addView(modeNote, Views.matchWrapTop(context, 2));

        root.addView(Views.label(context, "UDS body-module scan (optional)", 16, true), Views.matchWrapTop(context, 20));
        root.addView(Views.label(context, "Pick a candidate module (research: UDS_BODY_MODULE_RESEARCH.md) to also "
                        + "sweep for DIDs while this recording runs -- toggle the matching real action (lock, door, "
                        + "etc.) while it's probing. Requires Active mode (uncheck Passive above).", 12, false),
                Views.matchWrapTop(context, 4));
        udsTargetButtonsColumn = new LinearLayout(context);
        udsTargetButtonsColumn.setOrientation(LinearLayout.VERTICAL);
        root.addView(udsTargetButtonsColumn, Views.matchWrapTop(context, 8));
        refreshUdsTargetButtons();

        LinearLayout didRow = new LinearLayout(context);
        didRow.setOrientation(LinearLayout.HORIZONTAL);
        udsDidStartInput = Views.input(context, "DID start (hex)", android.text.InputType.TYPE_CLASS_TEXT);
        udsDidStartInput.setText(Integer.toHexString(persistedUdsDidStart).toUpperCase(java.util.Locale.US));
        didRow.addView(udsDidStartInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        udsDidEndInput = Views.input(context, "DID end (hex)", android.text.InputType.TYPE_CLASS_TEXT);
        udsDidEndInput.setText(Integer.toHexString(persistedUdsDidEnd).toUpperCase(java.util.Locale.US));
        LinearLayout.LayoutParams didEndParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        didEndParams.leftMargin = Views.dp(context, 8);
        didRow.addView(udsDidEndInput, didEndParams);
        root.addView(didRow, Views.matchWrapTop(context, 8));

        TextWatcher didRangeWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateUdsEstimate();
            }
        };
        udsDidStartInput.addTextChangedListener(didRangeWatcher);
        udsDidEndInput.addTextChangedListener(didRangeWatcher);

        udsEstimateText = Views.label(context, "", 12, false);
        udsEstimateText.setTextColor(0xff52616b);
        root.addView(udsEstimateText, Views.matchWrapTop(context, 4));
        udsStatusText = Views.label(context, "", 12, false);
        udsStatusText.setTextColor(0xff0b6e69);
        root.addView(udsStatusText, Views.matchWrapTop(context, 4));
        updateUdsEstimate();

        root.addView(Views.label(context, "UDS address discovery (find real module addresses)", 16, true),
                Views.matchWrapTop(context, 20));
        root.addView(Views.label(context,
                        "Don't know the right address for a module? Probe a range of request IDs directly -- "
                                + "each one gets a quick session-control check (response = request + 0x6A). "
                                + "Independent of the target buttons/DID range above; runs on its own, not tied "
                                + "to Start recording.",
                        12, false),
                Views.matchWrapTop(context, 4));
        LinearLayout addrScanRow = new LinearLayout(context);
        addrScanRow.setOrientation(LinearLayout.HORIZONTAL);
        addrScanStartInput = Views.input(context, "Start (hex)", android.text.InputType.TYPE_CLASS_TEXT);
        addrScanStartInput.setText("700");
        addrScanRow.addView(addrScanStartInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addrScanEndInput = Views.input(context, "End (hex)", android.text.InputType.TYPE_CLASS_TEXT);
        addrScanEndInput.setText("7FF");
        LinearLayout.LayoutParams addrEndParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        addrEndParams.leftMargin = Views.dp(context, 8);
        addrScanRow.addView(addrScanEndInput, addrEndParams);
        root.addView(addrScanRow, Views.matchWrapTop(context, 8));
        Button addrScanButton = Views.secondaryButton(context, "Start address scan");
        addrScanButton.setOnClickListener(view -> startAddrScan());
        root.addView(addrScanButton, Views.matchHeightTop(context, 46, 8));
        addrScanStatusText = Views.label(context, "", 12, false);
        addrScanStatusText.setTextColor(0xff0b6e69);
        root.addView(addrScanStatusText, Views.matchWrapTop(context, 4));

        root.addView(Views.label(context, "VWTP 2.0 connection probe (older routing scheme)", 16, true),
                Views.matchWrapTop(context, 20));
        root.addView(Views.label(context,
                        "Direct address scan above found only the Gateway -- body modules may need this "
                                + "older, session-based scheme instead: requests always go to CAN ID 0x200 with "
                                + "a single-byte logical module address (VCDS-style numbers), not a raw CAN ID "
                                + "per module. Independent of everything else on this screen.",
                        12, false),
                Views.matchWrapTop(context, 4));
        LinearLayout vwtpAddrRow = new LinearLayout(context);
        vwtpAddrRow.setOrientation(LinearLayout.HORIZONTAL);
        vwtpAddrStartInput = Views.input(context, "Start (hex)", android.text.InputType.TYPE_CLASS_TEXT);
        vwtpAddrStartInput.setText("00");
        vwtpAddrRow.addView(vwtpAddrStartInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        vwtpAddrEndInput = Views.input(context, "End (hex)", android.text.InputType.TYPE_CLASS_TEXT);
        vwtpAddrEndInput.setText("FF");
        LinearLayout.LayoutParams vwtpEndParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        vwtpEndParams.leftMargin = Views.dp(context, 8);
        vwtpAddrRow.addView(vwtpAddrEndInput, vwtpEndParams);
        root.addView(vwtpAddrRow, Views.matchWrapTop(context, 8));
        vwtpRxIdInput = Views.input(context, "Our RX id (hex, e.g. 300)", android.text.InputType.TYPE_CLASS_TEXT);
        vwtpRxIdInput.setText("300");
        root.addView(vwtpRxIdInput, Views.matchWrapTop(context, 8));
        Button vwtpScanButton = Views.secondaryButton(context, "Start VWTP scan");
        vwtpScanButton.setOnClickListener(view -> startVwtpScan());
        root.addView(vwtpScanButton, Views.matchHeightTop(context, 46, 8));
        vwtpScanStatusText = Views.label(context, "", 12, false);
        vwtpScanStatusText.setTextColor(0xff0b6e69);
        root.addView(vwtpScanStatusText, Views.matchWrapTop(context, 4));

        root.addView(Views.label(context, "Deep scan (unattended, address + auto-identify)", 16, true),
                Views.matchWrapTop(context, 20));
        root.addView(Views.label(context,
                        "Combines the address scan above with an automatic identification-DID sweep "
                                + "(0xF180-0xF1A0) against every address that responds -- no need to notice a hit "
                                + "and manually re-scan it. Meant to run unattended: the board persists progress "
                                + "and resumes on its own after a reset (e.g. a brownout), so it keeps going even "
                                + "if this screen disconnects or the app closes. Independent of everything else "
                                + "on this screen.",
                        12, false),
                Views.matchWrapTop(context, 4));
        LinearLayout deepScanRow = new LinearLayout(context);
        deepScanRow.setOrientation(LinearLayout.HORIZONTAL);
        deepScanStartInput = Views.input(context, "Start (hex)", android.text.InputType.TYPE_CLASS_TEXT);
        deepScanStartInput.setText("700");
        deepScanRow.addView(deepScanStartInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        deepScanEndInput = Views.input(context, "End (hex)", android.text.InputType.TYPE_CLASS_TEXT);
        deepScanEndInput.setText("7FF");
        LinearLayout.LayoutParams deepScanEndParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        deepScanEndParams.leftMargin = Views.dp(context, 8);
        deepScanRow.addView(deepScanEndInput, deepScanEndParams);
        root.addView(deepScanRow, Views.matchWrapTop(context, 8));
        Button deepScanButton = Views.secondaryButton(context, "Start deep scan");
        deepScanButton.setOnClickListener(view -> startDeepScan());
        root.addView(deepScanButton, Views.matchHeightTop(context, 46, 8));
        deepScanStatusText = Views.label(context, "", 12, false);
        deepScanStatusText.setTextColor(0xff0b6e69);
        root.addView(deepScanStatusText, Views.matchWrapTop(context, 4));
        refreshDeepScanStatusOnce();

        startButton = Views.primaryButton(context, "Start recording");
        startButton.setOnClickListener(view -> startRecording());
        root.addView(startButton, Views.matchHeightTop(context, 52, 20));

        stopButton = Views.secondaryButton(context, "Stop recording");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(view -> stopRecording());
        root.addView(stopButton, Views.matchHeightTop(context, 48, 10));

        countText = Views.label(context, "Frames recorded: 0", 16, true);
        root.addView(countText, Views.matchWrapTop(context, 24));
        droppedText = Views.label(context, "Frames dropped or overflowed: 0", 14, false);
        root.addView(droppedText, Views.matchWrapTop(context, 8));
        fileText = Views.label(context, "File: not started", 12, false);
        fileText.setTextIsSelectable(true);
        root.addView(fileText, Views.matchWrapTop(context, 12));

        TextView note = Views.label(context,
                "Recordings are CSV files in the app's external files/can-captures directory. "
                        + "The raw CAN IDs and payloads remain unchanged for later analysis.",
                12, false);
        note.setTextColor(0xff52616b);
        root.addView(note, Views.matchWrapTop(context, 20));

        root.addView(Views.label(context, "Saved recordings", 16, true),
                Views.matchWrapTop(context, 24));
        LinearLayout captureButtonsRow = new LinearLayout(context);
        captureButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        Button refreshButton = Views.secondaryButton(context, "Refresh recordings");
        refreshButton.setOnClickListener(view -> refreshCaptureList());
        captureButtonsRow.addView(refreshButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button clearAllButton = Views.secondaryButton(context, "Clear all recordings");
        clearAllButton.setOnClickListener(view -> confirmClearAllRecordings());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clearParams.leftMargin = Views.dp(context, 8);
        captureButtonsRow.addView(clearAllButton, clearParams);
        root.addView(captureButtonsRow, Views.matchHeightTop(context, 46, 8));
        capturesText = Views.label(context, "", 12, false);
        capturesText.setTextIsSelectable(true);
        root.addView(capturesText, Views.matchWrapTop(context, 8));

        root.addView(Views.label(context, "Upload to Drive", 16, true), Views.matchWrapTop(context, 24));
        TextView uploadNote = Views.label(context,
                "Pick a Drive folder once (uses your phone's own Drive app/account via the system file picker), "
                        + "then select recordings below and copy them there -- no USB/adb needed to get captures "
                        + "off the phone for analysis.",
                12, false);
        uploadNote.setTextColor(0xff52616b);
        root.addView(uploadNote, Views.matchWrapTop(context, 4));
        Button chooseDriveFolderButton = Views.secondaryButton(context, "Choose Drive folder");
        chooseDriveFolderButton.setOnClickListener(view -> chooseDriveFolderLauncher.launch(null));
        root.addView(chooseDriveFolderButton, Views.matchHeightTop(context, 46, 8));
        driveFolderText = Views.label(context, "", 12, false);
        root.addView(driveFolderText, Views.matchWrapTop(context, 4));
        uploadCheckboxColumn = new LinearLayout(context);
        uploadCheckboxColumn.setOrientation(LinearLayout.VERTICAL);
        root.addView(uploadCheckboxColumn, Views.matchWrapTop(context, 12));
        Button uploadSelectedButton = Views.secondaryButton(context, "Upload selected");
        uploadSelectedButton.setOnClickListener(view -> uploadSelectedRecordings());
        root.addView(uploadSelectedButton, Views.matchHeightTop(context, 46, 8));
        uploadStatusText = Views.label(context, "", 12, false);
        root.addView(uploadStatusText, Views.matchWrapTop(context, 4));

        LinearLayout liveHeader = new LinearLayout(context);
        liveHeader.setOrientation(LinearLayout.HORIZONTAL);
        liveHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        liveHeader.addView(Views.label(context, "Live raw frames", 16, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button clearLiveButton = Views.secondaryButton(context, "Clear");
        clearLiveButton.setOnClickListener(view -> clearLiveFrames());
        liveHeader.addView(clearLiveButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(liveHeader, Views.matchWrapTop(context, 24));
        liveCheckBox = new CheckBox(context);
        liveCheckBox.setText("Show live (independent of recording -- for watching while you act on the car)");
        liveCheckBox.setTextColor(0xff1f2933);
        liveCheckBox.setChecked(true);
        root.addView(liveCheckBox, Views.matchWrapTop(context, 4));
        TextView liveNote = Views.label(context,
                "Newest at the bottom. Last " + LIVE_MAX_LINES + " frames shown; full history is only in the CSV.",
                11, false);
        liveNote.setTextColor(0xff52616b);
        root.addView(liveNote, Views.matchWrapTop(context, 2));
        liveText = new TextView(context);
        liveText.setTextSize(11);
        liveText.setTypeface(android.graphics.Typeface.MONOSPACE);
        liveText.setTextColor(0xff1f2933);
        liveText.setBackgroundColor(0xffffffff);
        liveText.setPadding(Views.dp(context, 8), Views.dp(context, 8), Views.dp(context, 8), Views.dp(context, 8));
        liveText.setTextIsSelectable(true);
        LinearLayout.LayoutParams liveParams = Views.matchWrapTop(context, 8);
        liveText.setMinHeight(Views.dp(context, 220));
        root.addView(liveText, liveParams);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        updateConnectionState();
        refreshCaptureList();
        updateDriveFolderText();
        refreshCarButtons();
        return scroll;
    }

    @Override
    public void onStart() {
        super.onStart();
        boardLink.addListener(this);
        IntentFilter filter = new IntentFilter(CanCaptureService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
        updateConnectionState();
        liveHandler.post(livePollRunnable);
    }

    @Override
    public void onStop() {
        liveHandler.removeCallbacks(livePollRunnable);
        udsPollHandler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            requireContext().unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        boardLink.removeListener(this);
        super.onStop();
    }

    private void onLiveFrames(String json) {
        try {
            JSONObject data = new JSONObject(json);
            JSONArray frames = data.optJSONArray("frames");
            if (frames == null || frames.length() == 0) {
                return;
            }
            for (int i = 0; i < frames.length(); i++) {
                JSONObject frame = frames.optJSONObject(i);
                if (frame == null) {
                    continue;
                }
                long seq = frame.optLong("seq", liveAfterSeq);
                if (seq > liveAfterSeq) {
                    liveAfterSeq = seq;
                }
                String line = String.format(java.util.Locale.US, "#%-6d id=0x%03X dlc=%d data=%s",
                        seq, frame.optInt("id", 0), frame.optInt("dlc", 0), frame.optString("data", ""));
                liveLines.addLast(line);
                while (liveLines.size() > LIVE_MAX_LINES) {
                    liveLines.removeFirst();
                }
            }
            if (liveText != null) {
                liveText.setText(String.join("\n", liveLines));
            }
        } catch (JSONException exception) {
            // Transient/partial response -- next poll will retry; not worth surfacing to the user.
        }
    }

    /** Clears only the on-screen tail, not liveAfterSeq -- polling keeps
     * following on from wherever it already was, so this just gives a clean
     * screen to watch the next action against instead of a running-together
     * scroll of whatever happened before. */
    private void clearLiveFrames() {
        liveLines.clear();
        if (liveText != null) {
            liveText.setText("");
        }
    }

    private void refreshCarButtons() {
        if (carButtonsRow == null) {
            return;
        }
        Context context = requireContext();
        carButtonsRow.removeAllViews();
        for (int i = 0; i < CARS.length; i++) {
            Car car = CARS[i];
            boolean selected = java.util.Objects.equals(car.id, selectedCarId);
            Button button = selected ? Views.primaryButton(context, car.label) : Views.secondaryButton(context, car.label);
            button.setOnClickListener(view -> selectCar(car));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) {
                params.leftMargin = Views.dp(context, 8);
            }
            carButtonsRow.addView(button, params);
        }
    }

    private void selectCar(Car car) {
        selectedCarId = car.id;
        prefs(requireContext()).edit().putString(PREF_SELECTED_CAR, car.id).apply();
        refreshCarButtons();
    }

    /** "None" plus one row per UdsTargets.ALL entry -- a vertical list rather
     * than the car selector's horizontal row since there are 8 candidates
     * plus "None", too many to fit legibly side by side. */
    private void refreshUdsTargetButtons() {
        if (udsTargetButtonsColumn == null) {
            return;
        }
        Context context = requireContext();
        udsTargetButtonsColumn.removeAllViews();

        Button noneButton = selectedUdsTarget == null
                ? Views.primaryButton(context, "None (just record)")
                : Views.secondaryButton(context, "None (just record)");
        noneButton.setOnClickListener(view -> selectUdsTarget(null));
        udsTargetButtonsColumn.addView(noneButton, Views.matchWrapTop(context, 0));

        for (UdsTargets.Target target : UdsTargets.ALL) {
            boolean selected = target == selectedUdsTarget;
            Button button = selected ? Views.primaryButton(context, target.label) : Views.secondaryButton(context, target.label);
            button.setOnClickListener(view -> selectUdsTarget(target));
            udsTargetButtonsColumn.addView(button, Views.matchWrapTop(context, 6));
        }
    }

    private void selectUdsTarget(UdsTargets.Target target) {
        selectedUdsTarget = target;
        int index = -1;
        for (int i = 0; i < UdsTargets.ALL.length; i++) {
            if (UdsTargets.ALL[i] == target) {
                index = i;
                break;
            }
        }
        prefs(requireContext()).edit().putInt(PREF_UDS_TARGET_INDEX, index).apply();
        refreshUdsTargetButtons();
        updateUdsEstimate();
    }

    /** Turns a free-text label (a UdsTargets.Target's human-readable label,
     * which may contain spaces/parens/commas) into a safe filename prefix
     * fragment: lowercase, non-alphanumeric runs collapsed to a single "-",
     * trimmed of leading/trailing "-", capped so the whole filename stays
     * reasonable. */
    private String slug(String text) {
        String result = text.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return result.length() > 24 ? result.substring(0, 24) : result;
    }

    /** Starts a recording labeled for the given source only if one isn't
     * already running -- used by the address-scan and VWTP-scan Start
     * buttons, which are otherwise completely independent of the Record/Stop
     * buttons (see RecordFragment's doc comment on recordingActive). Never
     * stops or relabels an already-running recording -- if the user started
     * one manually first, that's respected as-is rather than being
     * silently swapped out. */
    private void ensureRecordingWithLabel(String label) {
        if (!recordingActive) {
            recordingAutoStartedByScan = true;
            startRecording(label);
        }
    }

    /** Called from each scan poller (address/VWTP/deep) once it reaches a
     * terminal outcome -- stops the recording ONLY if this fragment is the
     * one that auto-started it via ensureRecordingWithLabel, never a
     * recording the user started manually (which stays running until they
     * tap Stop themselves, exactly as before this auto-stop existed). */
    private void stopRecordingIfAutoStarted() {
        if (recordingAutoStartedByScan && recordingActive) {
            stopRecording();
        }
    }

    private int parseHexOrDefault(EditText input, int fallback) {
        try {
            return Integer.parseInt(input.getText().toString().trim(), 16);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private void updateUdsEstimate() {
        if (udsEstimateText == null) {
            return;
        }
        if (selectedUdsTarget == null) {
            udsEstimateText.setText("");
            return;
        }
        int didStart = parseHexOrDefault(udsDidStartInput, UdsTargets.DEFAULT_DID_START);
        int didEnd = parseHexOrDefault(udsDidEndInput, UdsTargets.DEFAULT_DID_END);
        prefs(requireContext()).edit()
                .putInt(PREF_UDS_DID_START, didStart)
                .putInt(PREF_UDS_DID_END, didEnd)
                .apply();
        if (didEnd < didStart) {
            udsEstimateText.setText("DID end must be >= DID start.");
            return;
        }
        long worstCaseMs = UdsTargets.worstCaseMillis(didStart, didEnd);
        udsEstimateText.setText("Worst case (all timeouts): " + UdsTargets.formatDuration(worstCaseMs)
                + " -- keep recording running at least that long to be sure the sweep finishes.");
    }

    /** The "Start recording" button: labels the file by whichever DID-sweep
     * target is selected (this button also kicks off that sweep, below --
     * see the doc comment on ensureRecordingWithLabel for why that side
     * effect must NOT also apply to the address-scan/VWTP-scan buttons that
     * reuse startRecording(String) just to get a labeled file started). */
    private void startRecording() {
        String label = (selectedUdsTarget != null && !passiveCheckBox.isChecked())
                ? "uds-" + slug(selectedUdsTarget.label) : "can";
        recordingAutoStartedByScan = false;
        if (!startRecording(label)) {
            return;
        }
        udsPollHandler.removeCallbacksAndMessages(null);
        if (selectedUdsTarget != null && !passiveCheckBox.isChecked()) {
            int didStart = parseHexOrDefault(udsDidStartInput, UdsTargets.DEFAULT_DID_START);
            int didEnd = parseHexOrDefault(udsDidEndInput, UdsTargets.DEFAULT_DID_END);
            udsStatusText.setText("UDS scan: starting against " + selectedUdsTarget.label + "...");
            activeUdsTarget = selectedUdsTarget;
            activeUdsDidStart = didStart;
            activeUdsDidEnd = didEnd;
            boardLink.startUdsScan(selectedUdsTarget, didStart, didEnd, started -> {
                if (started) {
                    udsPollHandler.postDelayed(udsPollRunnable, UDS_POLL_INTERVAL_MS);
                } else {
                    udsStatusText.setText("UDS scan: failed to start");
                    activeUdsTarget = null;
                }
            });
        } else if (selectedUdsTarget != null) {
            udsStatusText.setText("UDS scan skipped -- requires Active mode (uncheck Passive above)");
        } else {
            udsStatusText.setText("");
        }
    }

    /** Just starts the CSV recording service with the given file-name label
     * -- no side effects beyond that. Deliberately does NOT also trigger the
     * DID-sweep-by-target behavior that the no-arg startRecording() (the
     * "Start recording" button itself) has -- ensureRecordingWithLabel calls
     * this directly from the address-scan/VWTP-scan buttons, and those must
     * not silently also kick off an unrelated DID sweep against whatever
     * target happens to be selected in the list. Returns false (does
     * nothing else) if the board isn't connected yet. */
    private boolean startRecording(String label) {
        if (!boardLink.isWifiReady()) {
            updateConnectionState();
            return false;
        }
        Intent intent = new Intent(requireContext(), CanCaptureService.class)
                .setAction(CanCaptureService.ACTION_START)
                .putExtra(CanCaptureService.EXTRA_BOARD_IP, boardLink.getBoardIp())
                .putExtra(CanCaptureService.EXTRA_PASSIVE, passiveCheckBox.isChecked())
                .putExtra(CanCaptureService.EXTRA_CAR, selectedCarId)
                .putExtra(CanCaptureService.EXTRA_LABEL, label);
        ContextCompat.startForegroundService(requireContext(), intent);
        return true;
    }

    private void stopRecording() {
        recordingAutoStartedByScan = false;
        Intent intent = new Intent(requireContext(), CanCaptureService.class)
                .setAction(CanCaptureService.ACTION_STOP);
        requireContext().startService(intent);
    }

    /** Recordings now live either flat under can-captures/ (plain Record tab)
     * or nested under can-captures/&lt;car&gt;/ (Learn tab, per-car folders),
     * so this walks one level of subdirectories instead of a flat listFiles(). */
    private List<File> listAllCaptureFiles() {
        File root = new File(requireContext().getExternalFilesDir(null), "can-captures");
        List<File> result = new ArrayList<>();
        File[] entries = root.listFiles();
        if (entries == null) {
            return result;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                File[] nested = entry.listFiles((dir, name) -> name.endsWith(".csv"));
                if (nested != null) {
                    result.addAll(Arrays.asList(nested));
                }
            } else if (entry.getName().endsWith(".csv")) {
                result.add(entry);
            }
        }
        return result;
    }

    private void refreshCaptureList() {
        if (capturesText == null) {
            return;
        }
        List<File> files = listAllCaptureFiles();
        if (files.isEmpty()) {
            capturesText.setText("No recordings yet.");
            return;
        }

        files.sort((left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
        StringBuilder summary = new StringBuilder();
        int count = Math.min(files.size(), 10);
        for (int index = 0; index < count; index++) {
            File file = files.get(index);
            File parent = file.getParentFile();
            boolean nested = parent != null && !parent.getName().equals("can-captures");
            summary.append(nested ? parent.getName() + "/" + file.getName() : file.getName())
                    .append("  ")
                    .append(file.length() / 1024)
                    .append(" KB  ")
                    .append(dateFormat.format(new Date(file.lastModified())))
                    .append('\n');
        }
        capturesText.setText(summary.toString().trim());
        refreshUploadList();
    }

    /** Keeps the upload checkbox column's file order in sync with
     * capturesText -- checkbox index N corresponds to uploadCandidateFiles
     * index N, since both are built from the same sorted listAllCaptureFiles(). */
    private void refreshUploadList() {
        if (uploadCheckboxColumn == null) {
            return;
        }
        Context context = requireContext();
        uploadCheckboxColumn.removeAllViews();
        uploadCheckboxes = new ArrayList<>();
        uploadCandidateFiles = listAllCaptureFiles();
        uploadCandidateFiles.sort((left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        int count = Math.min(uploadCandidateFiles.size(), 10);
        if (count == 0) {
            uploadCheckboxColumn.addView(Views.label(context, "No recordings yet.", 12, false),
                    Views.matchWrapTop(context, 0));
            return;
        }
        for (int index = 0; index < count; index++) {
            File file = uploadCandidateFiles.get(index);
            File parent = file.getParentFile();
            boolean nested = parent != null && !parent.getName().equals("can-captures");
            String label = (nested ? parent.getName() + "/" + file.getName() : file.getName())
                    + "  (" + (file.length() / 1024) + " KB)";
            CheckBox checkBox = new CheckBox(context);
            checkBox.setText(label);
            checkBox.setTextColor(0xff1f2933);
            uploadCheckboxColumn.addView(checkBox, Views.matchWrapTop(context, index == 0 ? 0 : 4));
            uploadCheckboxes.add(checkBox);
        }
    }

    private void uploadSelectedRecordings() {
        if (driveFolderUri == null) {
            uploadStatusText.setText("Choose a Drive folder first (button above).");
            return;
        }
        List<File> selected = new ArrayList<>();
        for (int index = 0; index < uploadCheckboxes.size(); index++) {
            if (uploadCheckboxes.get(index).isChecked()) {
                selected.add(uploadCandidateFiles.get(index));
            }
        }
        if (selected.isEmpty()) {
            uploadStatusText.setText("Select at least one recording first.");
            return;
        }
        uploadNext(selected, 0, 0);
    }

    /** Uploads one file at a time (rather than in parallel) so the status
     * text can show clean progress and the relay isn't hit with a burst of
     * simultaneous requests. */
    private void uploadNext(List<File> files, int index, int succeeded) {
        if (index >= files.size()) {
            uploadStatusText.setText("Uploaded " + succeeded + "/" + files.size() + " recording(s) to Drive.");
            return;
        }
        uploadStatusText.setText("Uploading " + (index + 1) + "/" + files.size() + ": " + files.get(index).getName());
        File file = files.get(index);
        DriveUploader.upload(requireContext(), driveFolderUri, file, success -> {
            if (!isAdded()) {
                return;
            }
            if (success) {
                UploadCleanup.markUploaded(requireContext(), file.getName(), driveFolderUri);
            }
            uploadNext(files, index + 1, succeeded + (success ? 1 : 0));
        });
    }

    private void confirmClearAllRecordings() {
        List<File> files = listAllCaptureFiles();
        int count = files.size();
        if (count == 0) {
            capturesText.setText("No recordings yet.");
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete all recordings?")
                .setMessage("This will permanently delete all " + count + " recording(s) on this phone "
                        + "(from both Record and Learn). This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> clearAllRecordings(files))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearAllRecordings(List<File> files) {
        int deleted = 0;
        for (File file : files) {
            if (file.delete()) {
                deleted++;
            }
        }
        capturesText.setText(deleted + " recording(s) deleted.");
        refreshCaptureList();
    }

    private void updateConnectionState() {
        boolean ready = boardLink != null && boardLink.isWifiReady();
        if (connectionText != null) {
            connectionText.setText(ready
                    ? "P4 Wi-Fi ready: " + boardLink.getBoardIp()
                    : "Connect the P4 to Wi-Fi before recording.");
        }
        if (startButton != null) {
            startButton.setEnabled(ready);
        }
    }

    @Override
    public void onWifiConnected(String boardIp) {
        updateConnectionState();
    }

    @Override
    public void onBoardConnectionChanged(boolean connected) {
        updateConnectionState();
    }
}
