package dev.chavan.snake

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets

class MainActivity : Activity() {
    lateinit var view: SnakeView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("snake", MODE_PRIVATE)
        view = SnakeView(this)
        view.best = prefs.getInt(BEST, 0)
        view.onGameOver = { score ->
            if (score > prefs.getInt(BEST, 0)) prefs.edit().putInt(BEST, score).apply()
        }
        // Android draws apps edge to edge; keep the score and board clear of the system bars and cutout.
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (v as SnakeView).insetTop = bars.top
            v.insetBottom = bars.bottom
            v.relayout()
            insets
        }
        setContentView(view)
    }

    override fun onDestroy() {
        view.feedback.release()
        super.onDestroy()
    }

    override fun onPause() {
        view.pause()
        super.onPause()
    }

    companion object {
        const val BEST = "best"
    }
}
