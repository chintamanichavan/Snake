package dev.chavan.snake

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import java.io.File
import java.io.IOException
import java.time.LocalDate

class MainActivity : Activity() {
    lateinit var view: SnakeView
        private set

    private lateinit var store: GhostStore
    private lateinit var prefs: SharedPreferences

    // The ghost loaded for each mode and day, so a file is read and checked once, not every game.
    private val loaded = HashMap<String, LoadResult>()

    private fun key(daily: Boolean, date: LocalDate) = if (daily) "daily-$date" else "classic"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("snake", MODE_PRIVATE)

        val dir = File(filesDir, "ghosts")
        if (!dir.isDirectory && !dir.mkdirs()) Log.w(TAG, "cannot make $dir; ghosts will not be saved")
        store = GhostStore(dir)

        view = SnakeView(this)
        view.startIn(savedInstanceState?.getBoolean(DAILY) ?: false)
        try {
            store.removeOldDailies(view.today()).forEach { Log.w(TAG, "could not delete old ghost $it") }
        } catch (e: IOException) {
            Log.w(TAG, "could not clear old daily ghosts", e)
        }

        view.onNewGame = { daily, date ->
            val r = loaded.getOrPut(key(daily, date)) { load(daily, date) }
            view.ghost = r.ghost
            view.best = maxOf(bestScore(daily, date), r.ghost?.score ?: 0)
        }
        view.onGameOver = { game -> keep(game) }

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

    private fun load(daily: Boolean, date: LocalDate): LoadResult {
        val started = SystemClock.uptimeMillis()
        val r = store.load(daily, date, SnakeView.COLS, SnakeView.ROWS)
        r.problem?.let { Log.w(TAG, "ghost not used: $it") }
        Log.d(TAG, "loaded ${key(daily, date)} ghost in ${SystemClock.uptimeMillis() - started} ms")
        return r
    }

    /**
     * The best score is kept as its own small record, apart from the ghost file, so a ghost that
     * is lost or set aside never lowers it. Classic: one number (also the pre-ghost record, so an
     * upgrade keeps it). Daily: "date score" for the current day only.
     */
    private fun bestScore(daily: Boolean, date: LocalDate): Int {
        if (!daily) return prefs.getInt(BEST, 0)
        val parts = prefs.getString(DAILY_BEST, "")!!.split(' ')
        return if (parts.size == 2 && parts[0] == date.toString()) parts[1].toIntOrNull() ?: 0 else 0
    }

    private fun recordBest(daily: Boolean, date: LocalDate, score: Int) {
        if (score <= bestScore(daily, date)) return
        val edit = prefs.edit()
        if (daily) edit.putString(DAILY_BEST, "$date $score") else edit.putInt(BEST, score)
        if (!edit.commit()) Log.w(TAG, "could not save the best score")
    }

    /**
     * After a game: record its score if it is the best, and keep it as its mode's ghost if it
     * beats the current one or there is none yet. Returns a note for the game-over card if the
     * ghost could not be kept.
     */
    private fun keep(game: Game): String? {
        // A daily game belongs to the day it was played, from its seed, whatever the clock says now.
        val date = if (game.daily) LocalDate.ofEpochDay(game.seed) else view.today()
        recordBest(game.daily, date, game.score)
        val k = key(game.daily, date)
        var current = loaded.getOrPut(k) { load(game.daily, date) }
        if (current.unreadable) {
            current = load(game.daily, date) // one more try: a read error may have been passing
            loaded[k] = current
        }
        if (current.unreadable) {
            Log.w(TAG, "not saving over an unreadable ghost file: ${current.problem}")
            return "Ghost not saved"
        }
        if (!GhostStore.beats(game.score, current.ghost)) return null
        val record = GhostRecord.of(game)
        val started = SystemClock.uptimeMillis()
        return try {
            store.save(record)
            loaded[k] = LoadResult(record, null)
            Log.d(TAG, "saved ghost (${record.moves.length} moves) in ${SystemClock.uptimeMillis() - started} ms")
            null
        } catch (e: IOException) {
            Log.w(TAG, "could not save the ghost; the game carries on", e)
            "Ghost not saved"
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "game not kept as a ghost: ${e.message}")
            "Too long to keep as a ghost"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(DAILY, view.daily)
    }

    override fun onResume() {
        super.onResume()
        view.refreshStaleDaily() // back after midnight UTC: the start screen moves to today's board
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
        private const val DAILY_BEST = "dailyBest"
        private const val DAILY = "daily"
        private const val TAG = "Snake"
    }
}
