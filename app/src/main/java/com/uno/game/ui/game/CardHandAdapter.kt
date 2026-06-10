package com.uno.game.ui.game

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.uno.game.R
import com.uno.game.models.UnoCard

class CardHandAdapter(
    private val onCardClick: (UnoCard) -> Unit
) : ListAdapter<UnoCard, CardHandAdapter.CardViewHolder>(CardDiffCallback()) {

    /** Set of card IDs that are currently playable — drives highlight / dim logic */
    var playableCardIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Currently selected / lifted card id */
    private var selectedCardId: String? = null

    inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardView: UnoCardView = itemView.findViewById(R.id.unoCard)

        fun bind(card: UnoCard, position: Int) {
            val playable = playableCardIds.isEmpty() || card.id in playableCardIds
            val selected = card.id == selectedCardId

            cardView.setCard(card)
            cardView.isPlayable = playable
            cardView.isCardSelected = selected

            // Apply visual alpha / scale based on playability
            itemView.alpha  = if (playable) 1f else 0.45f
            itemView.scaleX = if (selected) 1.08f else 1f
            itemView.scaleY = if (selected) 1.08f else 1f
            itemView.translationY = if (selected) -14f else 0f

            itemView.setOnClickListener {
                if (!playable) {
                    // Shake to indicate card not playable
                    shakeCard(itemView)
                    return@setOnClickListener
                }
                // Lift animation on tap, then callback
                liftCard(itemView) { onCardClick(card) }
                selectedCardId = if (selected) null else card.id
            }
        }

        private fun liftCard(view: View, afterLift: () -> Unit) {
            val ty = ObjectAnimator.ofFloat(view, "translationY", view.translationY, -48f)
            val sx = ObjectAnimator.ofFloat(view, "scaleX", view.scaleX, 1.12f)
            val sy = ObjectAnimator.ofFloat(view, "scaleY", view.scaleY, 1.12f)
            AnimatorSet().apply {
                playTogether(ty, sx, sy)
                duration = 160
                interpolator = OvershootInterpolator(1.5f)
                start()
            }
            view.postDelayed(afterLift, 180)
        }

        private fun shakeCard(view: View) {
            ObjectAnimator.ofFloat(view, "translationX",
                0f, -12f, 12f, -8f, 8f, -4f, 4f, 0f).apply {
                duration = 350
                start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_card, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }
}

class CardDiffCallback : DiffUtil.ItemCallback<UnoCard>() {
    override fun areItemsTheSame(old: UnoCard, new: UnoCard) = old.id == new.id
    override fun areContentsTheSame(old: UnoCard, new: UnoCard) = old == new
}
