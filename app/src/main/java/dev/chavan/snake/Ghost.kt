package dev.chavan.snake

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * Bump when a rule change (start position, food, walls, scoring) would make old recordings replay
 * differently. Old ghosts are then rejected with a reason instead of replaying wrongly.
 * PinnedRulesTest fails when such a change is made.
 */
const val GHOST_VERSION = 1

/**
 * The longest game kept as a ghost, in letters. At the top speed of 70 ms a step this is almost two
 * hours of play, yet the file stays near 100 KB and checking it on load stays quick. It also stops
 * a damaged file from making the replay run on and on.
 */
const val MAX_MOVES = 100_000

/** Anything bigger is not a ghost this game wrote, so it is rejected before being read. */
const val MAX_FILE_BYTES = MAX_MOVES + 1_000

/** Board sizes a ghost file may claim. Bounded so a damaged file cannot ask for a huge board. */
private val BOARD_SIDES = 5..64

/** A finished game, kept so it can be replayed as a ghost. */
data class GhostRecord(
    val daily: Boolean,
    val cols: Int,
    val rows: Int,
    val seed: Long,
    val score: Int,
    val moves: String,
) {
    /** Steps taken: every letter after the first, which is the starting facing. */
    val steps get() = moves.length - 1

    /** Plain text, one fact per line, readable in any editor. */
    fun encode() = buildString {
        append("snake-ghost $GHOST_VERSION\n")
        append("mode ${if (daily) "daily" else "classic"}\n")
        append("board $cols $rows\n")
        append("seed $seed\n")
        append("score $score\n")
        append("steps $steps\n")
        append("moves $moves\n")
    }

    /** A fresh replay of this game, ready to step. */
    fun replay() = Replay(Game(cols, rows, seed, daily), moves)

    /**
     * Replays the whole game and checks it ends where it ended when it was played. Returns why
     * not, or null if it matches. This catches a recording that the current rules no longer
     * reproduce, which would otherwise replay "successfully" but differently.
     */
    fun verify(): String? {
        val r = try { replay() } catch (e: IllegalArgumentException) { return "unreadable moves: ${e.message}" }
        var taken = 0
        while (r.advance()) taken++
        return when {
            !r.game.over -> "replay did not end in a crash"
            taken != steps -> "replay took $taken steps, recorded $steps"
            r.game.score != score -> "replay scored ${r.game.score}, recorded $score"
            else -> null
        }
    }

    companion object {
        fun of(g: Game) = GhostRecord(g.daily, g.cols, g.rows, g.seed, g.score, g.recording)
    }
}

/** Reads a ghost file's text. Throws IllegalArgumentException saying what is wrong. */
fun decodeGhost(text: String): GhostRecord {
    require(text.length <= MAX_FILE_BYTES) { "file too large: ${text.length} bytes" }
    val lines = text.lines().filter { it.isNotBlank() }
    val magic = lines.firstOrNull() ?: throw IllegalArgumentException("empty file")
    require(magic.startsWith("snake-ghost ")) { "not a ghost file" }
    val version = magic.removePrefix("snake-ghost ").toIntOrNull()
    require(version == GHOST_VERSION) { "version $version, this game reads $GHOST_VERSION" }

    // Every line is known and appears once: a file joined from two saves, or edited by hand,
    // is refused rather than half-read.
    val known = setOf("mode", "board", "seed", "score", "steps", "moves")
    val fields = HashMap<String, String>()
    for (line in lines.drop(1)) {
        val key = line.substringBefore(' ')
        require(key in known) { "unknown line '$key'" }
        require(fields.put(key, line.substringAfter(' ', "")) == null) { "'$key' appears twice" }
    }
    fun field(name: String) = fields[name] ?: throw IllegalArgumentException("missing '$name'")
    fun int(name: String, text: String = field(name)) =
        text.toIntOrNull() ?: throw IllegalArgumentException("'$name' is not a number in range: $text")

    val daily = when (val m = field("mode")) {
        "daily" -> true
        "classic" -> false
        else -> throw IllegalArgumentException("unknown mode: $m")
    }
    val board = field("board").split(' ')
    require(board.size == 2) { "board needs two numbers" }
    val cols = int("board", board[0])
    val rows = int("board", board[1])
    require(cols in BOARD_SIDES && rows in BOARD_SIDES) { "board ${cols}x$rows is outside $BOARD_SIDES" }
    val seed = field("seed").toLongOrNull() ?: throw IllegalArgumentException("'seed' is not a number: ${field("seed")}")
    val score = int("score")
    require(score >= 0) { "score must be 0 or more: $score" }
    val moves = field("moves")
    require(moves.length <= MAX_MOVES) { "moves too long: ${moves.length} letters" }
    require(moves.isNotEmpty() && moves.all { it in "UDLR" }) { "moves contain a letter that is not a direction" }
    val steps = int("steps")
    require(steps == moves.length - 1) { "steps says $steps, moves hold ${moves.length - 1}" }
    return GhostRecord(daily, cols, rows, seed, score, moves)
}

