package com.uno.game.ui.lobby

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.uno.game.databinding.ActivityLobbyBinding
import com.uno.game.network.SocketManager
import com.uno.game.ui.game.GameActivity
import com.uno.game.utils.PreferencesManager

class LobbyActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLobbyBinding
    private lateinit var playerAdapter: LobbyPlayerAdapter
    private var roomCode: String = ""
    private var playerId: String = ""
    private var isHost: Boolean = false

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_IS_HOST = "is_host"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLobbyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: return
        isHost = intent.getBooleanExtra(EXTRA_IS_HOST, false)
        playerId = PreferencesManager.getPlayerId(this) ?: return
        val username = PreferencesManager.getUsername(this) ?: return

        binding.tvRoomCode.text = "Room: $roomCode"

        // Show START button only for host, disabled until 2+ players
        if (isHost) {
            binding.btnStart.visibility = View.VISIBLE
            binding.btnStart.isEnabled = false
            binding.btnStart.alpha = 0.5f
            binding.btnStart.text = "Waiting for players..."
        } else {
            binding.btnStart.visibility = View.GONE
        }

        playerAdapter = LobbyPlayerAdapter()
        binding.rvLobbyPlayers.adapter = playerAdapter

        setupSocket()
        SocketManager.joinRoom(roomCode, playerId, username)

        binding.btnStart.setOnClickListener {
            if (isHost) {
                SocketManager.startGame(roomCode, playerId)
                binding.btnStart.isEnabled = false
                binding.btnStart.text = "Starting..."
            }
        }

        binding.btnCopyCode.setOnClickListener {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Room Code", roomCode))
            Toast.makeText(this, "✅ Room code copied!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSocket() {
        SocketManager.onRoomUpdate = { json ->
            val players = json.optJSONArray("players")
            val list = mutableListOf<LobbyPlayer>()
            if (players != null) {
                for (i in 0 until players.length()) {
                    val p = players.getJSONObject(i)
                    list.add(LobbyPlayer(
                        id = p.optString("id"),
                        username = p.optString("username"),
                        avatarColor = p.optString("avatar_color", "#FF6B6B"),
                        seatPosition = p.optInt("seat_position")
                    ))
                }
            }
            runOnUiThread {
                playerAdapter.submitList(list)
                binding.tvPlayerCount.text = "${list.size}/6 Players"

                // Update START button state for host
                if (isHost) {
                    if (list.size >= 2) {
                        binding.btnStart.isEnabled = true
                        binding.btnStart.alpha = 1.0f
                        binding.btnStart.text = "START GAME (${list.size} players)"
                    } else {
                        binding.btnStart.isEnabled = false
                        binding.btnStart.alpha = 0.5f
                        binding.btnStart.text = "Waiting for players..."
                    }
                }
            }
        }

        SocketManager.onGameStarted = { _ ->
            runOnUiThread {
                startActivity(Intent(this, GameActivity::class.java).apply {
                    putExtra(GameActivity.EXTRA_ROOM_CODE, roomCode)
                })
                finish()
            }
        }

        SocketManager.onError = { msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                binding.btnStart.isEnabled = true
                binding.btnStart.alpha = 1.0f
                binding.btnStart.text = "START GAME"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SocketManager.onRoomUpdate = null
        SocketManager.onGameStarted = null
        SocketManager.onError = null
    }
}

data class LobbyPlayer(
    val id: String,
    val username: String,
    val avatarColor: String,
    val seatPosition: Int
)
