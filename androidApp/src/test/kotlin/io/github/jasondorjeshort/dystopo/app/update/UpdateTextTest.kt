package io.github.jasondorjeshort.dystopo.app.update

import kotlin.test.Test
import kotlin.test.assertEquals

/** T223: the Updates row's short line and what its tap shows. */
class UpdateTextTest {
    @Test
    fun aBuildPastItsTagReadsAsTheTag() {
        assertEquals("0.1.18-alpha", UpdateText.shortVersion("0.1.18-alpha-81-g0b74b66f-sweep"))
        assertEquals("0.1.18-alpha", UpdateText.shortVersion("0.1.18-alpha-81-g0b74b66f"))
    }

    @Test
    fun aReleaseNameIsAlreadyShort() {
        assertEquals("0.1.19-alpha", UpdateText.shortVersion("0.1.19-alpha"))
        assertEquals("0.1.12-dev", UpdateText.shortVersion("0.1.12-dev"))
    }

    @Test
    fun theLines() {
        assertEquals("Up to date · 0.1.19-alpha", UpdateText.upToDate("0.1.19-alpha"))
        assertEquals("This build: 0.1.18-alpha", UpdateText.thisBuild("0.1.18-alpha-81-g0b74b66f-sweep"))
        assertEquals("Full build: 0.1.18-alpha-81-g0b74b66f-sweep (231)", UpdateText.fullBuild("0.1.18-alpha-81-g0b74b66f-sweep", 231, null))
        assertEquals("Full build: 0.1.19-alpha (240); newest release v0.1.19-alpha", UpdateText.fullBuild("0.1.19-alpha", 240, "v0.1.19-alpha"))
    }
}
