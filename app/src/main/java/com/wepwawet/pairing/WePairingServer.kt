package com.wepwawet.pairing

import android.util.Log
import com.google.gson.Gson
import com.wepwawet.log.WepWawetLogger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * WE Pairing Server — acts as the "PC" side of the WE pairing protocol.
 *
 * This is the heart of the new WepWawet architecture. Instead of running a
 * PC client, this server NATIVELY speaks the WE pairing protocol. The official
 * WE Android app discovers and pairs with this server exactly as it would pair
 * with a real PC running Wallpaper Engine.
 *
 * Protocol (reverse-engineered from the WE Android APK decompiled with jadx):
 *
 * 1. DISCOVERY (UDP 7884):
 *    - Server broadcasts "WallpaperEngine/Ping:<pid>:<hostname>:1:<ifaceId>"
 *      via UDP multicast to 239.100.0.1:7884 every 5 seconds
 *    - Phone's DiscoverService listens and finds the server
 *
 * 2. PAIRING (TCP 7889):
 *    - Phone connects to server on TCP 7889
 *    - Server sends RSA public key (exponent + modulus as BigInteger)
 *    - Phone generates AES key + IV
 *    - Phone encrypts AES key with server's RSA public key → sends to server
 *    - Phone encrypts EncAuthRequest (JSON) with AES/CBC/PKCS5Padding → sends to server
 *    - Server decrypts AES key with RSA private key
 *    - Server decrypts EncAuthRequest with AES
 *    - Server verifies PIN
 *    - Server encrypts EncAuthResponse (JSON) with AES → sends to phone
 *    - All subsequent communication is AES-encrypted
 *
 * 3. FILE TRANSFER:
 *    - Phone requests wallpaper download
 *    - Server sends .mpkg file via AES-encrypted ranged transfer
 *    - Uses writeInt(length) + ciphertext format for all messages
 *
 * Wire format for encrypted messages:
 *   writeInt(ciphertext_length)   // 4 bytes, LITTLE-ENDIAN (verified from decompiled
 *                                 // TcpClient$Companion.writeInt — output.writeByte(value & 255)
 *                                 // then >> 8, >> 16, >> 24 — that's little-endian!)
 *   write(ciphertext)             // AES/CBC/PKCS5Padding encrypted JSON
 *
 * CRITICAL: The WE Android app's TcpClient$Companion.readInt/writeInt use
 * LITTLE-ENDIAN byte order (ByteBuffer.order(LITTLE_ENDIAN) and shift-down
 * writeByte calls). Java's DataInputStream.readInt() and DataOutputStream.writeInt()
 * use BIG-ENDIAN. So we MUST use our own readLeInt()/writeLeInt() helpers
 * for ALL length prefixes, otherwise the server misreads every message length
 * and pairing fails silently.
 *
 * Heartbeat: After pairing succeeds, the phone's connector thread sends
 * "heartbeat\n" (10 ASCII bytes, no length prefix) every ~3 seconds.
 * The server must detect and skip these frames, otherwise readLeInt() would
 * interpret 'h','e','a','r' as a 4-byte length and bail out.
 *
 * Constants from decompiled TcpClient.java:
 *   MAX_SAFE_COMMAND_LENGTH = 4096
 *   MAX_SAFE_DATA_LENGTH = 1048572  (1 MB)
 */
class WePairingServer {

    private val gson = com.google.gson.Gson()

    companion object {
        private const val TAG = "WEP-PAIRING"
        const val TCP_PORT = 7889

        /**
         * v0.7.24 (H-P8): Max simultaneously-live pairing connections. The
         * proxy speaks to ONE phone at a time; a small cap absorbs the WE
         * app's reconnect churn while stopping runaway thread spawn from
         * scanners.
         */
        const val MAX_CONCURRENT_CONNECTIONS = 4
        const val MAX_SAFE_COMMAND_LENGTH = 4096
        // VERIFIED from decompiled TcpClient.java (headless RE):
        //   MAX_SAFE_COMMAND_LENGTH = 4096
        //   MAX_SAFE_DATA_LENGTH = 1048572  (1 MiB - 4 KiB)
        // v0.6.0 (parity §3 C-2-1): corrected off-by-20 bug — was 1048592
        const val MAX_SAFE_DATA_LENGTH = 1048572
        // VERIFIED from real capture: PC sent 128-byte modulus = RSA-1024
        // (was 2048 in earlier code — WRONG)
        private const val RSA_KEY_SIZE = 1024
        private const val RSA_BLOCK_SIZE = 128  // bytes per RSA-1024 ciphertext
        // AES key size — actual size confirmed by Frida hooks pending;
        // AES supports 128/192/256-bit. Default 256.
        private const val AES_KEY_SIZE = 256
        private const val AES_IV_SIZE = 16
        // VERIFIED from decompiled TransferService.java
        private const val CONNECTION_PROTOCOL_VERSION = 4

        // ════════════════════════════════════════════════════════════════
        // v0.7.4 (parity G1): the .mpkg container version the currently-
        // paired phone reported in its EncAuthRequest
        // ("mpkgVersion":"PKGMdddd"). 0 = no phone paired yet (or it
        // reported something unparseable) — [MpkgPacker.negotiateMagic]
        // then falls back to PKGM0020, the behavior of every previous
        // WepWawet release.
        //
        // Set when a pairing succeeds (AuthOK), kept across a phone
        // disconnect (a queued .mpkg may still be picked up by the next
        // heartbeat; the next phone to pair overwrites it), reset when the
        // server stops. The PC does the same thing per-device through its
        // persisted paired-devices configuration before selecting the
        // per-transfer magic (FUN_14003d0e0: scene → device version,
        // video → unconditionally 14).
        // ════════════════════════════════════════════════════════════════
        @Volatile
        var negotiatedMpkgVersion: Int = 0
            private set
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var rsaKeyPair: KeyPair? = null

    // Current pairing state
    // v0.7.24 (C-P2/M-P4/M-P5): @Volatile — these are written by the pairing
    // handshake thread and read by the command-loop + transfer threads.
    @Volatile private var currentPin: String = ""
    @Volatile private var currentAesKey: SecretKey? = null
    @Volatile private var currentIv: IvParameterSpec? = null
    @Volatile private var currentClient: Socket? = null

    // v0.7.24 (H-P8): bounded connection acceptance. The old accept loop
    // spawned an unbounded thread per incoming TCP connection — a scanner
    // (or the WE app stuck in a reconnect loop) could spawn hundreds of
    // threads each blocking on readFully. Beyond MAX_CONCURRENT_CONNECTIONS
    // live connections, new sockets are closed immediately.
    private val liveConnections = java.util.concurrent.atomic.AtomicInteger(0)

    // Callbacks
    var onPinRequested: (() -> String)? = null
    var onPairingSuccess: ((String) -> Unit)? = null  // deviceName
    var onPairingFailure: ((String) -> Unit)? = null  // errorMessage
    var onWallpaperRequested: ((String) -> String?)? = null  // wallpaperId -> mpkgFilePath
    var onConnected: (() -> Unit)? = null  // called when command loop starts

    /**
     * v0.7.1 (round 6 — pairing persistence): invoked after the command
     * loop returns (i.e. when the phone disconnects, the socket closes,
     * or the loop throws), but ONLY when the disconnecting client is the
     * CURRENT client (i.e. the most recent pairing). The callback lets
     * [PairingService] clear its `pairedDeviceName` static state and
     * update the foreground notification so the UI shows "Paired: "
     * only while a phone is actually connected — not stale-forever after
     * the phone closes the app.
     *
     * The check `currentClient === client` is essential — if a NEW phone
     * paired (replacing an old connection), we don't want to clear state
     * for the NEW phone just because the OLD connection's command loop
     * finally returned.
     */
    var onDisconnected: (() -> Unit)? = null

    /**
     * Whether the pairing server is currently running (between [start]
     * and [stop]). Exposed so [PairingService] can decide whether to
     * fully restart vs. just restart the discovery broadcaster when
     * onStartCommand is called repeatedly.
     */
    val isRunning: Boolean get() = running

    /**
     * Generate an RSA keypair for pairing.
     * The public key will be sent to the phone during pairing.
     * The private key is used to decrypt the phone's AES key.
     *
     * v0.7.1 (round 6): this method is now INTERNAL — callers should
     * pass a persistent [KeyPair] (from [PairingIdentity.loadOrCreateKeyPair])
     * into [start] instead of letting the server generate a fresh one
     * on every restart. Kept for backward compatibility / fallback if
     * no keypair is supplied.
     */
    fun generateRsaKeyPair(): KeyPair {
        WepWawetLogger.proxy("Generating RSA keypair ($RSA_KEY_SIZE bits)")
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_SIZE)
        val keyPair = generator.generateKeyPair()
        rsaKeyPair = keyPair

        // Log the public key components (for debugging)
        val pubKey = keyPair.public as java.security.interfaces.RSAPublicKey
        WepWawetLogger.proxyDebug("RSA keypair generated",
            "modulus_bits" to pubKey.modulus.bitLength(),
            "exponent" to pubKey.publicExponent.toString(16).take(16))

        return keyPair
    }

