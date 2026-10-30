package io.github.jasondorjeshort.dystopo.model

/**
 * How a track is travelled (T158): the fixed list the owner chose on the readout idea (2026-10-25), shared by
 * the recorder ([ActivityProfile]), the live readout (T160) and the picker (T172). An activity changes only
 * defaults the user can see, never hidden behaviour.
 *
 * [code] is stored with a track (`track.activity`) and synced as a track field: never change or reuse one.
 */
enum class Activity(val code: String) {
    /** Walking, hiking, running, skiing on foot. */
    FOOT("foot"),
    BIKE("bike"),
    /** A car, a bus, a motorbike: anything on a road with an engine. */
    ROAD("road"),
    RAIL("rail"),
    BOAT("boat"),
    AIR("air");

    /** The recorder suggests auto-pause (a notice, not the default) for this activity: road only (research of 2026-10-30). */
    val suggestsAutoPause: Boolean get() = this == ROAD

    companion object {
        /** The activity with [code], or null for null or a code this build does not know (a newer peer's). */
        fun of(code: String?): Activity? = entries.firstOrNull { it.code == code }
    }
}
