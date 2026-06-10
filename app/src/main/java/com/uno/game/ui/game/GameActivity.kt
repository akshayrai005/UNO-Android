package com.uno.game.ui.game

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.uno.game.R
import com.uno.game.audio.SoundManager
import com.uno.game.audio.VoiceChatManager
import com.uno.game.databinding.ActivityGameBinding
import com.uno.game.databinding.DialogColorPickerBinding
import com.uno.game.databinding.DialogWinnerBinding
import com.uno.game.models.GameState
import com.uno.game.models.UnoCard
import com.uno.game.network.SocketManager
import com.uno.game.utils.PreferencesManager

class GameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameBinding
    private val viewModel: GameViewModel by viewModels()
    private lateinit var handAdapter: CardHandAdapter
    private lateinit var playerListAdapter: PlayerListAdapter

    private var pendingWildCard: UnoCard? = null
    private var lastTopCardId: String? = null
    private var isMuted = false
    private var isMicMuted = false
    private lateinit var voiceChat: VoiceChatManager

    private var autoDrawPending = false
    private var wasMyTurn = false          // track turn transitions for vibrate
    private var isSpectating = false       // true once local player finishes

    companion object {
        const val EXTRA_ROOM_CODE = "room_code"
        private const val TAG = "GameActivity"
        private const val REQUEST_MIC_PERMISSION = 1001
        private const val AUTO_DRAW_DELAY_MS = 800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: run { finish(); return }
        val playerId = PreferencesManager.getPlayerId(this) ?: run { finish(); return }
        val username = PreferencesManager.getUsername(this) ?: ""

        viewModel.roomCode = roomCode
        viewModel.currentPlayerId = playerId
        viewModel.currentUsername = username
        viewModel.initSocket()

        SocketManager.joinRoom(roomCode, playerId, username)

        SoundManager.init(this)
        SoundManager.playShuffle()
        requestMicrophonePermission()
        voiceChat = VoiceChatManager(this, roomCode)
        voiceChat.initialize()
        setupRecyclerViews()
        setupButtons()
        observeGame()

        binding.btnDraw.isEnabled = false
        binding.btnDrawPile.isEnabled = false
        setTurnIndicator(null, false)
    }

    // ── Vibrate ───────────────────────────────────────────────────────────────
    private fun vibrateForMyTurn() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 120), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 120), -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(0, 80, 60, 120), -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate failed: ${e.message}")
        }
    }

    // ── RecyclerViews ─────────────────────────────────────────────────────────
    private fun setupRecyclerViews() {
        handAdapter = CardHandAdapter { card -> onCardClicked(card) }
        binding.rvHand.apply {
            layoutManager = LinearLayoutManager(this@GameActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = handAdapter
            setHasFixedSize(false)
            itemAnimator = null
        }

        playerListAdapter = PlayerListAdapter { player ->
            viewModel.challengeUno(player.id)
            showToast("Challenged ${player.username}!")
        }
        binding.rvPlayers.apply {
            layoutManager = LinearLayoutManager(this@GameActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = playerListAdapter
        }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnDrawPile.setOnClickListener { drawCard() }
        binding.btnDraw.setOnClickListener { drawCard() }

        binding.btnUno.setOnClickListener {
            viewModel.sayUno()
            SoundManager.playUno()
            animatePulseButton(binding.btnUno)
        }

        binding.btnMuteSound.setOnClickListener {
            isMuted = SoundManager.toggleMute()
            binding.btnMuteSound.setImageResource(
                if (isMuted) R.drawable.ic_sound_off else R.drawable.ic_sound_on
            )
        }

        binding.btnMuteMic.setOnClickListener {
            isMicMuted = voiceChat.toggleMute()
            binding.btnMuteMic.setImageResource(
                if (isMicMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on
            )
            binding.btnMuteMic.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor(
                        if (isMicMuted) "#B71C1C" else "#37474F"
                    )
                )
        }
    }

    // ── Draw card ─────────────────────────────────────────────────────────────
    private fun drawCard() {
        if (isSpectating) return
        val state = viewModel.gameState.value
        if (state == null) { showToast(getString(R.string.game_not_loaded)); return }
        if (!viewModel.isMyTurn()) {
            val name = state.players.find { it.id == state.currentPlayerId }?.username ?: "someone"
            showToast(getString(R.string.not_your_turn, name))
            shakeView(binding.btnDraw)
            return
        }
        autoDrawPending = false
        viewModel.drawCard()
        SoundManager.playCardDraw()
        animatePileDrawPulse()
    }

    // ── Card click ────────────────────────────────────────────────────────────
    private fun onCardClicked(card: UnoCard) {
        if (isSpectating) return
        val state = viewModel.gameState.value
        if (state == null) { showToast(getString(R.string.game_not_loaded)); return }
        if (!viewModel.isMyTurn()) {
            val name = state.players.find { it.id == state.currentPlayerId }?.username ?: "someone"
            showToast(getString(R.string.not_your_turn, name))
            return
        }
        if (card.isWild()) {
            pendingWildCard = card
            showColorPicker()
        } else {
            playCardWithAnimation(card.id)
        }
    }

    private fun playCardWithAnimation(cardId: String, chosenColor: String? = null) {
        viewModel.playCard(cardId, chosenColor)
        SoundManager.playCardPlay()
    }

    // ── Color picker ──────────────────────────────────────────────────────────
    private fun showColorPicker() {
        val dialogBinding = DialogColorPickerBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this, R.style.Theme_UNO)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val pick = { color: String ->
            pendingWildCard?.let { card ->
                playCardWithAnimation(card.id, color)
                pendingWildCard = null
            }
            dialog.dismiss()
        }

        dialogBinding.btnColorRed.setOnClickListener    { pick("red") }
        dialogBinding.btnColorGreen.setOnClickListener  { pick("green") }
        dialogBinding.btnColorBlue.setOnClickListener   { pick("blue") }
        dialogBinding.btnColorYellow.setOnClickListener { pick("yellow") }
        dialog.show()
    }

    // ── Winner dialog ─────────────────────────────────────────────────────────
    private fun showWinnerDialog(winnerId: String, state: GameState) {
        SoundManager.playWin()
        val dialogBinding = DialogWinnerBinding.inflate(LayoutInflater.from(this))
        val medals    = listOf("🥇", "🥈", "🥉", "4️⃣", "5️⃣", "6️⃣")
        val loserIcon = "💀"
        val finishedIds  = state.finishOrder.toMutableList()
        val loserIds     = state.players.map { it.id }.filter { it !in finishedIds }
        val allRankedIds = finishedIds + loserIds
        val totalPlayers = allRankedIds.size
        val container    = dialogBinding.layoutRankings

        allRankedIds.forEachIndexed { i, id ->
            val name    = state.players.find { it.id == id }?.username ?: id
            val isLoser = i == totalPlayers - 1
            val icon    = if (isLoser) loserIcon else medals.getOrElse(i) { "▪️" }
            val label   = if (isLoser) "$icon $name  ← LOSER" else "$icon $name"
            val tv = android.widget.TextView(this).apply {
                text      = label
                textSize  = when (i) { 0 -> 20f; else -> 15f }
                setTextColor(when {
                    i == 0  -> android.graphics.Color.parseColor("#FFD700")
                    isLoser -> android.graphics.Color.parseColor("#EF5350")
                    else    -> android.graphics.Color.parseColor("#E0E0E0")
                })
                gravity    = android.view.Gravity.CENTER
                setPadding(0, if (i == 0) 0 else 4, 0, if (i == 0) 8 else 4)
            }
            container.addView(tv)
        }

        val myPosition = allRankedIds.indexOf(viewModel.currentPlayerId)
        val isIWinner  = myPosition == 0
        val isILoser   = myPosition == totalPlayers - 1

        dialogBinding.tvWinnerEmoji.text = when { isIWinner -> "🏆"; isILoser -> "😭"; else -> "🎉" }
        dialogBinding.tvWinnerTitle.text = when { isIWinner -> "YOU WON!"; isILoser -> "YOU LOST 💀"; else -> "GAME OVER" }
        if (isILoser) dialogBinding.tvWinnerTitle.setTextColor(android.graphics.Color.parseColor("#EF5350"))

        val dialog = AlertDialog.Builder(this, R.style.Theme_UNO)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogBinding.btnBackToMenu.setOnClickListener {
            PreferencesManager.clearLastRoom(this)
            dialog.dismiss()
            finish()
        }
        dialog.show()
        dialog.window?.decorView?.startAnimation(
            android.view.animation.AnimationUtils.loadAnimation(this, R.anim.bounce_in)
        )
    }

    // ── Spectator mode ────────────────────────────────────────────────────────
    private fun enterSpectatorMode(myPosition: Int) {
        if (isSpectating) return
        isSpectating = true
        val medals = listOf("🥇","🥈","🥉","4th","5th","6th")
        val medal = medals.getOrElse(myPosition - 1) { "${myPosition}th" }

        // Hide hand + action controls
        binding.rvHand.visibility = View.GONE
        binding.layoutActionBar.visibility = View.GONE

        // Show spectator bar
        binding.layoutSpectator.visibility = View.VISIBLE
        binding.tvSpectatorTitle.text = "👀 SPECTATING — You finished $medal!"
        binding.tvSpectatorSub.text = "Watching the remaining players battle it out..."
        showEventToast("$medal You finished! Now spectating 👀")
    }

    // ── Game state observer ───────────────────────────────────────────────────
    private fun observeGame() {
        viewModel.gameState.observe(this) { state ->
            var myPlayer = state.players.find { it.id == viewModel.currentPlayerId }
            if (myPlayer == null && viewModel.currentUsername.isNotBlank()) {
                myPlayer = state.players.find { it.username == viewModel.currentUsername }
                myPlayer?.let {
                    viewModel.currentPlayerId = it.id
                    PreferencesManager.savePlayer(this, it.id, it.username)
                }
            }

            // ── Check if I finished → enter spectator mode ──
            val myFinishPos = myPlayer?.finishPosition
            if (myFinishPos != null && !isSpectating) {
                enterSpectatorMode(myFinishPos)
            }

            // ── Top card ──
            state.topCard?.let { card ->
                val isNewCard = card.id != lastTopCardId
                binding.cardTop.setCard(card)
                binding.cardTop.visibility = View.VISIBLE
                if (isNewCard) {
                    lastTopCardId = card.id
                    animateDiscardIn()
                    playSoundForCard(card)
                }
            }

            binding.tvCurrentColor.setBackgroundColor(colorForString(state.currentColor))
            binding.tvDeckCountText.text = getString(R.string.deck_label, state.deckCount)
            binding.tvDeckCountPile.text = "${state.deckCount}"
            binding.tvDirection.text = if (state.direction == 1) "↻" else "↺"

            val myHand = myPlayer?.hand ?: emptyList()
            val topCard = state.topCard
            val currentColor = state.currentColor
            val isMyTurn = state.currentPlayerId == viewModel.currentPlayerId

            // ── Vibrate on turn start (only when it newly becomes my turn) ──
            if (isMyTurn && !wasMyTurn && !isSpectating) {
                vibrateForMyTurn()
            }
            wasMyTurn = isMyTurn

            val playableIds = if (isMyTurn && topCard != null && !isSpectating) {
                myHand.filter { card ->
                    isCardPlayable(card, topCard, currentColor, state.pendingDraw, myHand)
                }.map { it.id }.toSet()
            } else {
                emptySet()
            }

            if (!isSpectating) {
                handAdapter.playableCardIds = playableIds
                handAdapter.submitList(myHand.toList())
            }

            // Show ALL players (including me) in top rail when spectating
            val others = if (isSpectating) {
                state.players
            } else {
                state.players.filter { it.id != viewModel.currentPlayerId }
            }
            playerListAdapter.activePlayerId = state.currentPlayerId
            playerListAdapter.submitList(others)

            val currentName = state.players.find { it.id == state.currentPlayerId }?.username
            setTurnIndicator(currentName, isMyTurn && !isSpectating)

            if (!isSpectating) {
                binding.btnDraw.isEnabled = isMyTurn
                binding.btnDrawPile.isEnabled = isMyTurn

                if (state.pendingDraw > 0) {
                    binding.btnDraw.text = getString(R.string.draw_penalty, state.pendingDraw)
                    binding.btnDraw.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#E53935"))
                    binding.tvPendingDraw.text = "⚠️ Must draw ${state.pendingDraw}"
                    binding.tvPendingDraw.visibility = View.VISIBLE

                    if (isMyTurn && !autoDrawPending) {
                        val canStack = myHand.any { c ->
                            (state.topCard?.value == "draw2" && c.value == "draw2") ||
                            (state.topCard?.value == "wild_draw4" && c.value == "wild_draw4")
                        }
                        if (!canStack) {
                            autoDrawPending = true
                            Handler(Looper.getMainLooper()).postDelayed({ drawCard() }, AUTO_DRAW_DELAY_MS)
                        }
                    }
                } else {
                    binding.btnDraw.text = getString(R.string.draw_card)
                    binding.btnDraw.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#1E88E5"))
                    binding.tvPendingDraw.visibility = View.GONE

                    // Auto-draw when no playable card (excluding wilds which are always playable when pendingDraw=0)
                    if (isMyTurn && !autoDrawPending && playableIds.isEmpty() && myHand.isNotEmpty()) {
                        autoDrawPending = true
                        showEventToast("No playable card — drawing...")
                        Handler(Looper.getMainLooper()).postDelayed({ drawCard() }, AUTO_DRAW_DELAY_MS)
                    }
                }

                binding.btnUno.visibility = if (myHand.size == 1) View.VISIBLE else View.GONE
            }

            state.lastEvent?.let { event ->
                when {
                    event.unoAlert == true              -> showEventToast("UNO! 🃏")
                    event.card?.value == "skip"         -> showEventToast("⊘ SKIP!")
                    event.card?.value == "reverse"      -> showEventToast("⇄ REVERSE!")
                    event.card?.value == "draw2"        -> showEventToast("+2 Draw!")
                    event.card?.value == "wild_draw4"   -> showEventToast("+4 Draw!")
                }
            }
        }

        viewModel.cardAutoPlayedEvent.observe(this) { (playerId, cardValue) ->
            val name = if (playerId == viewModel.currentPlayerId) "You"
                       else viewModel.gameState.value?.players?.find { it.id == playerId }?.username ?: "Someone"
            showEventToast("🎴 $name drew & played $cardValue!")
            SoundManager.playCardPlay()
        }

        viewModel.unoEvent.observe(this) { playerId ->
            val name = viewModel.gameState.value?.players?.find { it.id == playerId }?.username ?: "Someone"
            showEventToast("$name: UNO! 🃏")
            SoundManager.playUno()
        }

        viewModel.winnerEvent.observe(this) { winnerId ->
            viewModel.gameState.value?.let { state -> showWinnerDialog(winnerId, state) }
        }

        viewModel.playerFinishedEvent.observe(this) { (playerId, position) ->
            val medals = listOf("🥇","🥈","🥉","4th","5th","6th")
            val name = viewModel.gameState.value?.players?.find { it.id == playerId }?.username ?: "Someone"
            val medal = medals.getOrElse(position - 1) { "$position" }
            showEventToast("$medal $name finished!")
        }

        viewModel.errorMessage.observe(this) { msg -> showToast(msg) }
    }

    // ── Card playability check ────────────────────────────────────────────────
    private fun isCardPlayable(
        card: UnoCard,
        topCard: UnoCard,
        currentColor: String?,
        pendingDraw: Int,
        myHand: List<UnoCard>
    ): Boolean {
        if (pendingDraw > 0) {
            // Only stacking cards valid
            return (card.value == "draw2" && topCard.value == "draw2") ||
                   (card.value == "wild_draw4" && topCard.value == "wild_draw4")
        }
        // Wild +4: only if no matching color card in hand
        if (card.value == "wild_draw4") {
            return myHand.none { c -> c.id != card.id && c.color == currentColor }
        }
        if (card.isWild()) return true
        val matchesColor = card.color == (currentColor ?: topCard.color)
        val matchesValue = card.value == topCard.value
        return matchesColor || matchesValue
    }

    private fun setTurnIndicator(currentName: String?, isMyTurn: Boolean) {
        when {
            currentName == null -> {
                binding.tvTurnIndicator.text = "⏳ Loading…"
                binding.tvTurnIndicator.setTextColor(Color.parseColor("#80FFFFFF"))
                binding.tvTurnIndicator.setBackgroundResource(R.drawable.bg_turn_indicator_other)
            }
            isMyTurn -> {
                binding.tvTurnIndicator.text = "⭐ YOUR TURN!"
                binding.tvTurnIndicator.setTextColor(Color.parseColor("#FFD700"))
                binding.tvTurnIndicator.setBackgroundResource(R.drawable.bg_turn_indicator)
                animateScalePop(binding.tvTurnIndicator)
            }
            isSpectating -> {
                binding.tvTurnIndicator.text = "👀 ${currentName}'s turn"
                binding.tvTurnIndicator.setTextColor(Color.parseColor("#AAAAFF"))
                binding.tvTurnIndicator.setBackgroundResource(R.drawable.bg_turn_indicator_other)
            }
            else -> {
                binding.tvTurnIndicator.text = "🎮 ${currentName}'s turn"
                binding.tvTurnIndicator.setTextColor(Color.parseColor("#B0C4DE"))
                binding.tvTurnIndicator.setBackgroundResource(R.drawable.bg_turn_indicator_other)
            }
        }
    }

    private fun showEventToast(message: String) {
        binding.tvEventToast.apply {
            text = message
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(200).withEndAction {
                postDelayed({
                    animate().alpha(0f).setDuration(300).withEndAction {
                        visibility = View.GONE
                    }.start()
                }, 1200)
            }.start()
        }
    }

    private fun playSoundForCard(card: UnoCard) {
        when (card.value) {
            "skip"       -> SoundManager.playSkip()
            "reverse"    -> SoundManager.playReverse()
            "wild_draw4" -> SoundManager.playDraw4()
            else         -> SoundManager.playCardPlay()
        }
    }

    private fun animateDiscardIn() {
        binding.cardTop.apply {
            scaleX = 0.7f; scaleY = 0.7f; alpha = 0.4f; rotation = (-15..15).random().toFloat()
            animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f)
                .setDuration(280).setInterpolator(OvershootInterpolator(1.8f)).start()
        }
    }

    private fun animatePileDrawPulse() = animateScalePop(binding.btnDrawPile)

    private fun animatePulseButton(view: View) {
        view.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120)
            .withEndAction { view.animate().scaleX(1f).scaleY(1f).duration = 120 }.start()
    }

    private fun animateScalePop(view: View) {
        val sx = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.05f, 1f)
        val sy = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.05f, 1f)
        AnimatorSet().apply { playTogether(sx, sy); duration = 200; start() }
    }

    private fun shakeView(view: View) {
        ObjectAnimator.ofFloat(view, "translationX",
            0f, -14f, 14f, -10f, 10f, -5f, 5f, 0f).apply { duration = 350; start() }
    }

    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun colorForString(color: String?): Int = when (color) {
        "red"    -> Color.parseColor("#E53935")
        "green"  -> Color.parseColor("#43A047")
        "blue"   -> Color.parseColor("#1E88E5")
        "yellow" -> Color.parseColor("#FDD835")
        else     -> Color.GRAY
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        SoundManager.release()
        if (::voiceChat.isInitialized) voiceChat.release()
    }
}
