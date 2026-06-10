package com.uno.game.network

import android.util.Log
import com.google.gson.Gson
import com.uno.game.BuildConfig
import com.uno.game.models.GameState
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

object SocketManager {
    private const val TAG = "SocketManager"
    private var socket: Socket? = null
    private val gson = Gson()

    var onRoomUpdate: ((JSONObject) -> Unit)? = null
    var onGameStarted: ((GameState) -> Unit)? = null
    var onGameState: ((GameState) -> Unit)? = null
    var onUnoCalled: ((String) -> Unit)? = null
    var onUnoChallenge: ((JSONObject) -> Unit)? = null
    var onPlayerDisconnected: ((String) -> Unit)? = null
    var onPlayerFinished: ((String, Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConnectionChange: ((Boolean) -> Unit)? = null
    var onCardAutoPlayed: ((String, String) -> Unit)? = null

    // Called after socket reconnects so activities can re-emit join_room
    var onReconnected: (() -> Unit)? = null

    // Voice chat callbacks
    var onVoicePeerJoined: ((String) -> Unit)? = null
    var onVoicePeerLeft: ((String) -> Unit)? = null
    var onVoiceOffer: ((String, Any) -> Unit)? = null
    var onVoiceAnswer: ((String, Any) -> Unit)? = null
    var onVoiceIceCandidate: ((String, Any) -> Unit)? = null

    fun connect() {
        if (socket?.connected() == true) return
        try {
            val options = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionAttempts(Int.MAX_VALUE)   // never give up
                .setReconnectionDelay(1000)               // 1s first retry
                .setReconnectionDelayMax(5000)            // max 5s between retries
                .setTimeout(20000)                        // 20s connect timeout
                .build()
            socket = IO.socket(BuildConfig.SERVER_URL, options)
            attachListeners()
            socket?.connect()
            Log.d(TAG, "Connecting to ${BuildConfig.SERVER_URL}")
        } catch (e: URISyntaxException) {
            Log.e(TAG, "URI error: ${e.message}")
        }
    }

    private fun attachListeners() {
        socket?.apply {
            on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "✅ Socket connected: ${id()}")
                onConnectionChange?.invoke(true)
            }
            on(Socket.EVENT_DISCONNECT) { args ->
                Log.d(TAG, "❌ Socket disconnected: ${args.firstOrNull()}")
                onConnectionChange?.invoke(false)
            }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Connection error: ${args.firstOrNull()}")
            }
            // Socket.IO fires "reconnect" after a successful reconnection
            on("reconnect") { args ->
                Log.d(TAG, "🔄 Socket reconnected (attempt ${args.firstOrNull()})")
                // Notify activities so they can re-join their rooms
                onReconnected?.invoke()
                onConnectionChange?.invoke(true)
            }
            on("room_update") { args ->
                val obj = args[0] as JSONObject
                onRoomUpdate?.invoke(obj)
            }
            on("game_started") { args ->
                parseGameState(args[0])?.let { onGameStarted?.invoke(it) }
            }
            on("game_state") { args ->
                parseGameState(args[0])?.let { onGameState?.invoke(it) }
            }
            on("uno_called") { args ->
                val obj = args[0] as JSONObject
                onUnoCalled?.invoke(obj.optString("playerId"))
            }
            on("uno_challenge") { args ->
                onUnoChallenge?.invoke(args[0] as JSONObject)
            }
            on("player_finished") { args ->
                val obj = args[0] as JSONObject
                onPlayerFinished?.invoke(obj.optString("playerId"), obj.optInt("position"))
            }
            on("player_disconnected") { args ->
                val obj = args[0] as JSONObject
                onPlayerDisconnected?.invoke(obj.optString("playerId"))
            }
            on("error") { args ->
                val obj = args[0] as JSONObject
                val msg = obj.optString("message")
                Log.e(TAG, "Server error: $msg")
                onError?.invoke(msg)
            }
            on("card_auto_played") { args ->
                val obj = args[0] as JSONObject
                val playerId = obj.optString("playerId")
                val cardValue = obj.optJSONObject("card")?.optString("value") ?: ""
                onCardAutoPlayed?.invoke(playerId, cardValue)
            }
            // Voice
            on("voice_peer_joined") { args ->
                val obj = args[0] as JSONObject
                onVoicePeerJoined?.invoke(obj.optString("socketId"))
            }
            on("voice_peer_left") { args ->
                val obj = args[0] as JSONObject
                onVoicePeerLeft?.invoke(obj.optString("socketId"))
            }
            on("voice_offer") { args ->
                val obj = args[0] as JSONObject
                onVoiceOffer?.invoke(obj.optString("fromSocketId"), obj.get("offer"))
            }
            on("voice_answer") { args ->
                val obj = args[0] as JSONObject
                onVoiceAnswer?.invoke(obj.optString("fromSocketId"), obj.get("answer"))
            }
            on("voice_ice_candidate") { args ->
                val obj = args[0] as JSONObject
                onVoiceIceCandidate?.invoke(obj.optString("fromSocketId"), obj.get("candidate"))
            }
        }
    }

    private fun parseGameState(arg: Any?): GameState? {
        return try {
            gson.fromJson(arg.toString(), GameState::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            null
        }
    }

    fun joinRoom(roomCode: String, playerId: String, username: String) {
        emit("join_room", JSONObject().apply {
            put("roomCode", roomCode)
            put("playerId", playerId)
            put("username", username)
        })
    }

    fun startGame(roomCode: String, playerId: String) {
        val data = JSONObject().apply {
            put("roomCode", roomCode)
            put("playerId", playerId)
        }
        if (socket?.connected() == true) {
            socket?.emit("start_game", data)
        } else {
            onError?.invoke("Not connected to server. Trying to reconnect...")
            socket?.connect()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (socket?.connected() == true) socket?.emit("start_game", data)
                else onError?.invoke("Still not connected. Check your internet connection.")
            }, 2000)
        }
    }

    fun playCard(roomCode: String, playerId: String, cardId: String, chosenColor: String? = null) {
        emit("play_card", JSONObject().apply {
            put("roomCode", roomCode)
            put("playerId", playerId)
            put("cardId", cardId)
            chosenColor?.let { put("chosenColor", it) }
        })
    }

    fun drawCard(roomCode: String, playerId: String) {
        emit("draw_card", JSONObject().apply {
            put("roomCode", roomCode)
            put("playerId", playerId)
        })
    }

    fun sayUno(roomCode: String, playerId: String) {
        emit("say_uno", JSONObject().apply {
            put("roomCode", roomCode)
            put("playerId", playerId)
        })
    }

    fun challengeUno(roomCode: String, challengerId: String, targetId: String) {
        emit("challenge_uno", JSONObject().apply {
            put("roomCode", roomCode)
            put("challengerId", challengerId)
            put("targetId", targetId)
        })
    }

    fun joinVoice(roomCode: String) = emit("voice_join", JSONObject().put("roomCode", roomCode))
    fun leaveVoice(roomCode: String) = emit("voice_leave", JSONObject().put("roomCode", roomCode))

    fun sendVoiceOffer(targetSocketId: String, offer: Any, roomCode: String) {
        emit("voice_offer", JSONObject().apply {
            put("targetSocketId", targetSocketId)
            put("offer", offer)
            put("roomCode", roomCode)
        })
    }

    fun sendVoiceAnswer(targetSocketId: String, answer: Any) {
        emit("voice_answer", JSONObject().apply {
            put("targetSocketId", targetSocketId)
            put("answer", answer)
        })
    }

    fun sendIceCandidate(targetSocketId: String, candidate: Any) {
        emit("voice_ice_candidate", JSONObject().apply {
            put("targetSocketId", targetSocketId)
            put("candidate", candidate)
        })
    }

    private fun emit(event: String, data: JSONObject) {
        if (socket?.connected() == true) {
            socket?.emit(event, data)
        } else {
            Log.w(TAG, "Not connected, can't emit $event — queuing reconnect")
            // Try to reconnect then re-emit once
            socket?.connect()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (socket?.connected() == true) socket?.emit(event, data)
            }, 2000)
        }
    }

    fun requestGameState(roomCode: String) {
        emit("get_game_state", JSONObject().apply { put("roomCode", roomCode) })
    }

    fun getSocketId(): String? = socket?.id()
    fun disconnect() { socket?.disconnect(); socket = null }
    fun isConnected(): Boolean = socket?.connected() == true
}
