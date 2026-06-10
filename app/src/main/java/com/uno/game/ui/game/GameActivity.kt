package com.uno.game.ui.game

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.uno.game.audio.SoundManager
import com.uno.game.databinding.ActivityGameBinding
import com.uno.game.models.UnoCard
import com.uno.game.utils.PreferencesManager

class GameActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGameBinding
    private val viewModel: GameViewModel by viewModels()
    private lateinit var handAdapter: CardHandAdapter
    private lateinit var playerListAdapter: PlayerListAdapter
    private var pendingWildCard: UnoCard? = null

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
        private const val TAG = "GameActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: run { finish(); return }
        val playerId = PreferencesManager.getPlayerId(this) ?: run { finish(); return }
        val username = PreferencesManager.getUsername(this) ?: ""

        Log.d(TAG, "GameActivity started — playerId=$playerId, username=$username, room=$roomCode")

        viewModel.roomCode = roomCode
        viewModel.currentPlayerId = playerId
        viewModel.currentUsername = username
        viewModel.initSocket()

        setupRecyclerViews()
        setupButtons()
        observeGame()

        SoundManager.init(this)

        // Show waiting state initially
        binding.tvTurnIndicator.text = "⏳ Loading game..."
        binding.tvTurnIndicator.setBackgroundColor(Color.parseColor("#22FFFFFF"))
        binding.tvTurnIndicator.setTextColor(Color.WHITE)
    }

    private fun setupRecyclerViews() {
        // Hand - horizontal scrollable
        handAdapter = CardHandAdapter { card -> onCardClicked(card) }
        binding.rvHand.apply {
            layoutManager = LinearLayoutManager(this@GameActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = handAdapter
            setHasFixedSize(false)
        }

        // Other players - horizontal at top
        playerListAdapter = PlayerListAdapter { player ->
            viewModel.challengeUno(player.id)
            Toast.makeText(this, "Challenged ${player.username}!", Toast.LENGTH_SHORT).show()
        }
        binding.rvPlayers.apply {
            layoutManager = LinearLayoutManager(this@GameActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = playerListAdapter
        }
    }

    private fun setupButtons() {
        binding.btnDrawPile.setOnClickListener { drawCard() }
        binding.btnDraw.setOnClickListener { drawCard() }

        binding.btnUno.setOnClickListener {
            viewModel.sayUno()
            SoundManager.playUno()
            binding.btnUno.animate().scaleX(1.3f).scaleY(1.3f).setDuration(150)
                .withEndAction { binding.btnUno.animate().scaleX(1f).scaleY(1f).duration = 150 }
        }

        binding.btnMuteSound.setOnClickListener {
            val muted = SoundManager.toggleMute()
            binding.btnMuteSound.alpha = if (muted) 0.5f else 1.0f
        }
    }

    private fun drawCard() {
        val state = viewModel.gameState.value
        if (state == null) {
            Toast.makeText(this, "Game not loaded yet...", Toast.LENGTH_SHORT).show()
            return
        }
        if (!viewModel.isMyTurn()) {
            val currentName = state.players.find { it.id == state.currentPlayerId }?.username ?: "someone"
            Toast.makeText(this, "Wait for $currentName's turn!", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.drawCard()
        SoundManager.playCardDraw()
    }

    private fun onCardClicked(card: UnoCard) {
        val state = viewModel.gameState.value
        if (state == null) {
            Toast.makeText(this, "Game not loaded yet...", Toast.LENGTH_SHORT).show()
            return
        }
        if (!viewModel.isMyTurn()) {
            val currentName = state.players.find { it.id == state.currentPlayerId }?.username ?: "someone"
            Toast.makeText(this, "Wait for $currentName's turn!", Toast.LENGTH_SHORT).show()
            return
        }
        if (card.isWild()) {
            pendingWildCard = card
            showColorPicker()
        } else {
            viewModel.playCard(card.id)
            SoundManager.playCardPlay()
        }
    }

    private fun showColorPicker() {
        val colors = arrayOf("🔴 Red", "🟢 Green", "🔵 Blue", "🟡 Yellow")
        val colorValues = arrayOf("red", "green", "blue", "yellow")
        AlertDialog.Builder(this)
            .setTitle("Choose Color")
            .setItems(colors) { _, which ->
                pendingWildCard?.let { card ->
                    viewModel.playCard(card.id, colorValues[which])
                    SoundManager.playCardPlay()
                    pendingWildCard = null
                }
            }
            .setCancelable(true)
            .show()
    }

    private fun observeGame() {
        viewModel.gameState.observe(this) { state ->
            Log.d(TAG, "gameState update — currentPlayerId=${state.currentPlayerId}, myId=${viewModel.currentPlayerId}, players=${state.players.size}")

            // Top card
            state.topCard?.let { card ->
                binding.cardTop.setCard(card)
                binding.cardTop.visibility = View.VISIBLE
            }

            // Current color indicator
            binding.tvCurrentColor.setBackgroundColor(
                when (state.currentColor) {
                    "red"    -> Color.parseColor("#E53935")
                    "green"  -> Color.parseColor("#43A047")
                    "blue"   -> Color.parseColor("#1E88E5")
                    "yellow" -> Color.parseColor("#FDD835")
                    else     -> Color.GRAY
                }
            )

            // Deck count & direction
            binding.tvDeckCountText.text = "Deck: ${state.deckCount}"
            binding.tvDirection.text = if (state.direction == 1) "→ Clockwise" else "← Counter"

            // ---- MY HAND ----
            // Try matching by ID first, fallback to username
            var myPlayer = state.players.find { it.id == viewModel.currentPlayerId }
            if (myPlayer == null && viewModel.currentUsername.isNotBlank()) {
                myPlayer = state.players.find { it.username == viewModel.currentUsername }
                // If found by username, update our playerId to match the server's
                myPlayer?.let {
                    Log.w(TAG, "ID mismatch — matched by username. Updating playerId to ${it.id}")
                    viewModel.currentPlayerId = it.id
                    PreferencesManager.savePlayer(this, it.id, it.username)
                }
            }

            val myHand = myPlayer?.hand ?: emptyList()
            Log.d(TAG, "My hand size: ${myHand.size} (myPlayer=${myPlayer?.username ?: "NOT FOUND"})")
            handAdapter.submitList(myHand.toList()) // force new list reference

            // ---- OTHER PLAYERS ----
            val others = state.players.filter { it.id != viewModel.currentPlayerId }
            playerListAdapter.submitList(others)

            // ---- TURN INDICATOR ----
            val isMyTurn = state.currentPlayerId == viewModel.currentPlayerId
            if (state.currentPlayerId.isNullOrBlank()) {
                binding.tvTurnIndicator.text = "⏳ Loading..."
            } else if (isMyTurn) {
                binding.tvTurnIndicator.text = "⭐ YOUR TURN!"
            } else {
                val currentName = state.players.find { it.id == state.currentPlayerId }?.username ?: "..."
                binding.tvTurnIndicator.text = "🎮 $currentName's turn"
            }
            binding.tvTurnIndicator.setBackgroundColor(
                if (isMyTurn) Color.parseColor("#33FFD700") else Color.parseColor("#22FFFFFF")
            )
            binding.tvTurnIndicator.setTextColor(
                if (isMyTurn) Color.parseColor("#FFD700") else Color.WHITE
            )

            // Only enable draw if it's my turn
            binding.btnDraw.isEnabled = isMyTurn
            binding.btnDrawPile.isEnabled = isMyTurn

            // Pending draw warning
            if (state.pendingDraw > 0) {
                binding.btnDraw.text = "Draw ${state.pendingDraw} ⚠️"
                binding.btnDraw.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E53935"))
            } else {
                binding.btnDraw.text = "Draw Card"
                binding.btnDraw.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E88E5"))
            }

            // UNO button — only show when I have exactly 1 card
            binding.btnUno.visibility = if (myHand.size == 1) View.VISIBLE else View.GONE
        }

        viewModel.unoEvent.observe(this) { playerId ->
            val name = viewModel.gameState.value?.players?.find { it.id == playerId }?.username ?: "Someone"
            Toast.makeText(this, "🃏 $name said UNO!", Toast.LENGTH_SHORT).show()
            SoundManager.playUno()
        }

        viewModel.winnerEvent.observe(this) { winnerId ->
            val name = viewModel.gameState.value?.players?.find { it.id == winnerId }?.username ?: "Unknown"
            SoundManager.playWin()
            AlertDialog.Builder(this)
                .setTitle("🎉 Game Over!")
                .setMessage("$name wins! 🏆")
                .setPositiveButton("Back to Menu") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }

        viewModel.errorMessage.observe(this) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SoundManager.release()
    }
}
