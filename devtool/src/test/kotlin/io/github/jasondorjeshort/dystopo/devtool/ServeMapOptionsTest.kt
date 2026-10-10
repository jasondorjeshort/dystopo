package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreSources
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.HillshadeMode
import io.github.jasondorjeshort.dystopo.tiles.ShadeTileStats
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T50: the phone's map options through `devtool serve` query strings. */
class ServeMapOptionsTest {
    @Test
    fun defaultsNeedNoShadeTilesAndNoInterval() {
        val o = ServeMapOptions.parse(ServeMapOptions.query("units=ft&lat=46.8&lon=-121.7"), ContourUnits.FEET)
        assertNull(ServeMapOptions.shadeTiles("http://h", o))
        assertEquals("", ServeMapOptions.contourQuery(o))
    }

    @Test
    fun optionsFlowIntoTheStyleAndTheTileRoutes() {
        val q = ServeMapOptions.query("hillshade=multi&slope=on&night=on&contourInterval=100&trails=off")
        val o = ServeMapOptions.parse(q, ContourUnits.FEET)
        assertEquals("&interval=100", ServeMapOptions.contourQuery(o))
        val shadeUrl = ServeMapOptions.shadeTiles("http://h", o)!!
        assertTrue(shadeUrl.startsWith("http://h/shade/{z}/{x}/{y}.png?"), shadeUrl)
        // The shade route reads back exactly the options the style asked for.
        val shade = ServeMapOptions.shadeOptions(ServeMapOptions.query(shadeUrl.substringAfter('?')))
        assertEquals(o.shade, shade)
        assertEquals(HillshadeMode.MULTI, shade.hillshade)
        assertTrue(shade.slope && shade.night)
        // The contour route applies the interval from the fixed-interval zoom up.
        val c = ServeMapOptions.contourOptions(ContourTileOptions(units = ContourUnits.FEET), ServeMapOptions.query("interval=100"))
        assertEquals(100.0, c.interval)
        val style = MapLibreStyle(
            MapLibreSources(
                demTiles = "http://h/dem/{z}/{x}/{y}.png", demMinZoom = 8, demMaxZoom = 14,
                contourTiles = "http://h/contours/{z}/{x}/{y}.pbf?units=ft" + ServeMapOptions.contourQuery(o), contourMinZoom = 7, contourMaxZoom = 15,
                osmTiles = "http://h/osm/{z}/{x}/{y}.pbf", glyphs = "http://h/glyphs/{fontstack}/{range}.pbf",
                shadeTiles = shadeUrl,
            ),
            options = o,
        ).json()
        assertTrue("\"shade\"" in style && "interval=100" in style, "style has the shade source and the interval")
        assertFalse("\"trail-mountain\"" in style, "trails toggled off")
        assertTrue("\"wilderness-fill\"" in style, "the wilderness fill is in the phone style")
    }

    @Test
    fun badValuesAreRefusedWithTheirName() {
        val e = assertFailsWith<IllegalArgumentException> { ServeMapOptions.parse(mapOf("slope" to "maybe"), ContourUnits.FEET) }
        assertTrue("slope" in e.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { ServeMapOptions.parse(mapOf("contourInterval" to "33"), ContourUnits.FEET) }
        assertFailsWith<IllegalArgumentException> { ServeMapOptions.contourOptions(ContourTileOptions(units = ContourUnits.METERS), mapOf("interval" to "100.5")) }
        assertFailsWith<IllegalArgumentException> { ServeMapOptions.shadeOptions(mapOf("hillshade" to "sideways")) }
        // Unknown keys (units, lat, lon) are not options and pass.
        ServeMapOptions.parse(mapOf("units" to "m", "lat" to "1", "foo" to "bar"), ContourUnits.METERS)
    }

    @Test
    fun shadeTileIsA256PngWithSlopeBandsOnSteepGround() {
        // A steep cone in a one-tile z12 pack; tile 12/650/1400 holds it.
        val z = 12
        val tx = 650
        val ty = 1400
        val argb = IntArray(256 * 256) { i -> Terrarium.encode((3000.0 - hypot(i % 256 - 128.0, i / 256 - 128.0) * 25.0).toFloat()) }
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, 256, 256, argb, 0, 256) }
        val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        val dem = DemPyramid(z, z, demDecoder) { tz, x, y -> if (tz == z && x == tx && y == ty) png else null }
        val stats = ShadeTileStats()
        val bytes = ServeMapOptions.shadePng(dem, z, tx, ty, ServeMapOptions.shadeOptions(mapOf("hillshade" to "multi", "slope" to "on")), stats)
        val out = ImageIO.read(bytes.inputStream())
        assertEquals(256, out.width)
        assertTrue(out.colorModel.hasAlpha())
        val opaque = (0 until 256 * 256).count { (out.getRGB(it % 256, it / 256) ushr 24) > 0 }
        assertTrue(opaque > 256 * 256 / 4, "shading and slope bands over the cone ($opaque px)")
        // Outside the pack: a transparent tile, not an error.
        val empty = ImageIO.read(ServeMapOptions.shadePng(dem, z, tx + 3, ty, ServeMapOptions.shadeOptions(mapOf("slope" to "on"))).inputStream())
        assertEquals(0, (0 until 256 * 256).count { (empty.getRGB(it % 256, it / 256) ushr 24) > 0 })
        assertFailsWith<IllegalArgumentException> { ServeMapOptions.shadePng(dem, 3, 9, 0, ServeMapOptions.shadeOptions(emptyMap())) }
    }
}
