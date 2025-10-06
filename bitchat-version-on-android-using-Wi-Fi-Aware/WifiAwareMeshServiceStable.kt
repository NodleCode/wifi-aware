package com.bitchat.android.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.crypto.MessagePadding
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.io.IOException
import java.net.Inet6Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * WifiAware mesh service - STABLE
 *
 * This is now a coordinator that orchestrates the following components:
 * - PeerManager: Peer lifecycle management
 * - FragmentManager: Message fragmentation and reassembly
 * - SecurityManager: Security, duplicate detection, encryption
 * - StoreForwardManager: Offline message caching
 * - MessageHandler: Message type processing and relay logic
 * - PacketProcessor: Incoming packet routing
 */
class WifiAwareMeshService(context: Context) {

    companion object {
        private const val TAG = "WifiAwareMeshService"
        private const val MAX_TTL: UByte = 7u
        private const val SERVICE_NAME = "bitchat"
        private const val PSK = "bitchat_secret"
    }

    // My peer identification - same format as iOS
    val myPeerID: String = generateCompatiblePeerID()
    private val handleToPeerId = ConcurrentHashMap<PeerHandle, String>()

    // Core components - each handling specific responsibilities
    private val encryptionService = EncryptionService(context)
    private val peerManager = PeerManager()
    private val fragmentManager = FragmentManager()
    private val securityManager = SecurityManager(encryptionService, myPeerID)
    private val storeForwardManager = StoreForwardManager()
    private val messageHandler = MessageHandler(myPeerID)
    private val packetProcessor = PacketProcessor(myPeerID)

    // Wi‑Fi Aware transport
    private val awareManager = context.getSystemService(WifiAwareManager::class.java)
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val listenerExec = Executors.newCachedThreadPool()
    private var isActive = false

    // Delegates
    var delegate: WifiAwareMeshDelegate? = null

    // Sockets
    private val peerSockets = ConcurrentHashMap<String, Socket>()
    private val serverSockets = ConcurrentHashMap<String, ServerSocket>()
    private val networkCallbacks = ConcurrentHashMap<String, ConnectivityManager.NetworkCallback>()
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Messages
    private var lastMsgId = 0
    private fun nextMessageId() = synchronized(this) { ++lastMsgId }
    private val lastTimestamps = ConcurrentHashMap<String, ULong>()

