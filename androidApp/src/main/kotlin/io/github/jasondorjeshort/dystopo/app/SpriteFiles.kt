package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.style.Sprites
import java.io.File

/**
 * Where MapLibre loads the marker sprite sheet from: `files/sprites/sprite{,@2x}.{json,png}`, written by
 * `devtool sprite` and pushed by tools/android-push-pack.ps1, loaded through a `file://` URL like the
 * glyphs ([Glyphs]), so icons never wait behind tile requests. Null when any of the four files is
 * missing; the style then falls back to marker discs (no icon collisions, see MapLibreStyle).
 */
object SpriteFiles {
    const val DIR = "sprites"

    /** [filesRoot] is the directory holding `glyphs/` and `sprites/` (the app's files dir). */
    fun styleUrl(filesRoot: File?): String? {
        val dir = filesRoot?.let { File(it, DIR) } ?: return null
        if (!Sprites.FILES.all { File(dir, it).isFile }) return null
        return "file://${dir.absolutePath}/${Sprites.NAME}"
    }
}
