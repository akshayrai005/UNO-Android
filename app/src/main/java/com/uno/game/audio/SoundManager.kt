package com.uno.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.uno.game.R

object SoundManager {
    private var soundPool: SoundPool? = null
    private var soundUno      = 0
    private var soundCardPlay = 0
    private var soundCardDraw = 0
    private var soundWin      = 0
    private var soundReverse  = 0
    private var soundSkip     = 0
    private var soundDraw4    = 0
    private var soundShuffle  = 0
    private var isMuted = false

    fun init(context: Context) {
        if (soundPool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attrs)
            .build()
        soundPool!!.let { sp ->
            soundUno      = sp.load(context, R.raw.sound_uno,       1)
            soundCardPlay = sp.load(context, R.raw.sound_card_play, 1)
            soundCardDraw = sp.load(context, R.raw.sound_card_draw, 1)
            soundWin      = sp.load(context, R.raw.sound_win,       1)
            soundReverse  = sp.load(context, R.raw.sound_reverse,   1)
            soundSkip     = sp.load(context, R.raw.sound_skip,      1)
            soundDraw4    = sp.load(context, R.raw.sound_draw4,     1)
            soundShuffle  = sp.load(context, R.raw.sound_shuffle,   1)
        }
    }

    fun playUno()      { play(soundUno) }
    fun playCardPlay() { play(soundCardPlay) }
    fun playCardDraw() { play(soundCardDraw) }
    fun playWin()      { play(soundWin) }
    fun playReverse()  { play(soundReverse) }
    fun playSkip()     { play(soundSkip) }
    fun playDraw4()    { play(soundDraw4) }
    fun playShuffle()  { play(soundShuffle) }

    private fun play(soundId: Int) {
        if (!isMuted && soundId != 0) soundPool?.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun toggleMute(): Boolean { isMuted = !isMuted; return isMuted }
    fun isMuted() = isMuted

    fun release() {
        soundPool?.release(); soundPool = null
        soundUno=0; soundCardPlay=0; soundCardDraw=0; soundWin=0
        soundReverse=0; soundSkip=0; soundDraw4=0; soundShuffle=0
    }
}
