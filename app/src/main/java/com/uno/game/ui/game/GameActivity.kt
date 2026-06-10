package com.uno.game.ui.game

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.uno.game.audio.SoundManager
import com.uno.game.audio.VoiceChatManager
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: run { finish(); return }
        val playerId = PreferencesManager.getPlayerId(this) ?: run { finish(); return }

        viewModel.roomCode = roomCode
        viewModel.currentPlayerId = playerId
        viewModel.initSocket()

        setupRecyclerViews()
        setupButtons()
        observeGame()

        SoundManager.init(this)
    }

    private fun setupRecyclerViews() {
        // Hand - horizontal
        handAdapter = CardHandAdapter { card -> onCardClicked(card) }
        binding.rvHand.apply {
            layoutManager = LinearLayoutManager(this@GameActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = handAdapter
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
        // Click draw pile OR draw button both draw
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
        if (!viewModel.isMyTurn()) {
            Toast.makeText(this, "It's not your turn!", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.drawCard()
        SoundManager.playCardDraw()
    }

    private fun onCardClicked(card: UnoCard) {
        if (!viewModel.isMyTurn()) {
            Toast.makeText(this, "It's not your turn!", Toast.LENGTH_SHORT).show()
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
            // Top card
            state.topCard?.let { card ->
                binding.cardTop.setCard(card)
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

            // Deck count
            binding.tvDeckCountText.text = "Deck: ${state.deckCount}"

            // Direction
            binding.tvDirection.text = if (state.direction == 1) "→ Clockwise" else "← Counter"

            // My hand - find my player
            val myPlayer = state.players.find { it.id == viewModel.currentPlayerId }
            val myHand = myPlayer?.hand ?: emptyList()
            handAdapter.submitList(myHand)

            // Other players
            val others = state.players.filter { it.id != viewModel.currentPlayerId }
            playerListAdapter.submitList(others)

            // Turn indicator
            val isMyTurn = state.currentPlayerId == viewModel.currentPlayerId
            binding.tvTurnIndicator.text = if (isMyTurn) "⭐ YOUR TURN!" else {
                val currentName = state.players.find { it.id == state.currentPlayerId }?.username ?: "..."
                "$currentName's turn"
            }
            binding.tvTurnIndicator.setBackgroundColor(
                if (isMyTurn) Color.parseColor("#33FFD700") else Color.parseColor("#22FFFFFF")
            )
            binding.tvTurnIndicator.setTextColor(
                if (isMyTurn) Color.parseColor("#FFD700") else Color.WHITE
            )
            binding.btnDraw.isEnabled = isMyTurn

            // Pending draw
            if (state.pendingDraw > 0) {
                binding.btnDraw.text = "Draw ${state.pendingDraw} ⚠️"
                binding.btnDraw.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E53935"))
            } else {
                binding.btnDraw.text = "Draw Card"
                binding.btnDraw.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E88E5"))
            }

            // UNO button
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
