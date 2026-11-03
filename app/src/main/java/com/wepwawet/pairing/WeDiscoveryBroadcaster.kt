package com.wepwawet.pairing

import android.content.Context
import android.net.wifi.WifiManager
import com.wepwawet.log.WepWawetLogger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.concurrent.thread

/**
 * WE Discovery Broadcaster — broadcasts "WallpaperEngine/Ping" on UDP 7884.
 *
 * This makes our app discoverable by the official WE Android app's DiscoverService.
 * When the user opens "Pair with Computer" in the WE Android app, it listens for
 * these pings on UDP 7884 multicast group 239.100.0.1.
 *
 * Payload format (from decompiled DiscoverService.java):
 *   "WallpaperEngine/Ping:<pid>:<hostname>:<flag>:<interfaceId>"
 *
 * Fields:
 *   pid: Process ID (5 digits) — we use a random 5-digit number
 *   hostname: Device name — we use "WepWawet"
 *   flag: Usually "1"
 *   interfaceId: Network interface ID — we use "lo" (loopback) for self-pair
 *
 * The WE Android app parses this and creates a DeviceItem with:
 *   deviceName = hostname
 *   ipAddress = source IP of the UDP packet
 *   port = 7889 (implied — the TCP pairing port)
 */
class WeDiscoveryBroadcaster(private val context: Context) {

    companion object {
        const val UDP_PORT = 7884
        const val MULTICAST_GROUP = "239.100.0.1"
        private const val BROADCAST_INTERVAL_MS = 1300L  // VERIFIED from Wireshark: PC broadcasts every ~1.3s
    }

    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    private var broadcastThread: Thread? = null

    /**
     * Whether the broadcaster is currently running (between [start] and [stop]).
     *
     * v0.7.1 (round 6 — pairing persistence): exposed so [PairingService]
     * can decide whether to restart the broadcaster vs. the full server
     * stack when [PairingService.onStartCommand] is called repeatedly
     * (e.g. from MainActivity.onCreate + PairingScreen appearance).
     */
    val isRunning: Boolean get() = running

    /**
     * Start broadcasting "WallpaperEngine/Ping" UDP packets on UDP 7884.
     *
     * v0.7.1 (round 6 — pairing persistence): the [guid] parameter is
     * REQUIRED and must come from [PairingIdentity.getGuid] (persistent
     * across app restarts). Previously this generated a fresh random UUID
     * on every start, so the phone saw a brand-new computer after every
     * restart/update and re-prompted for PIN even though the user had
     * already paired.
     *
     * @param hostname the device hostname broadcast in the ping (default "WepWawet").
     * @param guid the persistent device GUID from [PairingIdentity.getGuid].
     *   Must be non-blank — passing an empty guid would corrupt the wire
     *   format ("WallpaperEngine/Ping:07889:WepWawet:4:") and the phone's
     *   DiscoverService would discard the packet.
     */
    fun start(hostname: String = "WepWawet", guid: String) {
        require(guid.isNotBlank()) {
            "WeDiscoveryBroadcaster.start: guid must not be blank — pass PairingIdentity.getGuid(context)"
        }
        if (running) {
            WepWawetLogger.proxyWarn("Discovery broadcaster already running",
                "guid" to guid)
            return
        }
        running = true
        WepWawetLogger.proxy("Discovery broadcaster starting",
            "hostname" to hostname,
            "guid" to guid,
            "udp_port" to UDP_PORT,
            "multicast_group" to MULTICAST_GROUP)

        broadcastThread = thread(start = true, name = "wep-discovery-bcast", isDaemon = true) {
            broadcastLoop(hostname, guid)
        }
    }

    fun stop() {
        running = false
        broadcastThread?.interrupt()
        runCatching { multicastSocket?.close() }
        runCatching { multicastLock?.let { if (it.isHeld) it.release() } }
        multicastSocket = null
        multicastLock = null
        WepWawetLogger.proxy("Discovery broadcaster stopped")
    }

    private fun broadcastLoop(hostname: String, guid: String) {
        try {
            // Acquire multicast lock so the kernel doesn't drop multicast packets
            acquireMulticastLock()

            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(UDP_PORT))
                timeToLive = 255
            }
            multicastSocket = socket

