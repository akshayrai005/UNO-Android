package com.uno.game.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.uno.game.network.SocketManager
import org.webrtc.*

/**
 * WebRTC voice chat — full mesh, one PeerConnection per remote player.
 * Audio is routed through STREAM_VOICE_CALL at max volume for best clarity.
 */
class VoiceChatManager(
    private val context: Context,
    private val roomCode: String
) {
    private val TAG = "VoiceChat"

    private var factory: PeerConnectionFactory? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private val peers = mutableMapOf<String, PeerConnection>()
    private val pendingCandidates = mutableMapOf<String, MutableList<IceCandidate>>()

    private var isMuted = false
    private var isInitialized = false

    // AudioManager for volume + mode control
    private var audioManager: AudioManager? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeakerOn = false

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
    )

    // ── Init ──────────────────────────────────────────────────────────────────

    fun initialize() {
        if (isInitialized) return
        Log.d(TAG, "Initializing WebRTC voice for room $roomCode")

        // ── Fix low volume: set audio mode + max volume ───────────────────────
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.let { am ->
            savedAudioMode = am.mode
            savedSpeakerOn = am.isSpeakerphoneOn

            // MODE_IN_COMMUNICATION is required for WebRTC voice — uses VoIP path
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            // Loudspeaker ON so everyone around the phone can hear
            am.isSpeakerphoneOn = true

            // Crank STREAM_VOICE_CALL to maximum
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                maxVol,
                0 // no UI flag — silent change
            )
            Log.d(TAG, "AudioManager: mode=IN_COMMUNICATION, speakerOn=true, vol=$maxVol/$maxVol")
        }

        // ── WebRTC factory ────────────────────────────────────────────────────
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        // ── Local audio track with all quality enhancements ───────────────────
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation",     "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression",     "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl",      "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter",       "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring",       "false"))
            // Boost mic gain
            optional.add(MediaConstraints.KeyValuePair("googDAEchoCancellation",   "true"))
            optional.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection",  "true"))
        }
        localAudioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack  = factory?.createAudioTrack("ARDAMSa0", localAudioSource)
        localAudioTrack?.setEnabled(!isMuted)

        hookSocketEvents()
        SocketManager.joinVoice(roomCode)
        isInitialized = true
        Log.d(TAG, "WebRTC initialized — waiting for peers")
    }

    // ── Socket signaling ──────────────────────────────────────────────────────

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
            pendingCandidates[fromSocketId]?.forEach { pc.addIceCandidate(it) }
            pendingCandidates.remove(fromSocketId)
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
            val candidate = parseIceCandidate(candidateObj.toString())
            val pc = peers[fromSocketId]
            if (pc != null) {
                pc.addIceCandidate(candidate)
            } else {
                pendingCandidates.getOrPut(fromSocketId) { mutableListOf() }.add(candidate)
            }
        }
    }

    // ── PeerConnection ────────────────────────────────────────────────────────

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
                // Set remote track volume to max (1.0 = 100%)
                transceiver.receiver.track()?.let { track ->
                    if (track is AudioTrack) {
                        track.setVolume(10.0) // WebRTC AudioTrack volume: 0.0-10.0
                        Log.d(TAG, "Remote audio track volume set to 10.0 (max)")
                    }
                }
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
            override fun onAddTrack(r: RtpReceiver, streams: Array<MediaStream>) {
                // Also set volume here for older WebRTC builds
                r.track()?.let { track ->
                    if (track is AudioTrack) {
                        track.setVolume(10.0)
                    }
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
        })!!

        pc.addTrack(localAudioTrack!!, listOf("ARDAMSv0"))
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

    // ── Controls ──────────────────────────────────────────────────────────────

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        localAudioTrack?.setEnabled(!isMuted)
        Log.d(TAG, "Mic muted: $isMuted")
        return isMuted
    }

    fun isMuted() = isMuted

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun release() {
        // Restore audio state
        audioManager?.let { am ->
            am.mode = savedAudioMode
            am.isSpeakerphoneOn = savedSpeakerOn
        }
        audioManager = null

        SocketManager.leaveVoice(roomCode)
        SocketManager.onVoicePeerJoined   = null
        SocketManager.onVoicePeerLeft     = null
        SocketManager.onVoiceOffer        = null
        SocketManager.onVoiceAnswer       = null
        SocketManager.onVoiceIceCandidate = null

        peers.values.forEach { it.dispose() }
        peers.clear()
        pendingCandidates.clear()

        localAudioTrack?.dispose()
        localAudioSource?.dispose()
        factory?.dispose()
        factory          = null
        localAudioTrack  = null
        localAudioSource = null
        isInitialized    = false
        Log.d(TAG, "VoiceChatManager released")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.extractSdp(): String {
        return try {
            org.json.JSONObject(this).getString("sdp")
        } catch (e: Exception) {
            this
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

open class SimpleSdpObserver(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) { Log.e("SdpObserver", "$tag createFailure: $error") }
    override fun onSetFailure(error: String)    { Log.e("SdpObserver", "$tag setFailure: $error") }
}