    // Scope
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        setupDelegates()
        startDebugLoop()
    }

    /**
     * Periodically logs mesh debug information every 10 seconds.
     */
    private fun startDebugLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(10_000)
                if (isActive) Log.d(TAG, getDebugStatus())
            }
        }
    }

    /**
     * Configures delegates for internal components so that events are routed back
     * through this service and ultimately to the {@link WifiAwareMeshDelegate}.
     */
    private fun setupDelegates() {
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerConnected(nickname: String) {
                delegate?.didConnectToPeer(nickname)
            }

            override fun onPeerDisconnected(nickname: String) {
                delegate?.didDisconnectFromPeer(nickname)
            }

            override fun onPeerListUpdated(peerIDs: List<String>) {
                Log.d(
                    TAG,
                    "PeerManager→onPeerListUpdated: ${peerIDs.joinToString()} (size=${peerIDs.size})"
                )

                delegate?.didUpdatePeerList(peerIDs)
            }
        }

        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(
                peerID: String,
                peerPublicKeyData: ByteArray,
                receivedAddress: String?
            ) {
                delegate?.registerPeerPublicKey(peerID, peerPublicKeyData)
                serviceScope.launch {
                    delay(100)
                    sendAnnouncementToPeer(peerID)
                    delay(500)
                    storeForwardManager.sendCachedMessages(peerID)
                }
            }
        }

        storeForwardManager.delegate = object : StoreForwardManagerDelegate {
            override fun isFavorite(peerID: String) = delegate?.isFavorite(peerID) ?: false
            override fun isPeerOnline(peerID: String) = peerManager.isPeerActive(peerID)
            override fun sendPacket(packet: BitchatPacket) {
                packet.toBinaryData()?.let { broadcastPeer(it) }
            }
        }

        messageHandler.delegate = object : MessageHandlerDelegate {
            override fun addOrUpdatePeer(peerID: String, nickname: String) =
                peerManager.addOrUpdatePeer(peerID, nickname)

            override fun removePeer(peerID: String) =
                peerManager.removePeer(peerID)

            override fun updatePeerNickname(peerID: String, nickname: String) {
                peerManager.addOrUpdatePeer(peerID, nickname)
            }

            override fun getPeerNickname(peerID: String) =
                peerManager.getPeerNickname(peerID)

            override fun getNetworkSize() =
                peerManager.getActivePeerCount()

            override fun getMyNickname() =
                delegate?.getNickname()

            override fun sendPacket(packet: BitchatPacket) {
                packet.toBinaryData()?.let { broadcastPeer(it) }
            }

            override fun relayPacket(routed: RoutedPacket) {
                routed.packet.toBinaryData()?.let { broadcastPeer(it) }
            }

            override fun getBroadcastRecipient() =
                SpecialRecipients.BROADCAST

            override fun verifySignature(packet: BitchatPacket, peerID: String) =
                securityManager.verifySignature(packet, peerID)

            override fun encryptForPeer(data: ByteArray, recipientPeerID: String) =
                securityManager.encryptForPeer(data, recipientPeerID)

            override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String) =
                securityManager.decryptFromPeer(encryptedData, senderPeerID)

            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String) =
                delegate?.decryptChannelMessage(encryptedContent, channel)

            override fun onMessageReceived(message: BitchatMessage) {
                delegate?.didReceiveMessage(message)
            }

            override fun onChannelLeave(channel: String, fromPeer: String) {
                delegate?.didReceiveChannelLeave(channel, fromPeer)
            }

            override fun onPeerDisconnected(nickname: String) {
                delegate?.didDisconnectFromPeer(nickname)
            }

            override fun onDeliveryAckReceived(ack: DeliveryAck) {
                delegate?.didReceiveDeliveryAck(ack)
            }

            override fun onReadReceiptReceived(receipt: ReadReceipt) {
                delegate?.didReceiveReadReceipt(receipt)
            }
        }

        packetProcessor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) =
                securityManager.validatePacket(packet, peerID)

            override fun updatePeerLastSeen(peerID: String) =
                peerManager.updatePeerLastSeen(peerID)

            override fun handleKeyExchange(routed: RoutedPacket): Boolean =
                runBlocking { securityManager.handleKeyExchange(routed) }

            override fun handleAnnounce(routed: RoutedPacket) {
                serviceScope.launch {
                    val appPeerId = String(routed.packet.senderID, Charsets.UTF_8)
                    messageHandler.handleAnnounce(
                        RoutedPacket(routed.packet, appPeerId)
                    )
                }
            }

            override fun handleMessage(routed: RoutedPacket) {
                serviceScope.launch {
                    val appPeerId = String(routed.packet.senderID, Charsets.UTF_8)
                    messageHandler.handleMessage(
                        RoutedPacket(routed.packet, appPeerId)
                    )
                }
            }

            override fun handleLeave(routed: RoutedPacket) {
                serviceScope.launch {
                    val appPeerId = String(routed.packet.senderID, Charsets.UTF_8)
                    messageHandler.handleLeave(
                        RoutedPacket(routed.packet, appPeerId)
                    )
                }
            }

            override fun handleDeliveryAck(routed: RoutedPacket) {
                serviceScope.launch {
                    val appPeerId = String(routed.packet.senderID, Charsets.UTF_8)
                    messageHandler.handleDeliveryAck(
                        RoutedPacket(routed.packet, appPeerId)
                    )
                }
            }

            override fun handleReadReceipt(routed: RoutedPacket) {
                serviceScope.launch {
                    val appPeerId = String(routed.packet.senderID, Charsets.UTF_8)
                    messageHandler.handleReadReceipt(
                        RoutedPacket(routed.packet, appPeerId)
                    )
                }
            }

            override fun handleFragment(packet: BitchatPacket): BitchatPacket? =
                fragmentManager.handleFragment(packet)

            override fun sendAnnouncementToPeer(peerID: String) =
                this@WifiAwareMeshService.sendAnnouncementToPeer(peerID)

            override fun sendCachedMessages(peerID: String) =
                storeForwardManager.sendCachedMessages(peerID)

            override fun relayPacket(routed: RoutedPacket) {
                routed.packet.toBinaryData()?.let { broadcastPeer(it) }
            }
        }
    }

    /**
     * Starts Wi-Fi Aware services (publish + subscribe).
     *
     * Requires Wi-Fi state and location permissions. This method attaches to the
     * Aware session and initializes both the publisher (server role) and subscriber
     * (client role).
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(
        allOf = [
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        ]
    )
    fun startServices() {
        if (isActive) return
        isActive = true
        Log.i(TAG, "Starting Wi‑Fi Aware mesh w/ peerID=$myPeerID")

        awareManager?.attach(object : AttachCallback() {
            @SuppressLint("MissingPermission")
            @RequiresPermission(
                allOf = [
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ]
            )
            override fun onAttached(session: WifiAwareSession) {
                wifiAwareSession = session
                Log.i(TAG, "Attached! Starting publish & subscribe, peerID=$myPeerID")

                // PUBLISH (server role)
                session.publish(
                    PublishConfig.Builder()
                        .setServiceName(SERVICE_NAME)
                        .setServiceSpecificInfo(myPeerID.toByteArray())
                        .build(),
                    object : DiscoverySessionCallback() {
                        override fun onPublishStarted(pub: PublishDiscoverySession) {
                            publishSession = pub
                            Log.d(TAG, "PUBLISH: onPublishStarted()")
                        }

                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray,
                            matchFilter: List<ByteArray>
                        ) {
                            val peerId = String(serviceSpecificInfo)
                            handleToPeerId[peerHandle] = peerId
                        }

                        @RequiresApi(Build.VERSION_CODES.Q)
                        override fun onMessageReceived(
                            peerHandle: PeerHandle,
                            message: ByteArray
                        ) {
                            if (message.isEmpty()) return

                            val subscriberId = String(message)
                            if (subscriberId == myPeerID) return

                            handleToPeerId[peerHandle] = subscriberId
                            Log.d(TAG, "PUBLISH: got ping from $subscriberId; spinning up server")
                            handleSubscriberPing(publishSession!!, peerHandle)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )

                // SUBSCRIBE (client role)
                session.subscribe(
                    SubscribeConfig.Builder()
                        .setServiceName(SERVICE_NAME)
                        .build(),
                    object : DiscoverySessionCallback() {
                        override fun onSubscribeStarted(sub: SubscribeDiscoverySession) {
                            subscribeSession = sub
                            Log.d(TAG, "SUBSCRIBE: onSubscribeStarted()")
                        }

                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray,
                            matchFilter: List<ByteArray>
                        ) {
                            val peerId = String(serviceSpecificInfo)
                            handleToPeerId[peerHandle] = peerId
                            val msgId = nextMessageId()

                            subscribeSession?.sendMessage(peerHandle, msgId, myPeerID.toByteArray())

                            Log.d(TAG, "SUBSCRIBE: sent ping to $peerId (msgId=$msgId)")
                        }

                        @RequiresApi(Build.VERSION_CODES.Q)
                        override fun onMessageReceived(
                            peerHandle: PeerHandle,
                            message: ByteArray
                        ) {
                            if (message.isEmpty()) return

                            val peerId = handleToPeerId[peerHandle] ?: return
                            if (peerId == myPeerID) return

                            Log.d(TAG, "SUBSCRIBE: onMessageReceived() → server-ready")
                            handleServerReady(peerHandle, message)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }

            override fun onAttachFailed() {
                Log.e(TAG, "Wi‑Fi Aware attach failed")
            }
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * Handles subscriber ping: spawns a server socket and responds with connection info.
     *
     * @param pubSession The current publish discovery session
     * @param peerHandle The handle for the peer that pinged us
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleSubscriberPing(
        pubSession: PublishDiscoverySession,
        peerHandle: PeerHandle
    ) {
        val peerId = handleToPeerId[peerHandle] ?: return
        if (!amIServerFor(peerId)) return

        if (serverSockets.containsKey(peerId)) {
            Log.v(TAG, "↪ already serving $peerId, skipping")
            return
        }

        val ss = ServerSocket(0)
        serverSockets[peerId] = ss
        val port = ss.localPort

        val spec = WifiAwareNetworkSpecifier.Builder(pubSession, peerHandle)
            .setPskPassphrase(PSK)
            .setPort(port)
            .build()

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                try {
                    val client = ss.accept().apply { keepAlive = true }
                    Log.d(TAG, "Server accepted connection from $peerId")
                    peerSockets[peerId] = client
                    listenerExec.execute { listenToPeer(client, peerId) }
                    handleSubscriberKeepAlive(client, peerId, pubSession, peerHandle)
                    sendKeyExchange(peerId)
                } catch (ioe: IOException) {
                    Log.e(TAG, "ServerSocket accept failed for $peerId", ioe)
                }
            }

            override fun onLost(network: Network) {
                cm.bindProcessToNetwork(null)
                networkCallbacks.remove(peerId)
            }
        }

        networkCallbacks[peerId] = cb
        cm.requestNetwork(req, cb)

        val readyId = nextMessageId()
        val portBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(port)
            .array()
        Handler(Looper.getMainLooper()).post {
            try {
                val sent = pubSession.sendMessage(peerHandle, readyId, portBytes)
                Log.d(
                    TAG,
                    "PUBLISH: sendMessage() → server-ready? $sent  (msgId=$readyId, port=$port)"
                )
            } catch (e: Exception) {
                Log.e(TAG, "PUBLISH: Exception sending server-ready to $peerHandle", e)
            }
        }
    }

    /**
     * Sends periodic TCP and discovery keep-alive messages to maintain a subscriber connection.
     *
     * @param client Connected client socket
     * @param peerId ID of the connected peer
     */
    private fun handleSubscriberKeepAlive(
        client: Socket,
        peerId: String,
        pubSession: PublishDiscoverySession,
        peerHandle: PeerHandle
    ) {
        // TCP keep‑alive
        serviceScope.launch {
            val os = client.getOutputStream()
            while (peerSockets.containsKey(peerId)) {
                try {
                    os.write(0)
                } catch (_: IOException) {
                    break
                }
                delay(2_000)
            }
        }

        // DISCOVERY keep‑alive
        serviceScope.launch {
            var msgId = 0
            while (peerSockets.containsKey(peerId)) {
                try {
                    pubSession.sendMessage(peerHandle, msgId++, ByteArray(0))
                } catch (_: Exception) {
                    break
                }
                delay(20_000)
            }
        }
    }

    /**
     * Handles a "server ready" message from a publishing peer and initiates a client connection.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleServerReady(
        peerHandle: PeerHandle,
        payload: ByteArray
    ) {
        if (payload.size < Int.SIZE_BYTES) {
            Log.w(
                TAG,
                "handleServerReady called with invalid payload size=${payload.size}, dropping"
            )
            return
        }

        val peerId = handleToPeerId[peerHandle] ?: return
        if (amIServerFor(peerId)) return

        if (peerSockets.containsKey(peerId)) {
            Log.v(TAG, "↪ already client‑connected to $peerId, skipping")
            return
        }

        val port = ByteBuffer.wrap(payload)
            .order(ByteOrder.BIG_ENDIAN)
            .int

        val spec = WifiAwareNetworkSpecifier.Builder(subscribeSession!!, peerHandle)
            .setPskPassphrase(PSK)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
            }

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                if (peerSockets.containsKey(peerId)) return
                val info = (nc.transportInfo as? WifiAwareNetworkInfo) ?: return
                val addr = info.peerIpv6Addr as Inet6Address

                try {
                    val sock = network.socketFactory
                        .createSocket(addr, port)
                        .apply { keepAlive = true }

                    Log.d(TAG, "Client connected socket to $peerId at $addr:$port")
                    peerSockets[peerId] = sock
                    listenerExec.execute { listenToPeer(sock, peerId) }
                    handleServerKeepAlive(sock, peerId, peerHandle)
                    sendKeyExchange(peerId)
                } catch (ioe: IOException) {
                    Log.e(TAG, "Client socket connect failed to $peerId", ioe)
                }
            }

            override fun onLost(network: Network) {
                cm.bindProcessToNetwork(null)
                networkCallbacks.remove(peerId)
            }
        }

        networkCallbacks[peerId] = cb
        cm.requestNetwork(req, cb)
    }

    /**
     * Sends periodic TCP and discovery keep-alive messages for server connections.
     */
    private fun handleServerKeepAlive(
        sock: Socket,
        peerId: String,
        peerHandle: PeerHandle
    ) {
        // TCP
        serviceScope.launch {
            val os = sock.getOutputStream()
            while (peerSockets.containsKey(peerId)) {
                try {
                    os.write(0)
                } catch (_: IOException) {
                    break
                }
                delay(2_000)
            }
        }

        // DISCOVERY
        serviceScope.launch {
            var msgId = 0
            while (peerSockets.containsKey(peerId)) {
                try {
                    subscribeSession?.sendMessage(peerHandle, msgId++, ByteArray(0))
                } catch (_: Exception) {
                    break
                }
                delay(20_000)
            }
        }
    }

    /**
     * Determines whether this device should act as the server in a given peer relationship.
     */
    private fun amIServerFor(peerId: String) = myPeerID < peerId

    /**
     * Stops the Wi-Fi Aware mesh services and cleans up sockets and sessions.
     */
    fun stopServices() {
        if (!isActive) return
        isActive = false
        Log.i(TAG, "Stopping mesh")
        sendLeaveAnnouncement()
        networkCallbacks.values.forEach { cm.unregisterNetworkCallback(it) }
        networkCallbacks.clear()
        publishSession?.close(); publishSession = null
        subscribeSession?.close(); subscribeSession = null
        wifiAwareSession?.close(); wifiAwareSession = null

        serverSockets.values.forEach { it.closeQuietly() }
        peerSockets.values.forEach { it.closeQuietly() }

        handleToPeerId.clear()
        serverSockets.clear()
        peerSockets.clear()

        cm.bindProcessToNetwork(null)

        peerManager.shutdown()
        fragmentManager.shutdown()
        securityManager.shutdown()
        storeForwardManager.shutdown()
        messageHandler.shutdown()
        packetProcessor.shutdown()

        serviceScope.cancel()
    }

    /**
     * Listens for incoming packets from a connected peer and dispatches them through
     * the packet processor.
     *
     * @param socket Socket connected to the peer
     * @param initialPeerHandleId Temporary identifier before peer ID resolution
     */
    private fun listenToPeer(socket: Socket, initialPeerHandleId: String) {
        val inStream = socket.getInputStream()
        val buf = ByteArray(4096)
        var appPeerId: String? = null

        while (isActive) {
            val len = try {
                inStream.read(buf)
            } catch (_: IOException) {
                break
            }
            if (len <= 0) break

            val raw = buf.copyOf(len)
            val bcPkt = BitchatPacket.fromBinaryData(raw) ?: continue

            val senderIDstr = String(bcPkt.senderID, Charsets.UTF_8)
            val ts = bcPkt.timestamp

            if (lastTimestamps.put(senderIDstr, ts) == ts) {
                Log.d(TAG, "duplicate packet from $senderIDstr @ $ts — dropping")
                continue
            }

            if (senderIDstr == myPeerID) continue

            if (appPeerId == null) {
                appPeerId = senderIDstr
                peerSockets[appPeerId] = socket
            }

            val routed = RoutedPacket(bcPkt, appPeerId)
            packetProcessor.processPacket(routed)
        }

        socket.closeQuietly()
        appPeerId?.let {
            peerSockets.remove(it)
            peerManager.removePeer(it)
        }
    }

    /**
     * Broadcasts raw bytes to all currently connected peers.
     */
    private fun broadcastPeer(data: ByteArray) {
        peerSockets.values.forEach { sock ->
            try {
                sock.getOutputStream().write(data)
            } catch (_: IOException) {
            }
        }
    }

    /**
     * Builds a broadcast BitchatPacket with given content, mentions, and optional channel.
     */
    private fun buildBroadcastPacket(
        content: String,
        mentions: List<String>,
        channel: String?
    ): BitchatPacket {
        val nickname = delegate?.getNickname() ?: myPeerID
        val msg = BitchatMessage(
            sender = nickname,
            content = content,
            timestamp = Date(),
            isRelay = false,
            senderPeerID = myPeerID,
            mentions = mentions.ifEmpty { null },
            channel = channel
        )
        val raw = msg.toBinaryPayload() ?: error("serialize failed")
        val sig = securityManager.signPacket(raw)
        return BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = myPeerID.toByteArray(),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = raw,
            signature = sig,
            ttl = MAX_TTL
        )
    }

    /**
     * Sends a broadcast message to all peers.
     *
     * @param content   Text content of the message
     * @param mentions  Optional list of mentioned peer IDs
     * @param channel   Optional channel name
     */
    fun sendMessage(
        content: String,
        mentions: List<String> = emptyList(),
        channel: String? = null
    ) {
        if (content.isBlank()) return
        serviceScope.launch {
            val packet = buildBroadcastPacket(content, mentions, channel)
            packet.toBinaryData()?.let { broadcastPeer(it) }
        }
    }

    /**
     * Sends a private encrypted message to a specific peer.
     *
     * @param content            The message text
     * @param recipientPeerID    Destination peer ID
     * @param recipientNickname  Recipient nickname
     * @param messageID          Optional message ID (UUID if null)
     */
    fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String? = null
    ) {
        if (content.isBlank() || recipientPeerID.isBlank()) return
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            val msg = BitchatMessage(
                id = messageID ?: UUID.randomUUID().toString(),
                sender = nickname,
                content = content,
                timestamp = Date(),
                isRelay = false,
                isPrivate = true,
                recipientNickname = recipientNickname,
                senderPeerID = myPeerID
            )
            val raw = msg.toBinaryPayload() ?: return@launch
            try {
                val blockSize = MessagePadding.optimalBlockSize(raw.size)
                val padded = MessagePadding.pad(raw, blockSize)
                val enc = securityManager.encryptForPeer(padded, recipientPeerID)
                if (enc != null) {
                    val sig = securityManager.signPacket(enc)
                    val packet = BitchatPacket(
                        type = MessageType.MESSAGE.value,
                        senderID = myPeerID.toByteArray(),
                        recipientID = recipientPeerID.toByteArray(),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = enc,
                        signature = sig,
                        ttl = MAX_TTL
                    )
                    if (storeForwardManager.shouldCacheForPeer(recipientPeerID)) {
                        storeForwardManager.cacheMessage(packet, msg.id)
                    }
                    delay(Random.nextLong(50, 500))
                    packet.toBinaryData()?.let { broadcastPeer(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send private message: ${e.message}")
            }
        }
    }

    /**
     * Broadcasts an ANNOUNCE packet to the entire mesh.
     */
    fun sendBroadcastAnnounce() {
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            val packet = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = 3u,
                senderID = myPeerID.toByteArray(),
                recipientID = null,
                timestamp = System.currentTimeMillis().toULong(),
                payload = nickname.toByteArray(),
                signature = null
            )
            listOf(0L, 500L, 1000L).forEach { base ->
                delay(base + Random.nextLong(0, 500))
                packet.toBinaryData()?.let { broadcastPeer(it) }
            }
        }
    }

    /**
     * Sends an ANNOUNCE packet to a specific peer.
     */
    private fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 3u,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        packet.toBinaryData()?.let { broadcastPeer(it) }
        peerManager.markPeerAsAnnouncedTo(peerID)
    }

    /**
     * Sends a KEY_EXCHANGE packet to a peer to initiate secure communications.
     */
    private fun sendKeyExchange(peerID: String) {
        val keyData = securityManager.getCombinedPublicKeyData()
        val packet = BitchatPacket(
            type = MessageType.KEY_EXCHANGE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = keyData
        )
        packet.toBinaryData()?.let { broadcastPeer(it) }
    }

    /**
     * Sends a LEAVE announcement to all peers before disconnecting.
     */
    private fun sendLeaveAnnouncement() {
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        packet.toBinaryData()?.let { broadcastPeer(it) }
    }

    /** @return Mapping of peer IDs to nicknames. */
    fun getPeerNicknames(): Map<String, String> = peerManager.getAllPeerNicknames()

    /** @return Mapping of peer IDs to RSSI values. */
    fun getPeerRSSI(): Map<String, Int> = peerManager.getAllPeerRSSI()

    /** @return IP address for a given peer if connected. */
    fun getDeviceAddressForPeer(peerID: String): String? =
        peerSockets[peerID]?.inetAddress?.hostAddress

    /** @return Mapping of peer IDs to their device IP addresses. */
    fun getDeviceAddressToPeerMapping(): Map<String, String> =
        peerSockets.mapValues { it.value.inetAddress.hostAddress }

    /** @return A printable string of all peer device addresses. */
    fun printDeviceAddressesForPeers(): String =
        getDeviceAddressToPeerMapping().entries.joinToString("\n") { "${it.key} -> ${it.value}" }

    /**
     * @return A detailed string containing the debug status of all mesh components.
     */
    fun getDebugStatus(): String = buildString {
        appendLine("=== Wi‑Fi Aware Mesh Debug Status ===")
        appendLine("My Peer ID: $myPeerID")
        appendLine("Peers: ${peerSockets.keys}")
        appendLine(peerManager.getDebugInfo(getDeviceAddressToPeerMapping()))
        appendLine(fragmentManager.getDebugInfo())
        appendLine(securityManager.getDebugInfo())
        appendLine(storeForwardManager.getDebugInfo())
        appendLine(messageHandler.getDebugInfo())
        appendLine(packetProcessor.getDebugInfo())
    }

    /** Utility extension to safely close sockets. */
    private fun Socket.closeQuietly() = try {
        close()
    } catch (_: Exception) {
    }

    /** Utility extension to safely close server sockets. */
    private fun ServerSocket.closeQuietly() = try {
        close()
    } catch (_: Exception) {
    }

    /**
     * Generates a 4-byte random hex string compatible with iOS peer ID format.
     */
    private fun generateCompatiblePeerID(): String {
        val bytes = ByteArray(4).also { Random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Delegate interface for mesh service callbacks (maintains exact same interface)
 */
interface WifiAwareMeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didConnectToPeer(peerID: String)
    fun didDisconnectFromPeer(peerID: String)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(ack: DeliveryAck)
    fun didReceiveReadReceipt(receipt: ReadReceipt)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
    fun registerPeerPublicKey(peerID: String, publicKeyData: ByteArray)
}