# Snake

Classic Snake for Android in Kotlin: a plain `View` on a canvas, no UI libraries. Public on
GitHub. Rules and layout (`Game.kt`, `Board.kt`) have no Android code and are unit tested.

## Check
`./check` runs the JVM unit tests and the Stop hook's own tests. About 3 s warm. Exit 0 green,
1 a test failed (JUnit failure or Kotlin compile error), 2 could not run (no JDK or SDK).
Commits run it through the pre-commit hook. `tests/test_check.sh` tests it (~40 s).
Not covered: UI tests, `./gradlew connectedDebugAndroidTest`, which need the emulator running:
`~/Library/Android/sdk/emulator/emulator -avd Snake_Pixel`.

## Hooks
`.claude/settings.json` adds a Stop hook: a turn cannot end while the unit tests fail
(`.claude/hooks/stop-tests.sh`, tested by `test_stop_tests.sh`; `--gradle` for real runs).

## Gotchas
- JDK: Android Studio's bundled one. `./check` and the Stop hook set `JAVA_HOME` themselves; in a
  shell, `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- `local.properties` (git-ignored) names the SDK; without it Gradle cannot build.
- The emulator needs keyboard and two-finger swipes turned on in its `config.ini` (README).
- Design notes go in `docs/design/`.
