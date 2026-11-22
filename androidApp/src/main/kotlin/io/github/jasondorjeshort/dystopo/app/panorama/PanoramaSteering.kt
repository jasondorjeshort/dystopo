package io.github.jasondorjeshort.dystopo.app.panorama

/**
 * T330: how the Panorama view is steered (wave 46's choice (g); ADR 0064 and ADR 0044: the apps split, so a setting):
 * [DRAG] by the fingers (the default, pass two's view) or [FOLLOW] by the phone's attitude. Stored and synced as [key]
 * (`panoramaSteering`); a value this build does not know reads as the default. On a phone with no attitude sensor a synced
 * `follow` acts as Drag without changing the setting ([effective]).
 */
enum class PanoramaSteering(val key: String, val label: String) {
    DRAG("drag", "Drag"),
    FOLLOW("follow", "Follow phone"),
    ;

    companion object {
        val DEFAULT = DRAG

        fun of(key: String?): PanoramaSteering? = entries.firstOrNull { it.key == key }

        /** Whether the view follows the phone: the setting is [FOLLOW] and the phone has an attitude sensor ([sensor]). */
        fun follows(setting: PanoramaSteering, sensor: Boolean): Boolean = setting == FOLLOW && sensor
    }
}
