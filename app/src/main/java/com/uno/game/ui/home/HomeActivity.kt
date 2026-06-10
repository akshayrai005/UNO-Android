package com.uno.game.ui.home

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.uno.game.databinding.ActivityHomeBinding
import com.uno.game.network.ApiService
import com.uno.game.network.SocketManager
import com.uno.game.ui.lobby.LobbyActivity
import com.uno.game.utils.PreferencesManager
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding

    // Only 4 clean colors
    private val avatarColors = listOf("#E53935", "#1E88E5", "#43A047", "#FB8C00")
    private var selectedColor = "#E53935"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SocketManager.connect()

        PreferencesManager.getUsername(this)?.let {
            binding.etUsername.setText(it)
        }

        setupColorPicker()

        binding.btnCreateRoom.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            if (username.isBlank()) { Toast.makeText(this, "Enter a username!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            createRoomFlow(username)
        }

        binding.btnJoinRoom.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val code = binding.etRoomCode.text.toString().trim().uppercase()
            if (username.isBlank()) { Toast.makeText(this, "Enter a username!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (code.isBlank()) { Toast.makeText(this, "Enter a room code!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            joinRoomFlow(username, code)
        }
    }

    private fun setupColorPicker() {
        val colorViews = listOf(binding.color1, binding.color2, binding.color3, binding.color4)
        colorViews.forEachIndexed { i, view ->
            view.setBackgroundColor(Color.parseColor(avatarColors[i]))
            view.setOnClickListener {
                selectedColor = avatarColors[i]
                colorViews.forEach { v -> v.scaleX = 1f; v.scaleY = 1f; v.alpha = 0.6f }
                view.scaleX = 1.3f; view.scaleY = 1.3f; view.alpha = 1f
            }
        }
        // Select first by default
        colorViews[0].scaleX = 1.3f; colorViews[0].scaleY = 1.3f; colorViews[0].alpha = 1f
        colorViews.drop(1).forEach { it.alpha = 0.6f }
    }

    private fun createRoomFlow(username: String) {
        binding.btnCreateRoom.isEnabled = false
        binding.btnCreateRoom.text = "Creating..."
        lifecycleScope.launch {
            val playerResult = ApiService.createPlayer(username, selectedColor)
            playerResult.onSuccess { player ->
                PreferencesManager.savePlayer(this@HomeActivity, player.id, username)
                val roomResult = ApiService.createRoom(player.id)
                roomResult.onSuccess { room ->
                    startActivity(Intent(this@HomeActivity, LobbyActivity::class.java).apply {
                        putExtra(LobbyActivity.EXTRA_ROOM_CODE, room.roomCode)
                        putExtra(LobbyActivity.EXTRA_IS_HOST, true)
                    })
                }.onFailure { Toast.makeText(this@HomeActivity, "Failed to create room", Toast.LENGTH_SHORT).show() }
            }.onFailure { Toast.makeText(this@HomeActivity, "Error: ${it.message}", Toast.LENGTH_SHORT).show() }
            binding.btnCreateRoom.isEnabled = true
            binding.btnCreateRoom.text = "CREATE ROOM"
        }
    }

    private fun joinRoomFlow(username: String, code: String) {
        binding.btnJoinRoom.isEnabled = false
        binding.btnJoinRoom.text = "Joining..."
        lifecycleScope.launch {
            val playerResult = ApiService.createPlayer(username, selectedColor)
            playerResult.onSuccess { player ->
                PreferencesManager.savePlayer(this@HomeActivity, player.id, username)
                ApiService.getRoom(code).onSuccess {
                    startActivity(Intent(this@HomeActivity, LobbyActivity::class.java).apply {
                        putExtra(LobbyActivity.EXTRA_ROOM_CODE, code)
                        putExtra(LobbyActivity.EXTRA_IS_HOST, false)
                    })
                }.onFailure { Toast.makeText(this@HomeActivity, "Room not found!", Toast.LENGTH_SHORT).show() }
            }.onFailure { Toast.makeText(this@HomeActivity, "Error: ${it.message}", Toast.LENGTH_SHORT).show() }
            binding.btnJoinRoom.isEnabled = true
            binding.btnJoinRoom.text = "JOIN ROOM"
        }
    }
}
