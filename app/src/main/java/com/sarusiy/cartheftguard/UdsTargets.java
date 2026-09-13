package com.sarusiy.cartheftguard;

/**
 * Candidate VAG body/comfort-module UDS diagnostic addresses -- research
 * summary in JC-ESP32P4-M3/captures/fabia_2026/UDS_BODY_MODULE_RESEARCH.md.
 * None of these are confirmed to actually respond on any specific real car
 * yet; they're the starting points a DID sweep (see BoardLink.startUdsScan)
 * gets pointed at. Shared between LearnFragment (auto-picks a target per
 * stage) and RecordFragment (lets the user pick one manually).
 */
public final class UdsTargets {
    private UdsTargets() {
    }

    public static final class Target {
        public final String label;
        public final int requestId;
        public final int responseId;

        Target(String label, int requestId, int responseId) {
            this.label = label;
            this.requestId = requestId;
            this.responseId = responseId;
        }
    }

    public static final Target CENTRAL_CONVENIENCE = new Target("Central Convenience (locks/horn/comfort)", 0x70D, 0x777);
    public static final Target LOCK_ELECTRONICS = new Target("Lock Electronics", 0x71E, 0x788);
    public static final Target DOOR_DRIVER = new Target("Driver Door", 0x74A, 0x7B4);
    public static final Target DOOR_PASSENGER = new Target("Passenger Door", 0x74B, 0x7B5);
    public static final Target DOOR_REAR_DRIVER = new Target("Rear Driver Door", 0x73E, 0x7A8);
    public static final Target DOOR_REAR_PASSENGER = new Target("Rear Passenger Door", 0x73F, 0x7A9);
    public static final Target HEADLIGHT_REGULATION = new Target("Headlight Regulation", 0x754, 0x7BE);
    public static final Target HIGH_BEAM_ASSIST = new Target("High Beam Assist", 0x730, 0x79A);

    public static final Target[] ALL = {
            CENTRAL_CONVENIENCE, LOCK_ELECTRONICS, DOOR_DRIVER, DOOR_PASSENGER,
            DOOR_REAR_DRIVER, DOOR_REAR_PASSENGER, HEADLIGHT_REGULATION, HIGH_BEAM_ASSIST,
    };

    /** Default DID sweep range -- modest, per UDS_BODY_MODULE_RESEARCH.md's
     * suggested first range, not the full 512-DID max the firmware allows. */
    public static final int DEFAULT_DID_START = 0x0100;
    public static final int DEFAULT_DID_END = 0x0200;

    /** Worst case per DID: firmware's OBD_RESPONSE_TIMEOUT_MS (500) +
     * OBD_QUERY_INTERVAL_MS (200) if it times out rather than answering
     * quickly -- see main.c. Used to show a time estimate so a manually-
     * timed recording (Record tab) can be kept running long enough. */
    public static final int WORST_CASE_MS_PER_DID = 700;

    public static long worstCaseMillis(int didStart, int didEnd) {
        int count = didEnd - didStart + 1;
        return count > 0 ? (long) count * WORST_CASE_MS_PER_DID : 0;
    }

    public static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }
}
