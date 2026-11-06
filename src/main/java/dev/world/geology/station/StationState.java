package dev.world.geology.station;

/** What the station is doing; drives its animation, sound and the GUI. */
public enum StationState {
    IDLE,
    /** Lowering the striker and settling the frame before the first impact. */
    STARTING,
    /** Repeating impacts while recording. */
    PULSING,
    /** All impacts done; waiting for the data to be processed. */
    PROCESSING,
    /** Raising the striker after a finished survey. */
    FINISHING,
    /** Out of energy mid-survey; resumes when powered. */
    NO_POWER;

    public boolean surveying() {
        return this == STARTING || this == PULSING || this == PROCESSING || this == NO_POWER;
    }

    public static StationState byId(int id) {
        StationState[] states = values();
        return id >= 0 && id < states.length ? states[id] : IDLE;
    }
}
