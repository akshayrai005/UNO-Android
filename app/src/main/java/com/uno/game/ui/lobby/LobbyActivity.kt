package com.uno.game.ui.lobby

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.uno.game.R
import com.uno.game.databinding.ActivityLobbyBinding
import com.uno.game.network.SocketKeepAliveService
import com.uno.game.network.SocketManager
import com.uno.game.ui.game.GameActivity
import com.uno.game.utils.PreferencesManager

class LobbyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLobbyBinding
    private lateinit var playerAdapter: LobbyPlayerAdapter

    private var roomCode   = ""
    private var playerId   = ""
    private var username   = ""
    private var isHost     = false
    private var currentPlayerCount = 0

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_IS_HOST   = "is_host"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLobbyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: run { finish(); return }
        isHost   = intent.getBooleanExtra(EXTRA_IS_HOST, false)
        playerId = PreferencesManager.getPlayerId(this) ?: run { finish(); return }
        username = PreferencesManager.getUsername(this) ?: run { finish(); return }

        binding.tvRoomCode.text = roomCode
        PreferencesManager.saveLastRoom(this, roomCode)

        if (isHost) {
            binding.btnStart.visibility = View.VISIBLE
            updateStartButton()
        } else {
            binding.btnStart.visibility = View.GONE
        }

        playerAdapter = LobbyPlayerAdapter().also {
            if (isHost) it.hostId = playerId
        }

        binding.rvLobbyPlayers.apply {
            layoutManager = LinearLayoutManager(this@LobbyActivity)
            adapter = playerAdapter
        }

        setupSocketListeners()
        connectAndJoin()

        binding.btnStart.setOnClickListener { handleStartClick() }

        binding.btnCopyCode.setOnClickListener {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Room Code", roomCode))
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        // Start foreground service to keep socket alive when app is in background
        SocketKeepAliveService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // Always reconnect + rejoin when coming back to foreground
        if (!SocketManager.isConnected()) {
            showReconnectingState()
            SocketManager.connect()
            Handler(Looper.getMainLooper()).postDelayed({
                if (SocketManager.isConnected()) joinRoom()
                else Toast.makeText(this, "⏳ Reconnecting to server...", Toast.LENGTH_SHORT).show()
            }, 1500)
        } else {
            // Already connected — re-emit join to refresh player list
            joinRoom()
        }
    }

    override fun onPause() {
        super.onPause()
        // Socket stays alive via SocketKeepAliveService — don't disconnect
    }

    // ── Connect + join ────────────────────────────────────────────────────────
    private fun connectAndJoin() {
        if (!SocketManager.isConnected()) {
            SocketManager.connect()
            Handler(Looper.getMainLooper()).postDelayed({ joinRoom() }, 1000)
        } else {
            joinRoom()
        }
    }

    private fun joinRoom() {
        SocketManager.joinRoom(roomCode, playerId, username)
    }

    private fun showReconnectingState() {
        if (isHost) {
            binding.btnStart.isEnabled = false
            binding.btnStart.text = "⏳ Reconnecting..."
        }
    }

    // ── Start button ──────────────────────────────────────────────────────────
    private fun handleStartClick() {
        if (!isHost) return
        if (!SocketManager.isConnected()) {
            Toast.makeText(this, "⚠️ Reconnecting…", Toast.LENGTH_SHORT).show()
            SocketManager.connect()
            Handler(Looper.getMainLooper()).postDelayed({
                if (SocketManager.isConnected()) doStartGame()
                else Toast.makeText(this, "❌ Connection failed. Check internet.", Toast.LENGTH_LONG).show()
            }, 2000)
            return
        }
        doStartGame()
    }

    private fun doStartGame() {
        if (currentPlayerCount < 2) {
            Toast.makeText(this, getString(R.string.min_players), Toast.LENGTH_SHORT).show()
            return
        }
        SocketManager.startGame(roomCode, playerId)
        binding.btnStart.isEnabled = false
        binding.btnStart.text      = "Starting…"
    }

    // ── Socket listeners ──────────────────────────────────────────────────────
    private fun setupSocketListeners() {

        SocketManager.onReconnected = {
            runOnUiThread {
                joinRoom()
                Toast.makeText(this, "✅ Reconnected!", Toast.LENGTH_SHORT).show()
                if (isHost) updateStartButton()
            }
        }

        SocketManager.onConnectionChange = { connected ->
            runOnUiThread {
                if (!connected) {
                    if (isHost) {
                        binding.btnStart.isEnabled = false
                        binding.btnStart.text = "⏳ Reconnecting..."
                    }
                } else {
                    if (isHost) updateStartButton()
                }
            }
        }

        SocketManager.onRoomUpdate = { json ->
            val playersArray = json.optJSONArray("players")
            val list = mutableListOf<LobbyPlayer>()
            if (playersArray != null) {
                for (i in 0 until playersArray.length()) {
                    val p = playersArray.getJSONObject(i)
                    list.add(LobbyPlayer(
                        id           = p.optString("id"),
                        username     = p.optString("username"),
                        avatarColor  = p.optString("avatar_color", "#FF6B6B"),
                        seatPosition = p.optInt("seat_position")
                    ))
                }
            }
            val hostFromJson = json.optString("host_id", "")
            runOnUiThread {
                if (hostFromJson.isNotBlank()) playerAdapter.hostId = hostFromJson
                currentPlayerCount = list.size
                playerAdapter.submitList(list)
                binding.tvPlayerCount.text = getString(R.string.connected_players, list.size, 6)
                if (isHost) updateStartButton()
            }
        }

        SocketManager.onGameStarted = { _ ->
            runOnUiThread {
                // Stop keep-alive service — game activity manages its own connection
                SocketKeepAliveService.stop(this)
                startActivity(Intent(this, GameActivity::class.java).apply {
                    putExtra(GameActivity.EXTRA_ROOM_CODE, roomCode)
                })
                finish()
            }
        }

        SocketManager.onError = { msg ->
            runOnUiThread {
                Toast.makeText(this, "❌ $msg", Toast.LENGTH_LONG).show()
                if (isHost) updateStartButton()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun updateStartButton() {
        if (!isHost) return
        if (currentPlayerCount >= 2) {
            binding.btnStart.isEnabled = true
            binding.btnStart.alpha     = 1f
            binding.btnStart.text      = getString(R.string.start_game) + " ($currentPlayerCount players)"
        } else {
            binding.btnStart.isEnabled = false
            binding.btnStart.alpha     = 0.5f
            binding.btnStart.text      = getString(R.string.waiting_for_host)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SocketKeepAliveService.stop(this)
        SocketManager.onRoomUpdate       = null
        SocketManager.onGameStarted      = null
        SocketManager.onError            = null
        SocketManager.onConnectionChange = null
        SocketManager.onReconnected      = null
    }
}

data class LobbyPlayer(
    val id:           String,
    val username:     String,
    val avatarColor:  String,
    val seatPosition: Int
)
