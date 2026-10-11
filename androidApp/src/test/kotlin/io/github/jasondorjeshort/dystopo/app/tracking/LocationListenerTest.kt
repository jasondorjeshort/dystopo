package io.github.jasondorjeshort.dystopo.app.tracking

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T81: no `LocationListener { ... }` lambda in the app. A lambda (SAM conversion) implements onLocationChanged
 * alone; onStatusChanged, onProviderEnabled and onProviderDisabled became default methods only in API 30, so on
 * Android 8 to 10 (minSdk 26) the app died with AbstractMethodError when GPS started or was switched off. Use
 * [locationListener], which implements all four.
 */
class LocationListenerTest {
    /** `LocationListener { ... }` (a SAM lambda), not `object : LocationListener { ... }` (a full implementation). */
    private val sam = Regex("""(?<!:\s)(?<!:)\bLocationListener\s*\{""")

    @Test
    fun theRuleTellsLambdasFromImplementations() {
        assertTrue(sam.containsMatchIn("val l = LocationListener { loc -> use(loc) }"))
        assertTrue(sam.containsMatchIn("lm.requestLocationUpdates(p, 1000L, 0f, LocationListener { }, looper)"))
        assertTrue(!sam.containsMatchIn("val l = object : LocationListener {"))
    }

    @Test
    fun noLocationListenerLambdas() {
        val hits = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (sam.containsMatchIn(code)) "${f.path}:${i + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertTrue(hits.isEmpty(), "LocationListener lambdas crash below API 30; use locationListener {}:\n" + hits.joinToString("\n"))
    }

    @Test
    fun theSourcesAreThere() {
        // Guards the scan itself: it must see the app's sources (the test runs with the module as working directory).
        assertTrue(File("src/main/kotlin").walkTopDown().any { it.name == "LocationSource.kt" })
    }
}