    /**
     * Get the RSA public key as BigInteger components.
     * These are sent to the phone during pairing.
     *
     * v0.7.1 (round 6): if [rsaKeyPair] is null (no keypair was supplied
     * to [start] AND generateRsaKeyPair() hasn't been called yet), this
     * will lazily generate one — but callers should always pass a
     * persistent keypair from [PairingIdentity.loadOrCreateKeyPair].
     */
    fun getRsaPublicKeyComponents(): Pair<BigInteger, BigInteger> {
        val keyPair = rsaKeyPair ?: generateRsaKeyPair()
        val pubKey = keyPair.public as java.security.interfaces.RSAPublicKey
        return Pair(pubKey.modulus, pubKey.publicExponent)
    }

    /**
     * Start the pairing server.
     * Listens on TCP 7889 for incoming connections from the WE Android app.
     *
     * v0.7.1 (round 6 — pairing persistence): the [keyPair] parameter is
     * OPTIONAL but should be supplied from [PairingIdentity.loadOrCreateKeyPair]
     * so the same RSA public key is presented to the phone across app
     * restarts/updates — otherwise the phone sees a brand-new computer
     * (different RSA pubkey = different "computer identity" in the
     * phone's pairing DB) and re-prompts for PIN even though the user
     * already paired.
     *
     * v0.7.1 (round 6): the ServerSocket now sets `reuseAddress = true`
     * AND retries the TCP 7889 bind up to 5 times (1s apart) on
     * [java.net.BindException]. The retry handles the case where a
     * previous service instance's socket is in TIME_WAIT (Android's
     * default 2MSL is 60s on TCP 7889) — without retry, the new
     * foreground service would fail to bind and the user couldn't pair
     * for up to a minute after force-stopping the app.
     */
    fun start(pin: String, keyPair: KeyPair? = null) {
        if (running) {
            WepWawetLogger.proxyWarn("Pairing server already running")
            return
        }
        running = true
        currentPin = pin

        // Use the supplied keypair (persistent from PairingIdentity) if
        // provided; otherwise generate a fresh one (this is the fallback
        // for backward compatibility — callers SHOULD always supply one).
        rsaKeyPair = keyPair ?: generateRsaKeyPair()

        WepWawetLogger.proxy("Pairing server starting",
            "port" to TCP_PORT,
            // v0.7.24 (C-P3): PIN redacted — with the default PIN "1234"
            // this was a trivial device-takeover vector for anyone reading
            // the logs. Log only whether a custom PIN is set.
            "pin" to if (pin == "1234") "default" else "custom(${pin.length})",
            "rsa_key_size" to RSA_KEY_SIZE,
            "rsa_keypair_source" to if (keyPair != null) "external (persistent)" else "generated (ephemeral)")

        thread(start = true, name = "wep-pairing-server", isDaemon = true) {
            try {
                // v0.7.1 (round 6): retry the bind on BindException up to
                // 5 times, 1s apart. Android's TCP stack keeps a closed
                // socket in TIME_WAIT for up to 60s (2*MSL); without retry,
                // a fresh foreground service instance started within 60s
                // of the previous one stopping would fail to bind and
                // the user couldn't pair until the OS released the port.
                var boundSocket: ServerSocket? = null
                var bindAttempts = 0
                val maxBindAttempts = 5
                while (boundSocket == null && bindAttempts < maxBindAttempts) {
                    bindAttempts++
                    try {
                        boundSocket = ServerSocket().apply {
                            // reuseAddress allows rebinding a port that's
                            // still in TIME_WAIT — this is critical for
                            // foreground-service restarts.
                            reuseAddress = true
                            bind(java.net.InetSocketAddress(TCP_PORT))
                            soTimeout = 0  // block forever
                        }
                    } catch (b: java.net.BindException) {
                        if (bindAttempts >= maxBindAttempts) {
                            throw b
                        }
                        WepWawetLogger.proxyWarn("Pairing server bind failed — retrying",
                            "port" to TCP_PORT,
                            "attempt" to bindAttempts,
                            "max" to maxBindAttempts,
                            "exception" to b.javaClass.name,
                            "message" to (b.message ?: ""))
                        Thread.sleep(1000)
                    }
                }
                serverSocket = boundSocket
                WepWawetLogger.proxy("Pairing server listening on TCP $TCP_PORT",
                    "bind_attempts" to bindAttempts)

                while (running) {
                    val client = serverSocket?.accept() ?: break
                    WepWawetLogger.proxy("Incoming connection",
                        "remote" to (client.remoteSocketAddress.toString()))
                    // v0.7.24 (H-P8): reject connections beyond the cap.
                    if (liveConnections.incrementAndGet() > MAX_CONCURRENT_CONNECTIONS) {
                        liveConnections.decrementAndGet()
                        WepWawetLogger.proxyWarn("Connection rejected — too many live connections",
                            "live" to liveConnections.get(),
                            "max" to MAX_CONCURRENT_CONNECTIONS)
                        runCatching { client.close() }
                        continue
                    }
                    thread(start = true, name = "wep-pairing-conn", isDaemon = true) {
                        try {
                            handleConnection(client)
                        } finally {
                            liveConnections.decrementAndGet()
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    WepWawetLogger.proxyError("Pairing server error",
                        "exception" to e.javaClass.name, exception = e)
                }
            } finally {
                serverSocket?.close()
                running = false
                WepWawetLogger.proxy("Pairing server stopped")
            }
        }
    }

    fun stop() {
        running = false
        serverSocket?.close()
        currentClient?.close()
        currentClient = null
        currentAesKey = null
        currentIv = null
        // v0.7.4 (parity G1): a stopped server has no paired phone — clear
        // the negotiated .mpkg version so packers fall back to PKGM0020
        // until the next phone pairs and reports its own version.
        negotiatedMpkgVersion = 0
        WepWawetLogger.proxy("Pairing server stop requested",
            "negotiated_mpkg_version_reset" to true)
    }

    /**
     * Write a 4-byte LITTLE-ENDIAN integer to the output stream.
     *
     * Mirrors the WE Android app's TcpClient$Companion.writeInt exactly:
     *   output.writeByte(value & 255)
     *   output.writeByte((value >> 8) & 255)
     *   output.writeByte((value >> 16) & 255)
     *   output.writeByte((value >> 24) & 255)
     *
     * If you use DataOutputStream.writeInt() instead, that uses BIG-ENDIAN,
     * and the phone will misread every length prefix — pairing will fail
     * silently with a 0 or huge bogus length.
     */
    private fun writeLeInt(output: DataOutputStream, value: Int) {
        output.writeByte(value and 0xFF)
        output.writeByte((value shr 8) and 0xFF)
        output.writeByte((value shr 16) and 0xFF)
        output.writeByte((value shr 24) and 0xFF)
    }

    /**
     * Strip leading zero bytes from a BigInteger.toByteArray() result.
     * The real protocol sends the modulus as raw bytes WITHOUT the sign byte
     * that Java's BigInteger.toByteArray() prepends for positive numbers with
     * the high bit set.
     */
    private fun stripLeadingZero(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte()) {
            start++
        }
        return bytes.copyOfRange(start, bytes.size)
    }

    /**
     * Read a length-prefixed message: 4-byte LE length + N bytes.
     * Returns null on invalid length (caller should close the connection).
     */
    private fun readLengthPrefixed(input: DataInputStream, label: String): ByteArray? {
        val length = readLeInt(input)
        WepWawetLogger.proxy("Reading length-prefixed: $label", "length" to length)
        if (length <= 0 || length > MAX_SAFE_DATA_LENGTH) {
            WepWawetLogger.proxyError("Invalid $label length: $length")
            return null
        }
        val data = ByteArray(length)
        input.readFully(data)
        return data
    }

    /**
     * Read a 4-byte LITTLE-ENDIAN integer from the input stream.
     *
     * Mirrors the WE Android app's TcpClient$Companion.readInt exactly:
     *   byte[] bArr = new byte[4];
     *   input.read(bArr, 0, 4);
     *   return ByteBuffer.wrap(bArr).order(LITTLE_ENDIAN).getInt();
     *
     * If you use DataInputStream.readInt() instead, that uses BIG-ENDIAN,
     * and you'll misread every length prefix from the phone.
     */
    private fun readLeInt(input: DataInputStream): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return (b[0].toInt() and 0xFF) or
               ((b[1].toInt() and 0xFF) shl 8) or
               ((b[2].toInt() and 0xFF) shl 16) or
               ((b[3].toInt() and 0xFF) shl 24)
    }

