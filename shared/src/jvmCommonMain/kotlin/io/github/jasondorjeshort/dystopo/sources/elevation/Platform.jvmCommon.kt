package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Inflater

// T80: the JVM and Android actuals of Platform.kt (one source set for both: java.io, java.util.concurrent and
// java.util.zip exist on Android 8+). The default HTTP client is per platform (jvmMain, androidMain).

actual object SourceFiles {
    actual fun read(path: String): ByteArray? {
        val f = File(path)
        return try {
            if (f.isFile) f.readBytes() else null
        } catch (e: IOException) {
            null // deleted between the check and the read (the phone trims its caches)
        }
    }

    /** A temp name next to [file], unique per process and thread (worktrees and parallel renders share one cache). */
    private fun partFile(file: File): File = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.part")

    actual fun write(path: String, bytes: ByteArray): Boolean {
        val file = File(path)
        file.parentFile?.mkdirs()
        val tmp = partFile(file)
        tmp.writeBytes(bytes)
        return publish(tmp, file)
    }

    actual fun replace(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val tmp = partFile(file)
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * Moves a finished [tmp] onto [file]. If another thread or process got there first, its file is kept and
     * [tmp] is dropped: both downloaded the same bytes, and replacing a cache file that may be open for reading
     * fails on Windows (and, with REPLACE_EXISTING, two racing movers make each other fail with
     * FileAlreadyExistsException). Returns false when the existing file was kept.
     */
    @Volatile private var linksWork = true

    private fun publish(tmp: File, file: File): Boolean {
        // A hard link is created atomically or not at all. Files.move without REPLACE_EXISTING checks and
        // renames in two steps on Linux, so two racing movers could both "win" there (found by CI, T48).
        if (linksWork) try {
            Files.createLink(file.toPath(), tmp.toPath())
            tmp.delete()
            return true
        } catch (e: FileAlreadyExistsException) {
            tmp.delete()
            return false
        } catch (e: Exception) {
            if (e !is UnsupportedOperationException && e !is FileSystemException && e !is SecurityException) throw e
            // No hard links on this file system, or not allowed (Android apps: SELinux denies link(), and logs each
            // attempt): move from now on.
            if (e !is FileSystemException || e is java.nio.file.AccessDeniedException) linksWork = false
        }
        try {
            Files.move(tmp.toPath(), file.toPath())
            return true
        } catch (e: FileSystemException) {
            // FileAlreadyExistsException, or AccessDeniedException while the winner's file is being read.
            tmp.delete()
            if (!file.exists()) throw e
            return false
        }
    }

    actual fun exists(path: String): Boolean = File(path).isFile

    actual fun length(path: String): Long = File(path).let { if (it.isFile) it.length() else -1L }

    actual fun lastModifiedMs(path: String): Long = File(path).lastModified()

    actual fun setLastModifiedMs(path: String, ms: Long) {
        File(path).setLastModified(ms)
    }

    actual fun delete(path: String) {
        File(path).delete()
    }
}

actual class IoPool actual constructor(name: String, threads: Int) {
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, name).apply { isDaemon = true } }

    actual fun <T, R> map(items: Collection<T>, f: (T) -> R): List<R> =
        items.map { pool.submit(Callable { f(it) }) }.map {
            try {
                it.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
}

/**
 * Threads for [parallelForEach]. T80 (found by the T82 server soak): the readers' block reads used to run on the
 * common ForkJoin pool, whose blocking-compensation grew it to its 287-thread cap under load; this pool is fixed.
 */
private const val SOURCE_IO_THREADS = 16
private const val SOURCE_IO_NAME = "source-io"

/**
 * T88: a [parallelForEach] task with the priority of the thread that fanned it out ([SourceIo.withPriority]): queued
 * tasks run higher priority first, then in the order they came. On a cold phone view the coarse tiles' block reads
 * queued behind the view's own and finished 5 s after them, too late for the contour tile waiting on them.
 */
private class SourceTask(val priority: Int, val seq: Long, body: () -> Unit) : FutureTask<Unit>(Callable { body() }), Comparable<SourceTask> {
    override fun compareTo(other: SourceTask): Int =
        if (priority != other.priority) other.priority.compareTo(priority) else seq.compareTo(other.seq)
}

private val sourceIoSeq = AtomicLong()
private val sourceIo = ThreadPoolExecutor(SOURCE_IO_THREADS, SOURCE_IO_THREADS, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>()) { r ->
    Thread(r, SOURCE_IO_NAME).apply { isDaemon = true }
}

actual fun <T> parallelForEach(items: List<T>, f: (T) -> Unit) {
    // On a pool thread already (a task fanning out again): inline, so tasks never wait on tasks of their own pool.
    if (items.size <= 1 || Thread.currentThread().name == SOURCE_IO_NAME) {
        items.forEach(f)
        return
    }
    val priority = SourceIo.currentPriority()
    items.map { item -> SourceTask(priority, sourceIoSeq.incrementAndGet()) { f(item) }.also { sourceIo.execute(it) } }.forEach {
        try {
            it.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}

actual class PerThread<T : Any> actual constructor() {
    private val local = ThreadLocal<T?>()

    actual fun get(): T? = local.get()

    actual fun set(value: T?) {
        local.set(value)
    }
}

actual fun nanoTime(): Long = System.nanoTime()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun sleepMs(ms: Long) = Thread.sleep(ms)

actual fun environment(name: String): String? = System.getenv(name)

actual fun isIoException(e: Throwable): Boolean = e is IOException

actual val zlibDecompressor: Decompressor = { input, size ->
    val inflater = Inflater()
    try {
        inflater.setInput(input)
        val out = ByteArray(size)
        var n = 0
        while (n < size && !inflater.finished()) {
            val k = inflater.inflate(out, n, size - n)
            if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            n += k
        }
        out
    } finally {
        inflater.end()
    }
}