/**
 * What loading found: a ghost; nothing (no file yet); or a problem worth logging. An unreadable
 * file (a read error, not bad contents) may still hold the real best, so the caller must not
 * save over it this session.
 */
data class LoadResult(val ghost: GhostRecord?, val problem: String?, val unreadable: Boolean = false)

/**
 * Ghost files in one folder: best.ghost for classic, daily-YYYY-MM-DD.ghost for each day.
 * Plain java.io, so all of it, failures included, is tested on the JVM.
 */
class GhostStore(private val dir: File) {
    private fun classicFile() = File(dir, "best.ghost")
    private fun dailyFile(date: LocalDate) = File(dir, "daily-$date.ghost")

    fun load(daily: Boolean, date: LocalDate, cols: Int, rows: Int): LoadResult {
        val f = if (daily) dailyFile(date) else classicFile()
        if (!f.exists()) return LoadResult(null, null)
        if (f.isFile && f.length() > MAX_FILE_BYTES) return setAside(f, "file too large: ${f.length()} bytes")
        val text = try {
            f.readText()
        } catch (e: IOException) {
            return LoadResult(null, "${f.name}: could not read: ${e.message}", unreadable = true)
        }
        val r = try {
            decodeGhost(text)
        } catch (e: IllegalArgumentException) {
            return setAside(f, e.message!!)
        }
        val problem = when {
            r.daily != daily -> "mode does not match the file name"
            r.cols != cols || r.rows != rows -> "board ${r.cols}x${r.rows}, this game is ${cols}x$rows"
            daily && r.seed != dailySeed(date) -> "seed is not $date's board"
            else -> r.verify()
        }
        return if (problem == null) LoadResult(r, null) else setAside(f, problem)
    }

    /**
     * A file with bad contents is renamed to .bad, kept for a look later, so the next finished
     * game can take its place. If even the rename fails, it is reported as unreadable so nothing
     * overwrites it.
     */
    private fun setAside(f: File, problem: String): LoadResult {
        val moved = f.renameTo(File(dir, f.name + ".bad"))
        return LoadResult(null, "${f.name}: $problem" + if (moved) " (set aside as .bad)" else " (could not set aside)", unreadable = !moved)
    }

    /**
     * Writes a temporary file, forces it to disk, then renames it over the old one, so a crash or
     * full disk mid-save leaves the previous ghost whole. A daily ghost is filed under the day it
     * was played, from its seed, whatever the clock says at the end. Throws IOException if the
     * save fails, and IllegalArgumentException for a game too long to keep.
     */
    fun save(r: GhostRecord) {
        require(r.moves.length <= MAX_MOVES) { "game too long to keep as a ghost: ${r.moves.length} moves" }
        val target = if (r.daily) dailyFile(LocalDate.ofEpochDay(r.seed)) else classicFile()
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(r.encode().toByteArray())
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            if (tmp.isFile) tmp.delete()
            throw e
        }
    }

    /**
     * Deletes every daily file (ghosts, leftovers from failed saves, set-aside ones) except
     * today's ghost, whichever way the clock has moved. Returns the names it could not delete,
     * for the caller to log. Throws IOException if the folder cannot be listed at all.
     */
    fun removeOldDailies(today: LocalDate): List<String> {
        val keep = dailyFile(today).name
        val all = dir.listFiles() ?: throw IOException("cannot list $dir")
        return all.filter { it.name.startsWith("daily-") && it.name != keep }
            .filterNot { it.delete() }
            .map { it.name }
    }

    companion object {
        /** A finished game becomes the ghost if there is none yet or it scored higher. */
        fun beats(score: Int, ghost: GhostRecord?) = ghost == null || score > ghost.score
    }
}