    /**
     * Read a length-prefixed message, gracefully skipping 'heartbeat\n'
     * keepalive frames that the phone's connector thread sends every ~3s
     * after pairing. Without this, readLeInt() would read 'h','e','a','r'
     * (0x68, 0x65, 0x61, 0x72) → little-endian 0x72616568 = 1,919,014,504,
     * which exceeds MAX_SAFE_DATA_LENGTH and breaks the command loop.
     *
     * Strategy: peek the first 4 bytes. If they spell ASCII "hear",
     * consume the remaining 6 bytes ("tbeat\n") and return a sentinel
     * indicating a heartbeat was seen.
     */
    private fun readMessageOrHeartbeat(input: DataInputStream): Pair<String, ByteArray?> {
        val header = ByteArray(4)
        input.readFully(header)
        // Check for 'h','e','a','r' = first 4 bytes of "heartbeat\n"
        if (header[0] == 0x68.toByte() && header[1] == 0x65.toByte() &&
            header[2] == 0x61.toByte() && header[3] == 0x72.toByte()) {
            // Consume the remaining 6 bytes of "heartbeat\n": t,b,e,a,t,\n
            val rest = ByteArray(6)
            input.readFully(rest)
            // Verify the remaining bytes match "tbeat\n" exactly — if they
            // don't, the stream is out of sync and we should close the connection
            // rather than silently consume malformed data.
            if (rest[0] == 't'.code.toByte() && rest[1] == 'b'.code.toByte() &&
                rest[2] == 'e'.code.toByte() && rest[3] == 'a'.code.toByte() &&
                rest[4] == 't'.code.toByte() && rest[5] == '\n'.code.toByte()) {
                return "heartbeat" to null
            } else {
                // Malformed heartbeat — stream is out of sync
                WepWawetLogger.proxyWarn("malformed heartbeat — closing connection",
                    "rest_hex" to rest.joinToString("") { "%02x".format(it) })
                return "invalid" to null
            }
        }
        // Otherwise treat as little-endian length prefix
        val length = (header[0].toInt() and 0xFF) or
                     ((header[1].toInt() and 0xFF) shl 8) or
                     ((header[2].toInt() and 0xFF) shl 16) or
                     ((header[3].toInt() and 0xFF) shl 24)
        if (length <= 0 || length > MAX_SAFE_DATA_LENGTH) {
            return "invalid" to null
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        return "message" to payload
    }

    /**
     * Handle a single pairing connection from the WE Android app.
     *
     * This implements the full pairing handshake:
     * 1. Send RSA public key (modulus + exponent, length-prefixed LE)
     * 2. Receive encrypted AES key (RSA-encrypted, length-prefixed LE)
     * 3. Receive AES IV (16 raw bytes, length-prefixed LE)
     * 4. Decrypt AES key with RSA private key (RSA/ECB/OAEPPadding)
     *    VERIFIED from decompiled TcpClient line 541
     * 5. Receive encrypted EncAuthRequest (AES/CBC/PKCS5Padding)
     * 6. Decrypt with AES, parse JSON, verify PIN
     * 7. Send encrypted EncAuthResponse (AES/CBC/PKCS5Padding)
     * 8. Enter command loop (skips heartbeat frames)
     */
    private fun handleConnection(client: Socket) {
        var pairingSuccess = false
        try {
            client.use {
                // Set TCP keepalive + timeouts
                it.keepAlive = true
                it.soTimeout = 60000  // 60s timeout (phone blocks on commandQueue until PIN entered)

                // CRITICAL: Use BufferedOutputStream to avoid TCP fragmentation.
                // Without buffering, writeLeInt sends 4 individual bytes via writeByte,
                // creating 4 tiny TCP segments. The phone may time out waiting for
                // the complete length prefix.
                val input = DataInputStream(java.io.BufferedInputStream(it.getInputStream()))
                val output = DataOutputStream(java.io.BufferedOutputStream(it.getOutputStream()))

                WepWawetLogger.proxy("Handling pairing connection",
                    "remote" to it.remoteSocketAddress.toString())

                // === PC → phone: Send RSA exponent FIRST (as 3-byte BE BigInteger) ===
                // VERIFIED from real capture: Frame 0 = length=3 + bytes `01 00 01` = 65537
                // (was modulus-first in earlier code — WRONG)
                val (modulus, exponent) = getRsaPublicKeyComponents()
                // v0.6.0 (parity §3 C-2-2): RSA exponent is always sent as 3 bytes BE.
                // For exponent 65537 (0x010001) BigInteger.toByteArray() returns 3 bytes,
                // but for smaller exponents (e.g., 3 → [0x03]) we must left-pad to 3 bytes
                // to match the wire format the phone's TcpClient expects.
                val expBytesRaw = stripLeadingZero(exponent.toByteArray())
                val expBytes = if (expBytesRaw.size < 3) {
                    ByteArray(3 - expBytesRaw.size) + expBytesRaw
                } else {
                    expBytesRaw
                }
                WepWawetLogger.proxy("Sending RSA exponent",
                    "exponent" to exponent.toString(16),
                    "wire_bytes" to expBytes.size,
                    "wire_hex" to expBytes.joinToString("") { "%02x".format(it) })
                writeLeInt(output, expBytes.size)
                output.write(expBytes)
                output.flush()

                // === PC → phone: Send RSA modulus (128 raw BE bytes for RSA-1024) ===
                // VERIFIED: 128 bytes, no sign byte (BigInteger.toByteArray may include
                // a leading 0x00 sign byte — strip it).
                val modBytes = stripLeadingZero(modulus.toByteArray())
                // Pad to exactly 128 bytes if needed (left-pad with zeros)
                val modPadded = if (modBytes.size < RSA_BLOCK_SIZE) {
                    ByteArray(RSA_BLOCK_SIZE - modBytes.size) + modBytes
                } else {
                    modBytes
                }
                WepWawetLogger.proxy("Sending RSA modulus",
                    "modulus_bits" to modulus.bitLength(),
                    "wire_bytes" to modPadded.size,
                    "wire_hex_first_16" to modPadded.take(16).joinToString("") { "%02x".format(it) })
                writeLeInt(output, modPadded.size)
                output.write(modPadded)
                output.flush()

                // === Phone → PC: Receive 3 RSA-encrypted frames (128B each) ===
                // VERIFIED from decompiled TcpClient connector$lambda$2:
                //   Frame 0: RSA-OAEP-encrypted deviceUUID (line 548-558)
                //   Frame 1: RSA-OAEP-encrypted AES IV (line 578-586)
                //   Frame 2: RSA-OAEP-encrypted AES key (line 588-596)
                // The phone sends UUID FIRST, then IV, then AES key.
                // (Our previous code read them as AES key → IV → third — WRONG!)
                val encDeviceUUID = readLengthPrefixed(input, "RSA-encrypted deviceUUID")
                    ?: return
                val encIv = readLengthPrefixed(input, "RSA-encrypted AES IV")
                    ?: return
                val encAesKey = readLengthPrefixed(input, "RSA-encrypted AES key")
                    ?: return

                // Decrypt with RSA/ECB/OAEPPadding — VERIFIED from decompiled
                // TcpClient.connector$lambda$2 line 541:
                //   Cipher.getInstance("RSA/ECB/OAEPPadding")
                // NOT PKCS1Padding — using the wrong padding = pairing failure!
                val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
                rsaCipher.init(Cipher.DECRYPT_MODE, rsaKeyPair!!.private)
                val deviceUUIDBytes = rsaCipher.doFinal(encDeviceUUID)
                val ivBytes = rsaCipher.doFinal(encIv)
                val aesKeyBytes = rsaCipher.doFinal(encAesKey)

                currentAesKey = SecretKeySpec(aesKeyBytes, "AES")
                currentIv = IvParameterSpec(ivBytes)

                WepWawetLogger.proxy("AES key exchange complete",
                    "aes_key_bits" to (aesKeyBytes.size * 8),
                    "iv_bytes" to ivBytes.size,
                    "device_uuid" to String(deviceUUIDBytes, Charsets.UTF_8),
                    "device_uuid_bytes" to deviceUUIDBytes.size)

                // Step 4: Receive encrypted EncAuthRequest (length LITTLE-ENDIAN!)
                val aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                aesCipher.init(Cipher.DECRYPT_MODE, currentAesKey, currentIv)

                val encAuthReqLen = readLeInt(input)
                WepWawetLogger.proxyDebug("Received encrypted EncAuthRequest",
                    "length" to encAuthReqLen)
                if (encAuthReqLen <= 0 || encAuthReqLen > MAX_SAFE_DATA_LENGTH) {
                    WepWawetLogger.proxyError("Invalid EncAuthRequest length: $encAuthReqLen")
                    return
                }
                val encAuthReqBytes = ByteArray(encAuthReqLen)
                input.readFully(encAuthReqBytes)

                val authReqJson = String(aesCipher.doFinal(encAuthReqBytes), Charsets.UTF_8)
                // v0.7.24 (C-P3): the raw authReqJson contains the PIN in
                // cleartext — log a REDACTED copy instead.
                WepWawetLogger.proxy("Received EncAuthRequest",
                    "json" to authReqJson.replace(Regex("\"pin\"\\s*:\\s*\"[^\"]*\""), "\"pin\":\"***\""))

                // Step 5: Parse EncAuthRequest (JSON via Gson)
                // Expected fields: pin, name, deviceUserName, displayWidth, displayHeight,
                    // version, mpkgVersion, appVersion
                val gson = com.google.gson.Gson()
                val authReq = gson.fromJson(authReqJson, EncAuthRequest::class.java)

                WepWawetLogger.proxy("EncAuthRequest parsed",
                    "pin" to if ((authReq.pin ?: "").isEmpty()) "none" else "redacted",
                    "name" to (authReq.name ?: ""),
                    "deviceUserName" to (authReq.deviceUserName ?: ""),
                    "displayWidth" to (authReq.displayWidth ?: 0),
                    "displayHeight" to (authReq.displayHeight ?: 0),
                    "version" to (authReq.version ?: 0),
                    "mpkgVersion" to (authReq.mpkgVersion ?: ""),
                    "appVersion" to (authReq.appVersion ?: ""))

                // Step 6: Verify PIN and version
                val pinValid = authReq.pin == currentPin
                WepWawetLogger.proxy("PIN verification",
                    // v0.7.24 (C-P3): never log either PIN — the outcome
                    // ("valid") is all the debugging value anyone needs.
                    "received_pin" to "redacted",
                    "expected_pin" to "redacted",
                    "valid" to pinValid)

                // v0.6.0 (parity §3 C-2-3): send AuthFailedVersion when the phone's
                // protocol/mpkg version is wrong, AuthFailed for invalid PIN. The
                // phone's UI shows different prompts for each.
                val versionOk = (authReq.version ?: 0) == CONNECTION_PROTOCOL_VERSION
                val mpkgVersionOk = authReq.mpkgVersion?.let { ver ->
                    // libscenejni.so uses integer version comparison (no hardcoded
                    // string per binary strings analysis). Accept any "PKGMdddd".
                    ver.startsWith("PKGM") && ver.length == 8 &&
                        ver.substring(4).all { it.isDigit() }
                } ?: false

                // Step 7: Send encrypted EncAuthResponse
                val authResponse = EncAuthResponse()
                // VERIFIED from decompiled TcpClient line 677:
                //   if (encAuthResponse.getStatus().equals("AuthOK"))
                // The success status is "AuthOK", NOT "ok"!
                authResponse.status = when {
                    !versionOk || !mpkgVersionOk -> "AuthFailedVersion"
                    !pinValid -> "AuthFailed"
                    else -> "AuthOK"
                }

                if (!versionOk || !mpkgVersionOk) {
                    WepWawetLogger.proxyWarn("Pairing rejected — version mismatch",
                        "received_version" to (authReq.version ?: 0),
                        "expected_version" to CONNECTION_PROTOCOL_VERSION,
                        "received_mpkg_version" to (authReq.mpkgVersion ?: ""))
                }

                val respJson = gson.toJson(authResponse)
                WepWawetLogger.proxyDebug("Sending EncAuthResponse",
                    "json" to respJson,
                    "status" to authResponse.status)

                // Re-init cipher for encryption (AES/CBC is stateful)
                val encCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                encCipher.init(Cipher.ENCRYPT_MODE, currentAesKey, currentIv)
                val respBytes = encCipher.doFinal(respJson.toByteArray(Charsets.UTF_8))
                // LITTLE-ENDIAN length prefix!
                writeLeInt(output, respBytes.size)
                output.write(respBytes)
                output.flush()

                if (!pinValid) {
                    WepWawetLogger.proxyWarn("Pairing failed — invalid PIN")
                    onPairingFailure?.invoke("Invalid PIN")
                    return
                }

                // Step 8: Pairing successful!
                pairingSuccess = true
                // v0.7.4 (parity G1): record the phone's reported .mpkg version
                // so the packer can negotiate the per-transfer magic — the PC
                // never hands a phone a container NEWER than its own reported
                // version, and forces version 14 for video wallpapers.
                negotiatedMpkgVersion = authReq.mpkgVersion
                    ?.takeIf { it.startsWith("PKGM") && it.length == 8 }
                    ?.substring(4)
                    ?.toIntOrNull()
                    ?: 0
                WepWawetLogger.proxy("Negotiated phone .mpkg version (G1)",
                    "reported" to (authReq.mpkgVersion ?: ""),
                    "negotiated_int" to negotiatedMpkgVersion)
                // v0.7.1 (round 6 — pairing persistence): close any older
                // currentClient that isn't this one BEFORE assigning the
                // new client. If a previous phone session is still hanging
                // around (e.g. the phone's TCP keepalive hasn't detected the
                // disconnect yet), this forcibly closes the old socket so
                // the old connection's command loop returns and its thread
                // exits — preventing a stale-forever "paired" state in the
                // UI when the user re-pairs with a different phone or
                // reconnects after a long network dropout.
                val older = currentClient
                if (older != null && older !== client) {
                    WepWawetLogger.proxyWarn("Pairing: closing older currentClient before assigning new one",
                        "older_remote" to (older.remoteSocketAddress?.toString() ?: "unknown"),
                        "new_remote" to (client.remoteSocketAddress.toString()))
                    runCatching { older.close() }
                }
                currentClient = client
                // v0.7.24 (H-P5): was `soTimeout = 0` (block forever). With no
                // timeout, a peer that vanishes without FIN/RST (Wi-Fi drop)
                // left this thread blocked in readFully FOREVER. The phone
                // heartbeats every ~3-5s, so a 65s read timeout is >10 missed
                // heartbeats — a dead-peer detector, not a liveness risk.
                // SocketTimeoutException propagates to the loop's catch and
                // the connection is cleaned up like any other disconnect.
                it.soTimeout = 65_000

                val deviceName = authReq.deviceUserName ?: authReq.name ?: "Unknown"
                WepWawetLogger.proxy("Pairing successful!",
                    "device_name" to deviceName,
                    "app_version" to (authReq.appVersion ?: ""),
                    "mpkg_version" to (authReq.mpkgVersion ?: ""),
                    "display" to "${authReq.displayWidth}x${authReq.displayHeight}")
                onPairingSuccess?.invoke(deviceName)

                // Step 9: Enter command loop (handle subsequent commands).
                // When commandLoop returns (phone disconnected, socket
                // closed, or loop threw), invoke onDisconnected ONLY if
                // currentClient === client (i.e. this disconnecting client
                // is still the most recent one). If a NEW phone has already
                // paired (currentClient points to a different socket), we
                // do NOT clear state for the new phone.
                commandLoop(input, output)
                if (currentClient === client) {
                    currentClient = null
                    currentAesKey = null
                    currentIv = null
                    WepWawetLogger.proxy("Pairing: current client disconnected — invoking onDisconnected")
                    onDisconnected?.invoke()
                } else {
                    WepWawetLogger.proxy("Pairing: client disconnected but newer client already paired — leaving state intact")
                }

            }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Pairing connection error",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
                exception = e)
            if (!pairingSuccess) {
                onPairingFailure?.invoke(e.message ?: "Connection error")
            }
            // v0.7.1 (round 6): even on exception, if the disconnecting
            // client is still the current one, clear state + invoke the
            // onDisconnected callback so the UI notification is updated
            // (was stuck showing "Paired: OnePlus 15" forever after a
            // connection error from the previous pairing).
            if (currentClient === client) {
                currentClient = null
                currentAesKey = null
                currentIv = null
                WepWawetLogger.proxy("Pairing: current client errored out — invoking onDisconnected",
                    "exception" to e.javaClass.name)
                onDisconnected?.invoke()
            }
        }
    }

