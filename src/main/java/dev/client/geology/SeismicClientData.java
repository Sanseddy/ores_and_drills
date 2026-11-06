package dev.client.geology;

import dev.network.SeismicSurveyListPayload;
import dev.network.SeismicSurveyMapPayload;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/**
 * What the server last sent for the open station screen. Only one station screen is open at a time,
 * so the latest payload of each kind is all that is kept; a revision counter tells the screen when
 * to rebuild its view.
 */
public final class SeismicClientData {
    @Nullable private static SeismicSurveyListPayload list;
    @Nullable private static SeismicSurveyMapPayload map;
    private static int revision;

    private SeismicClientData() {}

    public static void acceptList(SeismicSurveyListPayload payload) {
        list = payload;
        revision++;
    }

    public static void acceptMap(SeismicSurveyMapPayload payload) {
        map = payload;
        revision++;
    }

    @Nullable
    public static SeismicSurveyListPayload list(BlockPos station) {
        return list != null && list.station().equals(station) ? list : null;
    }

    @Nullable
    public static SeismicSurveyMapPayload map(BlockPos station) {
        return map != null && map.station().equals(station) ? map : null;
    }

    public static int revision() {
        return revision;
    }

    /** Forgets everything when the screen closes so another station never shows stale data. */
    public static void clear() {
        list = null;
        map = null;
        revision++;
    }
}
