package io.github.jasondorjeshort.dystopo.app.share

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * NSD (mDNS / DNS-SD) for the Share screen (T54): announces this phone as `_dystopo._tcp` on the share
 * port while the screen is open, and browses for other phones doing the same, feeding [peers].
 * Resolves run one at a time (before API 34 a second concurrent resolve fails with
 * FAILURE_ALREADY_ACTIVE). Every callback only logs and records; nothing here throws into the UI.
 */
class ShareNsd(context: Context, private val peers: PeerList) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolver = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-nsd-resolve").apply { isDaemon = true } }
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    /** The name NSD registered us under (it may append a number on a clash); null when not announced. */
    @Volatile var registeredName: String? = null
        private set
    @Volatile var discovering = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var resolves = 0
        private set
    /** T90, discovery timing: when the announcement was confirmed and when browsing started (wall ms, comparable across emulators on one host). */
    @Volatile var registeredAt: Long? = null
        private set
    @Volatile var discoveryStartedAt: Long? = null
        private set
    /** T90: milliseconds of the last resolve (the request to NSD's answer) and the slowest so far. */
    @Volatile var lastResolveMs: Long? = null
        private set
    @Volatile var maxResolveMs = 0L
        private set

    @Synchronized
    fun register(name: String, port: Int, attributes: Map<String, String>) {
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
            attributes.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                registeredName = s.serviceName
                registeredAt = System.currentTimeMillis()
                peers.selfName = s.serviceName
                Log.i(TAG, "announced ${s.serviceName} on port $port")
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) { lastError = "register failed: $errorCode" }
            override fun onServiceUnregistered(s: NsdServiceInfo) { registeredName = null; registeredAt = null }
            override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) { lastError = "unregister failed: $errorCode" }
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { lastError = "register: ${it.message}"; registration = null }
    }

    @Synchronized
    fun unregister() {
        registration?.let { l -> runCatching { nsd.unregisterService(l) } }
        registration = null
        registeredName = null
        peers.selfName = null
    }

    @Synchronized
    fun startDiscovery() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { discovering = true; discoveryStartedAt = System.currentTimeMillis() }
            override fun onDiscoveryStopped(serviceType: String) { discovering = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { lastError = "discovery failed: $errorCode"; discovering = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { lastError = "stop discovery failed: $errorCode" }
            override fun onServiceFound(s: NsdServiceInfo) {
                peers.found(s.serviceName)
                resolver.execute { resolve(s) }
            }
            override fun onServiceLost(s: NsdServiceInfo) = peers.lost(s.serviceName)
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { lastError = "discover: ${it.message}"; discovery = null }
    }

    @Synchronized
    fun stopDiscovery() {
        discovery?.let { l -> runCatching { nsd.stopServiceDiscovery(l) } }
        discovery = null
        discovering = false
        peers.clear()
    }

    @Suppress("DEPRECATION") // resolveService: its API 34 replacement (registerServiceInfoCallback) is not on minSdk 26
    private fun resolve(s: NsdServiceInfo) {
        val done = CountDownLatch(1)
        val t0 = System.currentTimeMillis()
        runCatching {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    lastError = "resolve ${info.serviceName} failed: $errorCode"
                    done.countDown()
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    val ms = System.currentTimeMillis() - t0
                    lastResolveMs = ms
                    if (ms > maxResolveMs) maxResolveMs = ms
                    if (host != null) peers.resolved(info.serviceName, host, info.port)
                    resolves++
                    done.countDown()
                }
            })
        }.onFailure { done.countDown() }
        done.await(10, TimeUnit.SECONDS)
    }

    companion object {
        const val TAG = "DystopoShare"
        const val SERVICE_TYPE = "_dystopo._tcp"
    }
}
