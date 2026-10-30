package dev.chavan.snake

/**
 * Plays a recorded game back one step at a time. The game passed in must be fresh, built with the
 * recording's board size and seed.
 */
class Replay(val game: Game, private val moves: String) {
    private var next = 1

    init {
        require(moves.isNotEmpty()) { "empty recording" }
        moves.forEach { Dir.of(it) } // reject a damaged recording now, not halfway through a game
        require(moves[0] == 'R' || moves[0] == 'L') { "a game starts facing R, or L if reversed, not '${moves[0]}'" }
        require(game.fresh) { "replay needs a game that has not moved yet" }
        game.face(Dir.of(moves[0]))
    }

    /** True once the recorded game has crashed or its moves have run out. */
    val done get() = game.over || next >= moves.length

    /** Takes the next recorded step. Returns false, doing nothing, when the replay is done. */
    fun advance(): Boolean {
        if (done) return false
        game.force(Dir.of(moves[next++]))
        return true
    }
}