    // Pending wallpaper transfers (queue of (file, title) pairs)
    private val pendingWallpapers = java.util.concurrent.LinkedBlockingQueue<Pair<java.io.File, String>>()

    // v0.5.32 (audit C4): Lock for all writes to the per-connection
    // `output` DataOutputStream. The transfer protocol is a multi-step
    // transaction (beginUpload → transmissionStart → chunks → terminator)
    // and must NOT be interleaved with command-loop responses (AuthOK etc.).
    // Without this lock, spawning the transfer on a separate thread would
    // let the command loop's AuthOK write slip in between two transfer
    // messages and break the protocol.
    private val outputLock = Any()

    // v0.5.32 (audit C4): True while a wallpaper transfer is in flight
    // on the transfer thread. The WE Android protocol supports only ONE
    // transfer at a time (single tempWriteFile per session), so a second
    // pending wallpaper must wait until the first finishes.
    private val transferInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Queue a wallpaper .mpkg file to be sent to the phone.
     * Called from the UI when the user selects a wallpaper to "send to phone".
     * The file will be sent during the command loop.
     */
    fun sendWallpaper(mpkgFile: java.io.File, title: String) {
        WepWawetLogger.proxy("Wallpaper queued for transfer",
            "file" to mpkgFile.name,
            "title" to title,
            "size" to mpkgFile.length())
        pendingWallpapers.add(mpkgFile to title)
    }

