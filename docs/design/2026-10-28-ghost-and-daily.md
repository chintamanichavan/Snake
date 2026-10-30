# Ghost race and Daily challenge

## Goal
1. **Ghost race.** While you play, a faint copy of your best game moves on the board, so you can
   see whether you are ahead of it.
2. **Daily challenge.** A second mode where every player gets the same board on the same day: same
   size, same start, and the same k-th apple whenever its square is free.

Done when: a property test drives the real controls (`aim`, `turn`, `retake`, `step`) with random
input, replays the recording, and matches snake, food and score at every step; two daily games on
the same date with different moves get the same k-th apple whenever that square is free; on the
emulator, a finished game appears as a ghost in the next one, and the mode switch works.

## Non-goals
- Online leaderboards or sharing scores. Nothing leaves the phone, and no network permission.
- Ghosts from other players, or picking which past game to race. It is always your best.
- Limiting daily attempts. Unlimited tries; your daily score is your best of the day.

## Current state
- `Game.kt`: the rules. It uses unseeded `Random.Default` (`SnakeView.kt:151`), and "play again"
  calls `reset()`, which keeps the same generator. So no game can be rebuilt today.
- Food is "the n-th free cell", so it depends on where the snake is.
- Before the first step, `aim()` may reverse the snake end to end. `retake()` undoes a step and
  calls `step()` again.
- The board is 16 columns, with as many rows as fit the window (`layoutBoard`). Rows change with
  navigation mode, split screen and display size.
- The best score is one int in SharedPreferences (`MainActivity.kt`).

## Choice (options considered are in the Review section and below)
- **Random numbers:** a small SplitMix64 generator in the app (about 10 lines), pinned by a test of
  known outputs. Kotlin's seeded `Random` is only promised within one Kotlin version, and daily
  food has to match across app builds. Every game gets its own seed at `reset(seed)`, before the
  first apple is drawn.
- **Recording:** the seed, plus one letter per step (`RRRUULL...`). A retake overwrites the last
  letter. A replay calls `aim()` for the first step, so a reversed start replays correctly, and
  forces the recorded direction after that.
- **Board:** a fixed 16 x 24 for both modes, scaled to fit with `cell = min(w/16, h/24)`. One code
  path, and a ghost never fails to load because the window changed. Classic loses its extra rows
  on tall phones; that is accepted.
- **Daily food:** apple k is drawn from all 384 squares using a stream seeded by (date, k). If the
  square is covered, it tries again up to 8 times, then takes the next free square in scan order.
  That is deterministic and always ends. The date is UTC, fixed when the game starts: the board
  changes at 7 pm Central / 05:30 India time.
- **Ghost timing:** lockstep, one ghost step per real step. A retake does not move the ghost.
  Real-time racing could come later without changing the recording.
- **Ghost drawing:** an outline at about 30% opacity, with no eyes, effects or food, and no
  collision with you. When its game ends it stays where it crashed, faded further. When yours
  ends, it stops.
- **Storage:** one text file per ghost: a header with a rules version, board size, seed, final
  score and step count, then the letters. Saved by writing a temporary file, syncing it, then
  renaming it over the old one (plain java.nio, not `AtomicFile`, so the failure paths run in JVM
  tests), on the main thread at game over.
  - **Measured:** the longest possible game (a bot that fills the board) is 35,822 moves, a
    36 KB file, and verifying it takes 6 ms on the Mac. The cap is 100,000 moves; files over
    101,000 bytes are refused unread. The ghost file is
  the single source of the best score.
  - **Upgrade:** BEST shows the larger of the old saved number and the ghost. The first finished
    game saves a ghost even if it doesn't beat the old number.
  - **Daily:** `daily-YYYY-MM-DD.ghost`. On launch, every daily file except today's is deleted.
- **Loud failures:** a ghost whose replay doesn't reach its stored score and step count, or has the
  wrong version or a bad format, is dropped with a logged reason. A failed save is logged, and the
  game carries on.

## Risks
- **Replay drift.** Caught by the property test and by the score/step check on load.
- **A rules change in a future version.** The rules version changes, so old ghosts are dropped,
  with a log line.
- **A wrong clock.** A game uses the date it started with. File clean-up keeps only today's file,
  whatever the clock says.
- **Tiny windows.** The cell has a minimum of 8 dp; below that, the board shows clipped rather than
  unreadable. Tested.
- **Frame rate.** Today: about 60 fps, 1 slow frame in 231 (0.4%). Budget with the ghost: at most
  1% slow frames.

