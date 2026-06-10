package com.uno.game.ui.game

import android.animation.ObjectAnimator
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.uno.game.R
import com.uno.game.models.Player

class PlayerListAdapter(
    private val onChallengeClick: (Player) -> Unit
) : ListAdapter<Player, PlayerListAdapter.PlayerViewHolder>(PlayerDiffCallback()) {

    var activePlayerId: String? = null
        set(value) { field = value; notifyDataSetChanged() }

    // Medal emojis for finish positions
    private val medals = listOf("🥇", "🥈", "🥉", "4️⃣", "5️⃣", "6️⃣")

    inner class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName:    TextView = itemView.findViewById(R.id.tvPlayerName)
        val tvCards:   TextView = itemView.findViewById(R.id.tvCardCount)
        val tvUno:     TextView = itemView.findViewById(R.id.tvUnoAlert)
        val tvMedal:   TextView = itemView.findViewById(R.id.tvMedal)
        val vAvatar:   View     = itemView.findViewById(R.id.vAvatar)
        val tvInitial: TextView = itemView.findViewById(R.id.tvAvatarInitial)
        val vActive:   View     = itemView.findViewById(R.id.vActiveIndicator)

        private var pulseAnim: ObjectAnimator? = null

        fun bind(player: Player) {
            tvName.text = player.username.take(10)

            // Avatar
            try { vAvatar.setBackgroundColor(Color.parseColor(player.avatarColor)) }
            catch (_: Exception) { vAvatar.setBackgroundColor(0xFFE53935.toInt()) }
            tvInitial.text = player.username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

            val finished = player.finishPosition != null

            if (finished) {
                // Show medal instead of card count
                val pos = player.finishPosition!! - 1
                val medal = medals.getOrElse(pos) { "🏅" }
                tvMedal.text = medal
                tvMedal.visibility = View.VISIBLE
                tvCards.text = "✓ Done"
                tvCards.setTextColor(Color.parseColor("#88FFFFFF"))
                stopPulse(tvCards)
                tvUno.visibility = View.GONE
                // Dim finished players slightly
                itemView.alpha = 0.65f
                vActive.visibility = View.INVISIBLE
                itemView.setBackgroundResource(R.drawable.bg_player_item)
            } else {
                tvMedal.visibility = View.GONE
                itemView.alpha = if (player.isConnected) 1f else 0.4f

                // Card count — show "X 🃏" format
                tvCards.text = "${player.cardCount} 🃏"

                // Active turn indicator
                val isActive = player.id == activePlayerId
                vActive.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
                itemView.setBackgroundResource(
                    if (isActive) R.drawable.bg_player_item_active
                    else R.drawable.bg_player_item
                )

                // UNO badge
                tvUno.visibility = if (player.saidUno) View.VISIBLE else View.GONE

                // Danger highlight — 1 card, hasn't said UNO
                if (player.cardCount == 1 && !player.saidUno) {
                    tvCards.setTextColor(0xFFFF1744.toInt())
                    startPulse(tvCards)
                } else {
                    tvCards.setTextColor(Color.WHITE)
                    stopPulse(tvCards)
                }
            }

            // Long-press to challenge
            itemView.setOnLongClickListener { onChallengeClick(player); true }
        }

        private fun startPulse(view: View) {
            pulseAnim?.cancel()
            pulseAnim = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.3f, 1f).apply {
                duration = 700
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }

        private fun stopPulse(view: View) {
            pulseAnim?.cancel()
            pulseAnim = null
            view.alpha = 1f
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_player, parent, false)
        return PlayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PlayerViewHolder) {
        super.onViewRecycled(holder)
        holder.tvCards.alpha = 1f
    }
}

class PlayerDiffCallback : DiffUtil.ItemCallback<Player>() {
    override fun areItemsTheSame(old: Player, new: Player) = old.id == new.id
    override fun areContentsTheSame(old: Player, new: Player) = old == new
}
