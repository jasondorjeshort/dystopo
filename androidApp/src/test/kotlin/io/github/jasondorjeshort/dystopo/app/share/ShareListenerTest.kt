package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.Response
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T90: the share listener's end of a reply. A relay that drops what it still holds when the sender closes (the adb
 * forward between two emulators did, T78) cuts a reply that the sender closes right after writing; the share listener
 * waits for the client's close, so the same relay delivers every byte. And a client that stops reading is cut off by
 * the write deadline instead of holding a worker. T96: the wait is bounded in time and in sockets, on one thread.
 */
class ShareListenerTest {
    private val closers = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closers.reversed().forEach { runCatching { it.close() } }

    /**
     * A relay on loopback in front of `127.0.0.1:[upstream]` that reads the upstream at full speed into a queue and
     * passes it on at [bytesPerSec]; when the upstream closes it closes the client at once, dropping what is still queued.
     */
    private fun lossyRelay(upstream: Int, bytesPerSec: Int): Int {
        val ss = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        closers += ss
        Thread {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                val u = Socket(InetAddress.getLoopbackAddress(), upstream)
                closers += c
                closers += u
                Thread { runCatching { c.getInputStream().copyTo(u.getOutputStream()); u.shutdownOutput() } }.apply { isDaemon = true }.start()
                val q = LinkedBlockingQueue<ByteArray>()
                val upstreamDone = java.util.concurrent.atomic.AtomicBoolean()
                Thread {
                    val buf = ByteArray(65536)
                    val input = u.getInputStream()
                    runCatching {
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            q.put(buf.copyOf(n))
                        }
                    }
                    upstreamDone.set(true)
                }.apply { isDaemon = true }.start()
                Thread {
                    val out = c.getOutputStream()
                    runCatching {
                        while (true) {
                            if (upstreamDone.get()) { // the sender closed: whatever is still queued is lost
                                c.close()
                                break
                            }
                            val b = q.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                            out.write(b)
                            out.flush()
                            Thread.sleep(b.size * 1000L / bytesPerSec)
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort
    }

    /** A server that writes its reply and closes at once (what the share listener did before T90). */
    private fun closingServer(body: ByteArray): Int {
        val ss = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        closers += ss
        Thread {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching {
                    c.use { s ->
                        readHead(BufferedInputStream(s.getInputStream()))
                        s.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort
    }

    private fun readHead(input: InputStream) {
        var last = 0
        var run = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            run = if (b == '\n'.code && last == '\r'.code) run + 1 else if (b == '\r'.code) run else 0
            last = b
            if (run == 2) return
        }
    }

    /** GET through `127.0.0.1:[port]` as [LanHttp] does: the whole body by Content-Length, or an [IOException]. */
    private fun get(port: Int): ByteArray = LanHttp.request("GET", "http://127.0.0.1:$port/x", connectMs = 2000, readMs = 20_000).body

    private fun listener(body: ByteArray, stallMs: Long = 60_000): ShareListener {
        val l = ShareListener(0, handler = { Response(200, "application/octet-stream", body) }, bindAddress = "127.0.0.1", stallMs = stallMs, lingerMs = 5_000)
        l.start()
        closers += AutoCloseable { l.stop() }
        return l
    }

    @Test
    fun aReplyIsWholeThroughARelayThatDropsOnClose() {
        val body = Random(90).nextBytes(1 shl 20)
        // The cut, reproduced: write and close, through the lossy relay at 2 MB/s.
        val cut = runCatching { get(lossyRelay(closingServer(body), 2_000_000)) }
        assertTrue(cut.isFailure, "a sender that closes at once loses the relay's queue")
        // Cut part way ("shorter than its content-length"), or before the first byte when the relay had sent nothing yet ("no reply").
        val why = cut.exceptionOrNull()!!.message!!
        assertTrue("shorter than its content-length" in why || "no reply" in why, why)
        // The share listener waits for the client's close: the same relay delivers the whole reply.
        val l = listener(body)
        repeat(2) { assertContentEquals(body, get(lossyRelay(l.localPort, 2_000_000))) }
        val deadline = System.currentTimeMillis() + 5_000
        while (l.clientClosed.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(2L, l.clientClosed.get())
        assertEquals(0L, l.lingerTimeouts.get())
        assertEquals(2L * body.size, l.bytesOut.get())
    }

    @Test
    fun aClientThatStopsReadingIsCutOffByTheWriteDeadline() {
        val l = listener(ByteArray(32 shl 20), stallMs = 500)
        val s = Socket(InetAddress.getLoopbackAddress(), l.localPort)
        closers += s
        s.receiveBufferSize = 4096
        s.getOutputStream().write("GET /x HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        // Never read: the reply fills the buffers and the write stops making progress.
        val deadline = System.currentTimeMillis() + 10_000
        while (l.stalled.get() == 0L && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(1L, l.stalled.get())
        // The worker is free again: the next request is answered.
        val small = listener(ByteArray(10))
        assertEquals(10, get(small.localPort).size)
    }

    /** Reads one reply (head, then Content-Length bytes) from [s] and leaves the connection open; returns the body size. */
    private fun readReply(s: Socket): Int {
        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { if (it < 0) throw IOException("closed in the head") }.toChar())
        val n = Regex("Content-Length: (\\d+)").find(head)!!.groupValues[1].toInt()
        val buf = ByteArray(65536)
        var got = 0
        while (got < n) got += input.read(buf, 0, minOf(buf.size, n - got)).also { if (it < 0) throw IOException("closed in the body") }
        return got
    }

    private fun lingerThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "dystopo-share-linger" }

    /**
     * T96: 300 idle clients (each reads its reply and never closes). T90 held a socket and a thread per client for the whole
     * 30 s; now at most [ShareListener.MAX_LINGERING] wait at once (the oldest is closed when one more arrives), on one
     * watcher thread, and each is closed within the cap.
     */
    @Test
    fun idleClientsAreReleasedWithinTheCapOnOneThread() {
        val cap = 16
        val maxMs = 1_500L
        val before = lingerThreads()
        val l = ShareListener(0, handler = { Response(200, "text/plain", ByteArray(100)) }, bindAddress = "127.0.0.1", lingerMs = 30_000, maxLingerMs = maxMs, maxLingering = cap)
        l.start()
        closers += AutoCloseable { l.stop() }
        val clients = (1..300).map {
            Socket(InetAddress.getLoopbackAddress(), l.localPort).also { s ->
                closers += s
                s.soTimeout = 10_000
                s.getOutputStream().write("GET /x HTTP/1.1\r\n\r\n".toByteArray())
                assertEquals(100, readReply(s))
            }
        }
        val openedAt = System.currentTimeMillis()
        assertTrue(l.lingerPeak.get() <= cap, "at most $cap waiting at once, saw ${l.lingerPeak.get()}")
        assertTrue((l.json()["lingering"] as Int) <= cap)
        assertTrue(lingerThreads() - before <= 1, "one watcher thread, not one per client")
        // Every client sees the listener's close within the cap of its own wait (the last one waited longest).
        clients.forEach { s ->
            s.soTimeout = (openedAt + maxMs + 3_000 - System.currentTimeMillis()).coerceAtLeast(1).toInt()
            assertEquals(-1, s.getInputStream().read(), "closed by the listener")
        }
        assertTrue(System.currentTimeMillis() - openedAt < maxMs + 3_000)
        val deadline = System.currentTimeMillis() + 2_000
        while ((l.lingerEvicted.get() + l.lingerTimeouts.get()) < 300 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(300L, l.lingerEvicted.get() + l.lingerTimeouts.get())
        assertTrue(l.lingerEvicted.get() >= 300L - cap - 1)
        assertEquals(0L, l.clientClosed.get())
        assertEquals(0, l.json()["lingering"])
    }

    /** T96: a 4 MiB reply whose client never closes waited 30 s + 11.6 h under T90's rule; now at most the cap. */
    @Test
    fun aBigReplyWaitsAtMostTheCap() {
        assertEquals(ShareListener.MAX_LINGER_MS, ShareListener.lingerWait(4L shl 20, 30_000, 100, ShareListener.MAX_LINGER_MS))
        assertEquals(30_250L, ShareListener.lingerWait(25, 30_000, 100, ShareListener.MAX_LINGER_MS))
        assertTrue(ShareListener.MAX_LINGER_MS <= 5 * 60_000L, "a few minutes in all")
        val l = ShareListener(0, handler = { Response(200, "application/octet-stream", ByteArray(4 shl 20)) }, bindAddress = "127.0.0.1", maxLingerMs = 800)
        l.start()
        closers += AutoCloseable { l.stop() }
        Socket(InetAddress.getLoopbackAddress(), l.localPort).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write("GET /x HTTP/1.1\r\n\r\n".toByteArray())
            assertEquals(4 shl 20, readReply(s))
            val t0 = System.currentTimeMillis()
            assertEquals(-1, s.getInputStream().read())
            assertTrue(System.currentTimeMillis() - t0 < 3_000)
        }
        assertEquals(1L, l.lingerTimeouts.get())
    }

    @Test
    fun requestsBeyondTheLimitsAreRefused() {
        val l = listener(ByteArray(1))
        fun raw(text: String): String = Socket(InetAddress.getLoopbackAddress(), l.localPort).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(text.toByteArray())
            s.shutdownOutput()
            val o = ByteArrayOutputStream()
            runCatching { s.getInputStream().copyTo(o) }
            o.toString(Charsets.UTF_8)
        }
        assertTrue(raw("POST /x HTTP/1.1\r\nContent-Length: ${(1 shl 20) + 1}\r\n\r\n").startsWith("HTTP/1.1 413"))
        assertTrue(raw("NOT HTTP\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(raw("GET /x HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
        assertEquals(2L, l.badRequests.get())
    }
}