## Test plan
- **Unit tests:**
  - PRNG known outputs.
  - Replay property test over hundreds of random games, including a reversed start and a retake
    that eats.
  - A retake doesn't advance the ghost.
  - Daily food: same date gives the same k-th apple; different dates differ; a covered square
    falls back; a nearly full board ends.
  - The ghost file round-trips; bad files are rejected for each reason; a failed write is logged.
  - Upgrade from the old saved best.
  - Daily clean-up with an injected clock.
  - The new board fit.
- **UI tests (emulator):** the ghost is drawn where its game says; the ghost's score is in the
  score bar; the mode switch; finish a game, restart, and the ghost appears.
- **Measure:** frame times with the ghost against the budget, and the save time.

## Steps (each built, tested and committed)
1. SplitMix64 generator plus a per-game seed.
2. Recording and replay, with the property test.
3. Fixed 16 x 24 board.
4. Daily food and the date seed.
5. Ghost file: format, `AtomicFile` save, validated load, upgrade.
6. Draw the ghost in lockstep, plus its score in the score bar.
7. Classic/Daily switch on the start screen; wire up the activity; end-to-end test; measure.
8. Merge into `main`.

## Review (design-reviewer, 14 findings)
- **Acted on:**
  - 1 no seed today: per-game seed at `reset`.
  - 2 Kotlin `Random` not stable: own PRNG.
  - 3 reversed first step: replay through `aim`.
  - 4 `retake` rules: overwrite the last letter, ghost doesn't move, property test uses the real
    controls.
  - 5 no drift check: version, score and step count verified.
  - 6 Classic rows vary: fixed board for both modes.
  - 7 upgrade and two places holding the best: ghost is the source, upgrade rule.
  - 8 unbounded retries: all-cells draw, 8 tries then scan.
  - 9 daily rules: stated above.
  - 10 ghost behaviours: stated above.
  - 11 storage: `AtomicFile`, logged failures, main thread measured.
  - 12 unmeasured "4 inch" claim: removed, with an 8 dp minimum tested.
  - 14 test gaps: added.
- **Rejected:**
  - 6, excluding ghosts from backup: with a fixed board and version check, a restored ghost
    replays correctly, so there's no need.
  - 13, a fixed list instead of per-apple streams: about as simple as the choice, as the reviewer
    says; I kept the per-apple streams because the "k-th apple" rule is easier to state and test.

## Review (failure-reviewer on Ghost.kt, 10 findings)
- **Acted on:**
  - 1 a huge file crashes every launch: size checked before reading.
  - 2 an endless moves line freezes start-up: 100,000-move cap, measured above.
  - 3 a read error loses the real best: `LoadResult.unreadable`, and the caller must not save;
    bad contents are renamed `.bad` instead.
  - 4 a daily game saved after midnight goes under the wrong date: the file date now comes from
    the record's seed.
  - 5 numbers wrap silently: parsed as Int with ranges checked.
  - 6 a huge board: limited to 5..64.
  - 7 temp files left behind: deleted on failure, and swept with old dailies.
  - 8 a failed folder listing looks empty: now an IOException.
  - 10 duplicate or unknown lines: refused.
- **Accepted, not fixed:** 9, the folder is not synced after the rename, so a power cut right
  after a save can bring back the previous ghost. Nothing is corrupted and only the newest best is
  lost; `AtomicFile` has the same limit.

## Review (test-auditor on steps 1-5, 14 findings)
- **Acted on:**
  - 1, 2 rules not pinned: PinnedRulesTest checks food, start and nextInt against an independent
    Python copy, and a hand-worked daily ghost must still load.
  - 3 recording not cleared on reset: tested.
  - 4 daily save across midnight: tested.
  - 5 untested load branches: mode, seed and unreadable are tested.
  - 6 reasons not checked: every rejection checks its reason.
  - 7 extreme seeds: tested.
  - 8 weak property test: a daily run that counts retakes and eating retakes.
  - 9 resize and play-again UI: tested.
  - 10 board edges: tested.
  - 11 clean-up failures: tested.
  - 12 format edge cases: tested.
  - 13 Rng edges: tested.
  - 14 temp folders: now JUnit's `TemporaryFolder`.
- **Noted, not a test gap:** Android Studio added the foojay toolchain plugin and a JDK 25
  download setting to the project. They are left uncommitted and need the user's decision (and
  dependency-vetter) before being kept.
