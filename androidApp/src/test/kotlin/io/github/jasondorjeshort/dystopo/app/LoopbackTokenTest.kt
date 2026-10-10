package io.github.jasondorjeshort.dystopo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopbackTokenTest {
    private val token = LoopbackToken("s3cret")

    private fun req(path: String, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(), method: String = "GET", loopback: Boolean = true) =
        Request(method, path, query, ByteArray(0), headers, loopback)

    @Test
    fun queryOrHeaderAdmitsAndTheQueryParameterIsStripped() {
        val (r, answer) = token.admit(req("/dem/1/0/0.png", mapOf("v" to "x", "t" to "s3cret")), debugEndpoints = false)
        assertNull(answer)
        assertEquals(mapOf("v" to "x"), r!!.query)
        val (h, hAnswer) = token.admit(req("/stats", headers = mapOf("x-dystopo-token" to "s3cret")), debugEndpoints = true)
        assertNull(hAnswer)
        assertEquals("/stats", h!!.path)
    }

    @Test
    fun missingOrWrongTokenIs401() {
        for (r in listOf(req("/dem/1/0/0.png"), req("/style.json", mapOf("t" to "nope")), req("/stats", headers = mapOf("x-dystopo-token" to "s3cre")))) {
            val (admitted, answer) = token.admit(r, debugEndpoints = true)
            assertNull(admitted)
            assertEquals(401, answer!!.status)
            assertFalse(answer.body.decodeToString().contains("s3cret"))
        }
        assertEquals(3L, token.refused.get())
    }

    @Test
    fun healthHandsOutTheTokenOnlyInDebugBuildsToLoopbackCallers() {
        val (_, debug) = token.admit(req("/health"), debugEndpoints = true)
        assertEquals(200, debug!!.status)
        assertEquals("{\"token\":\"s3cret\"}", debug.body.decodeToString().trim().replace(" ", ""))
        assertEquals(401, token.admit(req("/health"), debugEndpoints = false).second!!.status) // release: no bootstrap
        assertEquals(401, token.admit(req("/health", loopback = false), debugEndpoints = true).second!!.status)
        assertEquals(401, token.admit(req("/health", method = "POST"), debugEndpoints = true).second!!.status)
        assertEquals(401, token.admit(req("/"), debugEndpoints = true).second!!.status)
    }

    @Test
    fun urlsGetTheTokenAndTokensDifferPerLaunch() {
        assertEquals("http://h/dem/{z}/{x}/{y}.png?v=1&t=s3cret", token.url("http://h/dem/{z}/{x}/{y}.png?v=1"))
        assertEquals("http://h/g/{fontstack}/{range}.pbf?t=s3cret", token.url("http://h/g/{fontstack}/{range}.pbf"))
        val a = LoopbackToken().value
        val b = LoopbackToken().value
        assertEquals(32, a.length)
        assertNotEquals(a, b)
        assertTrue(a.all { it in "0123456789abcdef" })
    }
}