    /**
     * Send an AES-encrypted JSON object to the phone.
     * Mirrors TcpClient$Companion.sendEncryptedGSONObject:
     *   writeLeInt(ciphertext_length) + AES_encrypted(JSON)
     * Each call creates a FRESH cipher (the phone does the same on its read side).
     */
    /** v0.7.24 (C-P2): immutable session-crypto snapshot for a transfer. */
    private class CryptoSnapshot(val key: SecretKey, val iv: IvParameterSpec)

    private fun sendEncryptedJson(output: DataOutputStream, obj: Any) {
        val json = gson.toJson(obj)
        sendEncryptedJsonString(output, json)
    }

    /** v0.7.24 (C-P2): transfer-path variant using an immutable crypto snapshot. */
    private fun sendEncryptedJson(output: DataOutputStream, obj: Any, crypto: CryptoSnapshot) {
        val json = gson.toJson(obj)
        sendEncryptedJsonString(output, json, crypto)
    }

    /**
     * v0.7.22: send an AES-encrypted RAW JSON string (not Gson-serialized).
     *
     * Used by the heartbeat echo, which must be byte-exact with the real PC:
     * wallpaperui.exe's handler (disassembled at 0x140039c98–0x140039d60)
     * builds a fresh JSON value, sets ONLY the "command" member to
     * "heartbeat" and sends it — the wire bytes are exactly
     * `{"command":"heartbeat"}`, no other fields. Serializing our
     * DownloadInfo with Gson would add title/file/size defaults the PC
     * never sends (harmless to the phone's parser, but not parity).
     */
    private fun sendEncryptedJsonString(output: DataOutputStream, json: String) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, currentAesKey, currentIv)
        val ciphertext = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        // v0.5.32 (audit C4): synchronize write+flush to keep atomicity
        // for the pairing phase (pre-transfer). During a transfer the
        // outer transferWallpaper holds outputLock — re-entrant sync is
        // safe and just no-ops here.
        synchronized(outputLock) {
            writeLeInt(output, ciphertext.size)
            output.write(ciphertext)
            output.flush()
        }
    }

    /** v0.7.24 (C-P2): transfer-path variant using an immutable crypto snapshot. */
    private fun sendEncryptedJsonString(output: DataOutputStream, json: String, crypto: CryptoSnapshot) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, crypto.key, crypto.iv)
        val ciphertext = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        // NOTE: the outer transferWallpaperLocked already holds outputLock
        // (re-entrant), keeping write+flush atomic within the transfer.
        synchronized(outputLock) {
            writeLeInt(output, ciphertext.size)
            output.write(ciphertext)
            output.flush()
        }
    }

    /**
     * Send AES-encrypted raw data (file chunk) to the phone.
     * Mirrors TcpClient$Companion.sendEncryptedGSONObject but for raw bytes:
     *   writeLeInt(ciphertext_length) + AES_encrypted(data)
     *
     * CRITICAL: Do NOT manually pad the data! AES/CBC/PKCS5Padding already
     * handles PKCS5/PKCS7 padding internally. The previous version had a
     * double-padding bug that corrupted every chunk — the phone would
     * decrypt the data and get extra padding bytes appended to each chunk,
     * making the .mpkg file invalid.
     */
    private fun sendEncryptedData(output: DataOutputStream, data: ByteArray) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, currentAesKey, currentIv)
        val ciphertext = cipher.doFinal(data)
        synchronized(outputLock) {
            writeLeInt(output, ciphertext.size)
            output.write(ciphertext)
            output.flush()
        }
    }

    /** v0.7.24 (C-P2): transfer-path variant using an immutable crypto snapshot. */
    private fun sendEncryptedData(output: DataOutputStream, data: ByteArray, crypto: CryptoSnapshot) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, crypto.key, crypto.iv)
        val ciphertext = cipher.doFinal(data)
        synchronized(outputLock) {
            writeLeInt(output, ciphertext.size)
            output.write(ciphertext)
            output.flush()
        }
    }

    /**
     * Send a .mpkg wallpaper file to the phone using the DownloadInfo transfer protocol.
     *
     * Protocol (VERIFIED from decompiled TcpClient.java command loop lines 727-926):
     * 1. Send {"command":"beginUpload"} — encrypted JSON
     * 2. Send {"command":"transmissionStart","title":"...","file":"wallpapers/X.mpkg","size":totalSize}
     * 3. For each ~1MB chunk:
     *    a. Send {"command":"transmissionContinue","size":1} — encrypted JSON (size>0 = data follows)
     *    b. Send 4-byte LE length + AES-encrypted chunk — the actual file bytes
     * 4. Send {"command":"transmissionContinue","size":0} — encrypted JSON (done)
     * 5. Phone moves temp file to filesDir/downloads/wallpapers/X.mpkg
     */
    private fun transferWallpaper(
        output: DataOutputStream,
        mpkgFile: java.io.File,
        title: String,
    ) {
        // v0.5.32 (audit C4): Hold outputLock for the ENTIRE transfer
        // transaction. The transfer is a multi-step protocol:
        //   beginUpload → transmissionStart → chunks → terminator
        // If the command loop's AuthOK write interleaves between two
        // transfer messages, the phone's command parser gets confused
        // (it expects transmissionStart after beginUpload, not AuthOK).
        // The lock is re-entrant so the inner sendEncryptedJson /
        // sendEncryptedData calls don't deadlock.
        //
        // v0.7.4: bytesSent is tracked HERE (outer function) so the catch
        // block can report the REAL bytes-sent-so-far — the old catch logged
        // `mpkgFile.length() - 0` (the total file size, not progress).
        var bytesSent = 0L
        // v0.7.24 (C-P2 BUG FIX): snapshot the session crypto BEFORE the
        // transfer begins. The transfer runs on its own thread and can take
        // 30+s; meanwhile a NEW phone pairing on another connection-thread
        // overwrites currentAesKey/currentIv mid-flight — the remaining
        // chunks were then encrypted with the WRONG session's key (silent
        // transfer corruption) or NPE'd when stop() nulled the fields. The
        // snapshot makes the transfer's crypto immutable for its lifetime.
        val cryptoKey = currentAesKey
        val cryptoIv = currentIv
        if (cryptoKey == null || cryptoIv == null) {
            WepWawetLogger.proxyError("transferWallpaper aborted — no active session crypto",
                "file" to mpkgFile.name)
            return
        }
        val crypto = CryptoSnapshot(cryptoKey, cryptoIv)
        try {
            synchronized(outputLock) {
                bytesSent = transferWallpaperLocked(output, mpkgFile, title, crypto)
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("transferWallpaper FAILED",
                "file" to mpkgFile.name,
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
                "bytes_sent_so_far" to bytesSent,
                exception = e)
            // Send cancel so the phone stops appending to tempWriteFile.
            // v0.7.24 (C-P2): uses the SAME captured session crypto — the
            // field may have been replaced by a newer pairing while this
            // transfer was in flight.
            try {
                sendEncryptedJson(output, DownloadInfo(command = "transmissionCancel"), crypto)
            } catch (_: Exception) {}
        }
    }

    /** v0.7.4: returns the number of file bytes actually sent (for accurate
     *  failure reporting in [transferWallpaper]). */
    private fun transferWallpaperLocked(
        output: DataOutputStream,
        mpkgFile: java.io.File,
        title: String,
        crypto: CryptoSnapshot,
    ): Long {
        val totalSize = mpkgFile.length()
        // Use just the filename without "wallpapers/" prefix.
        // The phone prepends DOWNLOAD_FOLDER ("downloads/") to this path,
        // so the final path is "downloads/<filename>". Using a shorter path
        // reduces the directory depth that mkdirs() needs to create on the
        // phone side, which helps avoid the file-move issue where mkdirs()
        // creates the .mpkg filename as a directory.
        val fileName = mpkgFile.name

        WepWawetLogger.proxy("transferWallpaper START",
            "file" to fileName,
            "title" to title.take(80),
            "size" to totalSize,
            "size_mb" to "%.3f".format(totalSize / 1024.0 / 1024.0),
            "file_exists" to mpkgFile.exists(),
            "file_readable" to mpkgFile.canRead(),
            "file_absolute_path" to mpkgFile.absolutePath)

        // === Sanity check: verify the .mpkg file looks like a .mpkg ===
        // (header_marker=8, magic starts with "PKGM") before sending.
        // If this fails, the file is corrupt — abort the transfer so the
        // user sees the error in the logs instead of WE silently rejecting it.
        if (!verifyMpkgFile(mpkgFile)) {
            WepWawetLogger.proxyError("transferWallpaper ABORT: .mpkg verification failed",
                "file" to mpkgFile.name,
                "size" to totalSize)
            return 0L
        }

        // v0.7.4: hoisted above the try so the catch block can report the
        // REAL bytes-sent-so-far (the old catch logged the total file size).
        var bytesSent = 0L
        try {
            // Step 1: beginUpload — phone removes any existing entry with
            // this file path, creates a fresh WallpaperInfoSparse, adds to UI.
            WepWawetLogger.proxy("transferWallpaper: sending beginUpload")
            sendEncryptedJson(output, DownloadInfo(command = "beginUpload"), crypto)
            WepWawetLogger.proxy("transferWallpaper: sent beginUpload")

            // Step 2: transmissionStart — phone creates tempWriteFile and
            // records the tempTransmissionInfo (file path, total size).
            WepWawetLogger.proxy("transferWallpaper: sending transmissionStart",
                "file" to fileName, "size" to totalSize)
            sendEncryptedJson(output, DownloadInfo(
                command = "transmissionStart",
                title = title,
                file = fileName,
                size = totalSize,
            ), crypto)
            WepWawetLogger.proxy("transferWallpaper: sent transmissionStart",
                "file" to fileName, "size" to totalSize)

            // Step 3: send chunks
            // Per the decompiled TcpClient.java command loop (lines 750-798):
            //   - For each chunk: send {"command":"transmissionContinue","size":1}
            //     (size>0 signals "data follows"), then 4-byte LE length + AES-
            //     encrypted chunk bytes.
            //   - When phone receives a chunk with size>0, it appends the data
            //     to tempWriteFile and decrements tempTransmissionInfo.size.
            //   - When tempTransmissionInfo.size reaches 0, phone moves the
            //     tempWriteFile to <filesDir>/downloads/wallpapers/X.mpkg.
            //
            // v0.7.4 (parity G8): chunk size reduced from 1 MiB to 128 KiB to
            // match the PC's framing EXACTLY. With 1 MiB chunks the AES-CBC
            // ciphertext frame is 1,048,592 bytes — twenty bytes OVER the
            // phone-side constant MAX_SAFE_DATA_LENGTH = 1,048,572 that this
            // very file documents. Transfers demonstrably worked (the phone
            // evidently does not enforce that ceiling on data frames), but
            // the margin was twenty bytes on every chunk. 128 KiB (=131,072
            // + 16 bytes padding overhead) sits far inside the limit and is
            // byte-for-byte the PC's chunk cadence (FUN_14003b4c0).
            val chunkSize = 128 * 1024  // 128 KiB — matches the PC transfer loop
            var chunkNum = 0
            val startTime = System.currentTimeMillis()
            java.io.FileInputStream(mpkgFile).use { fis ->
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val read = fis.read(buffer)
                    if (read <= 0) break
                    val chunk = if (read < chunkSize) buffer.copyOf(read) else buffer

                    WepWawetLogger.proxyDebug("transferWallpaper: sending chunk header",
                        "chunk" to (chunkNum + 1),
                        "chunk_size" to read,
                        "bytes_so_far" to bytesSent)

                    // Send transmissionContinue with size=1 (signals "data follows")
                    sendEncryptedJson(output, DownloadInfo(
                        command = "transmissionContinue",
                        size = 1L,  // >0 means "read encrypted data next"
                    ), crypto)

                    // Send the AES-encrypted chunk data
                    sendEncryptedData(output, chunk, crypto)

                    bytesSent += read
                    chunkNum++
                    if (chunkNum % 80 == 0 || bytesSent == totalSize) {
                        WepWawetLogger.proxy("transferWallpaper: chunk sent",
                            "chunk" to chunkNum,
                            "chunk_bytes" to read,
                            "bytes_sent" to bytesSent,
                            "total" to totalSize,
                            "pct" to (bytesSent * 100 / totalSize).toInt())
                    }
                }
            }
            val transferMs = System.currentTimeMillis() - startTime
            WepWawetLogger.proxy("transferWallpaper: all chunks sent",
                "file" to fileName,
                "chunks" to chunkNum,
                "bytes_sent" to bytesSent,
                "total" to totalSize,
                "ms" to transferMs,
                "kbps" to if (transferMs > 0) (bytesSent * 8 / transferMs) else 0)

            // Step 4: transmissionContinue with size=0 (signals "done")
            // Per decompiled code: when phone receives this, it converts it to
            // "transmissionCancel" for UI callback purposes, then checks if the
            // file exists at <filesDir>/downloads/wallpapers/X.mpkg.
            //   - If file exists → entry stays in wallpaper list (SUCCESS)
            //   - If file doesn't exist → entry is REMOVED (FAILURE)
            WepWawetLogger.proxy("transferWallpaper: sending transmissionContinue (terminator, size=0)")
            sendEncryptedJson(output, DownloadInfo(
                command = "transmissionContinue",
                size = 0L,  // 0 means "transfer complete"
            ), crypto)
            WepWawetLogger.proxy("transferWallpaper COMPLETE",
                "file" to fileName,
                "bytes_sent" to bytesSent,
                "chunks" to chunkNum,
                "ms" to transferMs)
            return bytesSent

        } catch (e: Exception) {
            WepWawetLogger.proxyError("transferWallpaperLocked FAILED",
                "file" to fileName,
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
                "bytes_sent_so_far" to bytesSent)
            throw e
        }
    }

    /**
     * Verify a .mpkg file looks correct before sending it. Catches issues like
     * corrupt headers, wrong magic, or truncated files early — so we log the
     * issue locally instead of WE silently rejecting the file on the phone side.
     *
     * Checks:
     *   - File exists and is >= 16 bytes (minimum: header_marker(4) + magic(8) + entry_count(4))
     *   - First 4 bytes (header_marker) = 8 (LE)
     *   - Bytes 4-11 are ASCII "PKGM" + 4 digits
     */
    private fun verifyMpkgFile(file: java.io.File): Boolean {
        try {
            if (!file.exists() || file.length() < 16) {
                WepWawetLogger.proxyWarn("verifyMpkgFile: file missing or too small",
                    "exists" to file.exists(),
                    "size" to file.length())
                return false
            }
            val header = ByteArray(16)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.readFully(header)
            }
            // header_marker = u32 LE
            val headerMarker = (header[0].toInt() and 0xFF) or
                ((header[1].toInt() and 0xFF) shl 8) or
                ((header[2].toInt() and 0xFF) shl 16) or
                ((header[3].toInt() and 0xFF) shl 24)
            // magic = 8 ASCII chars starting at offset 4
            val magic = String(header, 4, 8, Charsets.US_ASCII)
            val startsWithPkgm = magic.startsWith("PKGM")
            val hasValidVersion = magic.length == 8 &&
                magic.substring(4).all { it.isDigit() }
            // entry_count = u32 LE
            val entryCount = (header[12].toInt() and 0xFF) or
                ((header[13].toInt() and 0xFF) shl 8) or
                ((header[14].toInt() and 0xFF) shl 16) or
                ((header[15].toInt() and 0xFF) shl 24)
            val ok = headerMarker == 8 && startsWithPkgm && hasValidVersion && entryCount in 1..4096
            WepWawetLogger.proxy("verifyMpkgFile result",
                "file" to file.name,
                "header_marker" to headerMarker,
                "magic" to magic,
                "entry_count" to entryCount,
                "starts_with_PKGM" to startsWithPkgm,
                "has_valid_version" to hasValidVersion,
                "verified" to ok)
            return ok
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("verifyMpkgFile: exception",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            return false
        }
    }

    /**
     * Command loop — handles subsequent commands after pairing.
     * All messages are AES-encrypted with the established key.
     *
     * Wire format per message (LITTLE-ENDIAN length prefix — matches phone):
     *   writeLeInt(ciphertext_length)  (4 bytes little-endian)
     *   ciphertext                     (AES/CBC/PKCS5Padding encrypted JSON)
     *
     * Heartbeat: The phone sends "heartbeat\n" (10 ASCII bytes, no length prefix)
     * every ~3 seconds. We must detect and skip these frames.
     *
     * The phone may send commands like:
     *   - Request wallpaper download
     *   - Request playlist
     *   - Pause/stop wallpaper
     *   - etc.
     *
     * For now, we log every received command for the RE effort.
     */
    private fun commandLoop(input: DataInputStream, output: DataOutputStream) {
        WepWawetLogger.proxy("Entering command loop — waiting for commands from WE Android app")

        val decCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        decCipher.init(Cipher.DECRYPT_MODE, currentAesKey, currentIv)

        var heartbeatCount = 0
        var messageCount = 0
        // v0.7.24 (H-P4): `Socket.isConnected()` returns true for the
        // LIFETIME of the socket once connect() returned — it does NOT
        // reflect a silently-dead peer. isClosed() only turns true after WE
        // close the socket locally, so the real exit signal is the read
        // throwing (H-P5's read timeout guarantees that happens ≤65s after
        // the peer goes silent). Combined condition: still running, client
        // present, and not locally closed.
        while (running && currentClient?.isClosed == false) {
            try {
                val (kind, payload) = readMessageOrHeartbeat(input)
                when (kind) {
                    "heartbeat" -> {
                        heartbeatCount++
                        if (heartbeatCount % 10 == 1) {
                            WepWawetLogger.proxy("Heartbeat received",
                                "count" to heartbeatCount)
                        }
                        // v0.7.22 (PC parity, wallpaperui.exe 0x140039c98–
                        // 0x140039d60): the real PC answers EVERY heartbeat
                        // with an encrypted {"command":"heartbeat"} echo.
                        // This is not cosmetic: the official app's
                        // TransferService routes every received DownloadInfo
                        // (including the echo) through updateNotification →
                        // beginIdleDisconnect, which re-arms its 5-second
                        // idle disconnect — with a real PC the connection
                        // therefore STAYS ALIVE while backgrounded, and the
                        // notification keeps refreshing. Without the echo
                        // (every WepWawet release before v0.7.22) the timer
                        // fires and the app closes its own socket — the
                        // recurring "Connection closed: java.net.
                        // SocketException: Socket closed" entries in the
                        // user's applog.
                        // v0.7.24 (H-P6 BUG FIX): transferWallpaper holds
                        // outputLock for the ENTIRE multi-chunk transfer, and
                        // sendEncryptedJsonString synchronizes on the same
                        // lock — so during a transfer the echo BLOCKED the
                        // command loop for up to 30+s (100MB wallpaper),
                        // delaying every other command. That defeated the
                        // v0.5.32 "keep reading heartbeats" fix. The transfer
                        // chunks THEMSELVES reset the phone's 5s idle timer
                        // (TransferService treats each received frame as
                        // activity), so skipping the echo during a transfer
                        // is safe AND keeps the loop responsive.
                        if (transferInProgress.get()) {
                            if (heartbeatCount % 10 == 1) {
                                WepWawetLogger.proxy("Heartbeat echo skipped — transfer in progress (chunks keep the phone's idle timer re-armed)")
                            }
                        } else {
                            try {
                                sendEncryptedJsonString(output, """{"command":"heartbeat"}""")
                            } catch (e: Exception) {
                                WepWawetLogger.proxyWarn("Heartbeat echo failed",
                                    "exception" to e.javaClass.name)
                                throw e
                            }
                        }
                        // v0.5.32 (audit C4): Spawn the transfer on a
                        // separate thread so this loop can keep READING
                        // heartbeats. Previously `transferWallpaper` was
                        // called synchronously, which writes ~1MB chunks
                        // via sendEncryptedData for ~30s on Wi-Fi (100MB
                        // wallpaper) — long enough for the phone's TCP
                        // send buffer to fill up with unconsumed heartbeats
                        // and the phone's connector thread to time out
                        // and disconnect the pairing session mid-transfer.
                        //
                        // The AtomicBoolean prevents two parallel
                        // transfers (the protocol supports only one
                        // tempWriteFile per session); a second pending
                        // is re-queued for the next heartbeat.
                        val pending = pendingWallpapers.poll()
                        if (pending != null) {
                            if (transferInProgress.compareAndSet(false, true)) {
                                WepWawetLogger.proxy("Processing pending wallpaper transfer (from heartbeat, spawning on transfer thread)")
                                thread(start = true, isDaemon = true, name = "wep-transfer") {
                                    try {
                                        transferWallpaper(output, pending.first, pending.second)
                                    } finally {
                                        transferInProgress.set(false)
                                    }
                                }
                            } else {
                                WepWawetLogger.proxy("Transfer already in progress — re-queueing pending wallpaper (from heartbeat)")
                                pendingWallpapers.add(pending)
                            }
                        }
                        continue
                    }
                    "invalid" -> {
                        WepWawetLogger.proxyWarn("Invalid message length in command loop")
                        break
                    }
                    else -> {
                        val msgBytes = payload ?: continue
                        messageCount++
                        val decrypted = decCipher.doFinal(msgBytes)
                        val commandJson = String(decrypted, Charsets.UTF_8)

                        WepWawetLogger.proxy("Received command",
                            "length" to msgBytes.size,
                            "msg_index" to messageCount,
                            "decrypted_json" to commandJson.take(500))

                        // v0.7.22 (PC parity): mirror wallpaperui.exe's
                        // post-auth dispatcher (0x140039c39–0x14003a33c)
                        // exactly:
                        //   "quit"                    → clean close (no reply)
                        //   "heartbeat" as a JSON msg  → {"command":"heartbeat"}
                        //   beginUpload/transmissionStart/transmissionContinue/
                        //   transmissionCancel         → echo with command+size
                        //   anything else              → SILENTLY IGNORED —
                        //   the unknown-command path at 0x14003a33c just
                        //   cleans up its locals and loops back to the read.
                        // The previous {"status":"AuthOK"} ack for unknown
                        // messages was NOT PC behavior: the phone would
                        // Gson-parse it into a DownloadInfo with command=""
                        // and route it into its download callback — harmless
                        // but wrong, and it is gone now.
                        val command = runCatching {
                            gson.fromJson(commandJson, DownloadInfo::class.java).command
                        }.getOrNull()
                        when (command) {
                            "quit" -> {
                                WepWawetLogger.proxy("Phone requested quit — closing connection cleanly",
                                    "heartbeats_seen" to heartbeatCount,
                                    "messages_seen" to messageCount)
                                break
                            }
                            "heartbeat" -> {
                                sendEncryptedJsonString(output, """{"command":"heartbeat"}""")
                            }
                            "beginUpload", "transmissionStart", "transmissionCancel" -> {
                                sendEncryptedJsonString(output, """{"command":"$command"}""")
                            }
                            "transmissionContinue" -> {
                                val size = runCatching {
                                    gson.fromJson(commandJson, DownloadInfo::class.java).size
                                }.getOrNull() ?: 0L
                                sendEncryptedJsonString(output, """{"command":"transmissionContinue","size":$size}""")
                            }
                            else -> {
                                // unknown command — the real PC stays silent
                            }
                        }

                        // v0.5.32 (audit C4): Spawn on transfer thread
                        // (same fix as heartbeat branch above).
                        val pending = pendingWallpapers.poll()
                        if (pending != null) {
                            if (transferInProgress.compareAndSet(false, true)) {
                                WepWawetLogger.proxy("Processing pending wallpaper transfer (from command, spawning on transfer thread)")
                                thread(start = true, isDaemon = true, name = "wep-transfer") {
                                    try {
                                        transferWallpaper(output, pending.first, pending.second)
                                    } finally {
                                        transferInProgress.set(false)
                                    }
                                }
                            } else {
                                WepWawetLogger.proxy("Transfer already in progress — re-queueing pending wallpaper (from command)")
                                pendingWallpapers.add(pending)
                            }
                        }
                    }
                }

            } catch (e: java.net.SocketTimeoutException) {
                // Normal — keep alive
            } catch (e: Exception) {
                if (running) {
                    WepWawetLogger.proxyWarn("Command loop ended",
                        "exception" to e.javaClass.name,
                        "heartbeats_seen" to heartbeatCount,
                        "messages_seen" to messageCount)
                }
                break
            }
        }
        WepWawetLogger.proxy("Command loop ended",
            "heartbeats_seen" to heartbeatCount,
            "messages_seen" to messageCount)
    }
}