            val group = InetAddress.getByName(MULTICAST_GROUP)
            // VERIFIED from decompiled DiscoverService.java:
            //   parts[1] = TCP port (5 chars, zero-padded) — phone does parseInt(parts[1])
            //   parts[3] = protocolVersion — phone checks protocolVersion == 4 (DiscoverDevicesActivity line 181)
            //   parts[4] = deviceGuid (UUID string)
            // Real PC sends: "WallpaperEngine/Ping:07889:LAPTOP-NAME:4:5FB2F0F0-..."
            // Our previous code sent ":1:" for protocolVersion → phone showed "different version" error!
            //
            // v0.7.1 (round 6): the guid is now passed in from
            // [PairingIdentity.getGuid] (persistent across app restarts)
            // — NO MORE `java.util.UUID.randomUUID().toString().uppercase()`
            // here. The phone's pairing database is keyed by this GUID, so
            // a stable GUID means the phone recognizes this computer after
            // restarts/updates and doesn't re-prompt for PIN.
            val payload = "WallpaperEngine/Ping:07889:$hostname:4:$guid"
            val bytes = payload.toByteArray(Charsets.US_ASCII)

            WepWawetLogger.proxy("Discovery broadcaster listening on UDP $UDP_PORT",
                "payload" to payload)

            // Also listen for incoming UDP (the phone may send a Pong)
            socket.soTimeout = 1000  // wake up every 1s to check running

            while (running) {
                // v0.7.4: the loop used to spend up to 1s blocking in receive()
                // PLUS the fixed 1.3s sleep — an actual broadcast cadence of
                // ~2.3s, not the intended ~1.3s (verified from Wireshark capture
                // of a real PC). We now measure the elapsed time and sleep only
                // the REMAINDER of the interval so the ping goes out every
                // ~BROADCAST_INTERVAL_MS regardless of how long receive() blocked.
                val iterationStart = System.currentTimeMillis()
                try {
                    // Broadcast to multicast group only — multicast is the correct
                    // transport for service discovery. Subnet broadcast (255.255.255.255)
                    // was removed because it wakes Wi-Fi radios on every LAN device every
                    // 1.3s. Loopback send was removed because it was delivered to itself,
                    // polluting logs with self-reception.
                    val pkt = DatagramPacket(bytes, bytes.size, group, UDP_PORT)
                    socket.send(pkt)

                    WepWawetLogger.proxyDebug("broadcast sent",
                        "payload" to payload)
                } catch (e: Exception) {
                    if (running) {
                        WepWawetLogger.proxyWarn("broadcast iteration failed",
                            "exception" to e.javaClass.name)
                    }
                }

                // Listen for incoming UDP (phone's Pong or other discovery replies)
                try {
                    val rxPkt = DatagramPacket(ByteArray(256), 256)
                    socket.receive(rxPkt)
                    val rxPayload = String(rxPkt.data, 0, rxPkt.length, Charsets.US_ASCII)
                    val src = rxPkt.address.hostAddress ?: "?"
                    WepWawetLogger.proxyDebug("received UDP reply",
                        "src" to src,
                        "port" to rxPkt.port,
                        "payload" to rxPayload)
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal — no reply, keep broadcasting
                }

                val elapsed = System.currentTimeMillis() - iterationStart
                val sleepTime = (BROADCAST_INTERVAL_MS - elapsed).coerceAtLeast(0L)
                try {
                    Thread.sleep(sleepTime)
                } catch (_: InterruptedException) {
                    return
                }
            }
            // NOTE: socket.close() moved to finally block (audit M1 fix below).
        } catch (e: Exception) {
            if (running) {
                WepWawetLogger.proxyError("Discovery broadcaster error",
                    "exception" to e.javaClass.name, exception = e)
            }
        } finally {
            // v0.5.32 (audit M1): Always close the socket + release the
            // multicast lock in finally, regardless of how the loop exits.
            //
            // Previously socket.close() was at line 136 (after the while
            // loop), so it only ran when the loop exited normally
            // (running=false). If anything threw an uncaught exception
            // — e.g., socket.receive() throwing a non-SocketTimeoutException
            // (the inner catch only handles SocketTimeoutException, so
            // SocketException from stop() closing the socket mid-receive
            // propagates to the outer catch), or Thread.sleep() throwing
            // InterruptedException — the socket file descriptor leaked
            // until GC finalized it (which on Android can take minutes).
            //
            // stop() may have already closed + nulled multicastSocket;
            // runCatching handles that gracefully (no-op on null/closed).
            runCatching { multicastSocket?.close() }
            multicastSocket = null
            runCatching { multicastLock?.let { if (it.isHeld) it.release() } }
            // v0.7.24 (H-P7 BUG FIX): the loop's catch never reset `running`,
            // so after any exception (e.g. a bind failure) isRunning kept
            // returning true and PairingService's smart-restart NEVER
            // relaunched discovery — the phone could never find this PC
            // again until the whole app restarted. Reset unconditionally:
            // the thread only reaches finally when it's done for good.
            running = false
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("wepwawet:discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            WepWawetLogger.proxyDebug("multicast lock acquired")
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("multicast lock acquire failed",
                "exception" to e.javaClass.name)
        }
    }
}
