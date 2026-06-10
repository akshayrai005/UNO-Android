package com.uno.game.audio

import android.content.Context
import android.util.Log
import com.uno.game.network.SocketManager
import org.webrtc.*

/**
 * WebRTC voice chat — full mesh, one PeerConnection per remote player.
 *
 * Flow:
 * 1. Call initialize() → joins the socket voice room, starts local audio capture.
 * 2. When a peer joins  → we create an offer and send it via Socket.IO.
 * 3. When we receive an offer → we create an answer and send it back.
 * 4. ICE candidates are exchanged until media flows peer-to-peer.
 * 5. Call release() on Activity.onDestroy().
 */
class VoiceChatManager(
    private val context: Context,
    private val roomCode: String
) {
    private val TAG = "VoiceChat"

    // WebRTC core
    private var factory: PeerConnectionFactory? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private val peers = mutableMapOf<String, PeerConnection>() // socketId → PeerConnection
    private val pendingCandidates = mutableMapOf<String, MutableList<IceCandidate>>()

    private var isMuted = false
    private var isInitialized = false

    // STUN/TURN servers — free STUN is fine for LAN/family use
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
    )

    // ── Init ─────────────────────────────────────────────────────────────────

    fun initialize() {
        if (isInitialized) return
        Log.d(TAG, "Initializing WebRTC voice for room $roomCode")

        // Init PeerConnectionFactory
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()

        // Create local audio track
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        localAudioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack  = factory?.createAudioTrack("ARDAMSa0", localAudioSource)
        localAudioTrack?.setEnabled(!isMuted)

        hookSocketEvents()
        SocketManager.joinVoice(roomCode)
        isInitialized = true
        Log.d(TAG, "WebRTC initialized — waiting for peers")
    }

    // ── Socket signaling hooks ────────────────────────────────────────────────

    private fun hookSocketEvents() {
        SocketManager.onVoicePeerJoined = { socketId ->
            Log.d(TAG, "Peer joined: $socketId — creating offer")
            createPeerConnection(socketId, isOfferer = true)
        }

        SocketManager.onVoicePeerLeft = { socketId ->
            Log.d(TAG, "Peer left: $socketId")
            peers[socketId]?.dispose()
            peers.remove(socketId)
            pendingCandidates.remove(socketId)
        }

        SocketManager.onVoiceOffer = { fromSocketId, offerObj ->
            Log.d(TAG, "Received offer from $fromSocketId")
            val pc = createPeerConnection(fromSocketId, isOfferer = false)
            val sdp = SessionDescription(
                SessionDescription.Type.OFFER,
                offerObj.toString().extractSdp()
            )
            pc.setRemoteDescription(SimpleSdpObserver("setRemote-offer"), sdp)
            // drain pending ICE
            pendingCandidates[fromSocketId]?.forEach { pc.addIceCandidate(it) }
            pendingCandidates.remove(fromSocketId)
            // create answer
            val answerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            pc.createAnswer(object : SimpleSdpObserver("createAnswer") {
                override fun onCreateSuccess(answer: SessionDescription) {
                    pc.setLocalDescription(SimpleSdpObserver("setLocal-answer"), answer)
                    SocketManager.sendVoiceAnswer(fromSocketId, answer.toJsonObj())
                }
            }, answerConstraints)
        }

        SocketManager.onVoiceAnswer = { fromSocketId, answerObj ->
            Log.d(TAG, "Received answer from $fromSocketId")
            val pc = peers[fromSocketId]
            if (pc != null) {
                val sdp = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    answerObj.toString().extractSdp()
                )
                pc.setRemoteDescription(SimpleSdpObserver("setRemote-answer"), sdp)
                pendingCandidates[fromSocketId]?.forEach { pc.addIceCandidate(it) }
                pendingCandidates.remove(fromSocketId)
            }
        }

        SocketManager.onVoiceIceCandidate = { fromSocketId, candidateObj ->
            val candidateStr = candidateObj.toString()
            val candidate = parseIceCandidate(candidateStr)
            val pc = peers[fromSocketId]
            if (pc != null) {
                pc.addIceCandidate(candidate)
            } else {
                // buffer until PeerConnection is ready
                pendingCandidates.getOrPut(fromSocketId) { mutableListOf() }.add(candidate)
            }
        }
    }

    // ── PeerConnection creation ───────────────────────────────────────────────

    private fun createPeerConnection(socketId: String, isOfferer: Boolean): PeerConnection {
        peers[socketId]?.dispose()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                SocketManager.sendIceCandidate(socketId, candidate.toJsonObj())
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                Log.d(TAG, "Remote track received from $socketId")
                // Audio plays automatically — no video to handle
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "PeerConnection[$socketId] state: $state")
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, streams: Array<MediaStream>) {}
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
        })!!

        // Add local audio track
        val streamId = "ARDAMSv0"
        pc.addTrack(localAudioTrack!!, listOf(streamId))

        peers[socketId] = pc

        if (isOfferer) {
            val offerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            pc.createOffer(object : SimpleSdpObserver("createOffer") {
                override fun onCreateSuccess(offer: SessionDescription) {
                    pc.setLocalDescription(SimpleSdpObserver("setLocal-offer"), offer)
                    SocketManager.sendVoiceOffer(socketId, offer.toJsonObj(), roomCode)
                }
            }, offerConstraints)
        }

        return pc
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        localAudioTrack?.setEnabled(!isMuted)
        Log.d(TAG, "Mute: $isMuted")
        return isMuted
    }

    fun isMuted() = isMuted

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun release() {
        SocketManager.leaveVoice(roomCode)
        SocketManager.onVoicePeerJoined    = null
        SocketManager.onVoicePeerLeft      = null
        SocketManager.onVoiceOffer         = null
        SocketManager.onVoiceAnswer        = null
        SocketManager.onVoiceIceCandidate  = null
        peers.values.forEach { it.dispose() }
        peers.clear()
        pendingCandidates.clear()
        localAudioTrack?.dispose()
        localAudioSource?.dispose()
        factory?.dispose()
        factory         = null
        localAudioTrack = null
        localAudioSource = null
        isInitialized   = false
        Log.d(TAG, "VoiceChatManager released")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Pull "sdp" field out of a JSON-like string e.g. {"type":"offer","sdp":"v=0\r\n..."} */
    private fun String.extractSdp(): String {
        return try {
            val obj = org.json.JSONObject(this)
            obj.getString("sdp")
        } catch (e: Exception) {
            this // already raw SDP
        }
    }

    private fun SessionDescription.toJsonObj(): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("type", type.canonicalForm())
            put("sdp", description)
        }

    private fun IceCandidate.toJsonObj(): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("candidate",     sdp)
            put("sdpMid",        sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
        }

    private fun parseIceCandidate(json: String): IceCandidate {
        val obj = org.json.JSONObject(json)
        return IceCandidate(
            obj.optString("sdpMid"),
            obj.optInt("sdpMLineIndex"),
            obj.optString("candidate")
        )
    }
}

/** Minimal SdpObserver that only logs errors — override onCreateSuccess where needed. */
open class SimpleSdpObserver(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) { Log.e("SdpObserver", "$tag createFailure: $error") }
    override fun onSetFailure(error: String)    { Log.e("SdpObserver", "$tag setFailure: $error") }
}