/**
 * EncAuthRequest — the phone sends this encrypted with AES to authenticate.
 *
 * Fields from decompiled TcpClient$EncAuthRequest:
 *   pin: String              — 4-digit PIN shown on the "PC"
 *   name: String             — device name
 *   deviceUserName: String   — custom device name entered by user
 *   displayWidth: Int        — phone screen width
 *   displayHeight: Int       — phone screen height
 *   version: Int              — protocol version (default 4)
 *   mpkgVersion: String       — supported .mpkg version
 *   appVersion: String        — WE Android app version
 */
data class EncAuthRequest(
    val pin: String? = null,
    val name: String? = null,
    val deviceUserName: String? = null,
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val version: Int? = null,
    val mpkgVersion: String? = null,
    val appVersion: String? = null,
)

/**
 * EncAuthResponse — the "PC" sends this encrypted with AES.
 *
 * Fields from decompiled TcpClient$EncAuthResponse:
 *   status: String — "ok" for success, anything else for failure
 */
data class EncAuthResponse(
    var status: String = "",
)

/**
 * DownloadInfo — the PC sends this to the phone to transfer wallpaper files.
 *
 * Fields from decompiled io.wallpaperengine.wedata.DownloadInfo:
 *   command: String — "beginUpload", "transmissionStart", "transmissionContinue", "transmissionCancel"
 *   title: String — wallpaper title
 *   file: String — file path relative to downloads folder (e.g., "wallpapers/123.mpkg")
 *   size: Long — total file size (for transmissionStart) or chunk flag (>0 = read data, 0 = done)
 *   data: ByteArray — decrypted chunk data (phone-side only)
 *   bytesRemaining: Long — bytes remaining (phone-side only)
 *
 * Transfer protocol (VERIFIED from decompiled TcpClient.java command loop):
 * 1. PC sends {"command":"beginUpload"} — encrypted JSON
 * 2. PC sends {"command":"transmissionStart","title":"...","file":"wallpapers/X.mpkg","size":totalSize}
 * 3. For each chunk (~1MB):
 *    a. PC sends {"command":"transmissionContinue","size":1} — encrypted JSON (size>0 = read data)
 *    b. PC sends 4-byte LE length + AES-encrypted chunk_data — the actual file bytes
 * 4. PC sends {"command":"transmissionContinue","size":0} — encrypted JSON (size=0 = done)
 * 5. Phone moves temp file to filesDir/downloads/wallpapers/X.mpkg
 */
data class DownloadInfo(
    var command: String = "",
    var title: String = "",
    var file: String = "",
    var size: Long = 0L,
    var data: ByteArray? = null,
    var bytesRemaining: Long = 0L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadInfo) return false
        return command == other.command && title == other.title && file == other.file && size == other.size
    }
    override fun hashCode(): Int {
        var result = command.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + file.hashCode()
        result = 31 * result + size.hashCode()
        return result
    }
}
