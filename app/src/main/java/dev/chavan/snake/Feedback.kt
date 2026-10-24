package dev.chavan.snake

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.HapticFeedbackConstants
import android.view.View

/** What the player hears and feels. An interface so tests can record events instead of making noise. */
interface Feedback {
    fun eat(score: Int)
    fun crash()
    fun newBest()
    fun release() {}
}

/** Sound through the game audio stream (it follows the media volume) and the phone's vibration motor. */
class PhoneFeedback(private val view: View) : Feedback {
    private val rate = 44_100
    // Built once up front: making an AudioTrack takes milliseconds, playing a ready one does not.
    // One eat blip serves every pitch by playing it faster; phones limit how many tracks an app holds.
    // Either can be null if the phone refuses audio, and then the game simply plays silently.
    private val eat = track(tone(eatPitch(1), eatPitch(1) * 1.5f, 70, rate))
    private val crash = track(tone(260f, 70f, 320, rate, volume = 0.45f))

    override fun eat(score: Int) {
        eat?.let {
            it.playbackRate = (rate * eatPitch(score) / eatPitch(1)).toInt()
            play(it)
        }
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    override fun crash() {
        crash?.let { play(it) }
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    /** A rising four-note fanfare, after the crash thud has died down. */
    override fun newBest() {
        listOf(1, 3, 5, 8).forEachIndexed { i, step ->
            view.postDelayed({ eat(step) }, 380L + i * 110L)
        }
    }

    override fun release() {
        eat?.release()
        crash?.release()
    }

    private fun play(t: AudioTrack) {
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: IllegalStateException) {
            // Audio was taken away mid-game (e.g. by a call); a missed blip is fine.
        }
    }

    private fun track(samples: ShortArray): AudioTrack? = try {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        t.write(samples, 0, samples.size)
        t
    } catch (e: UnsupportedOperationException) {
        null
    } catch (e: IllegalStateException) {
        null
    }
}
