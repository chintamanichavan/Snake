# Snake

Classic Snake for Android. Kotlin, a plain `View` drawn on a canvas, no UI libraries.

- `Game.kt`: the rules (moves, turns, food, walls, winning). No Android code.
- `Board.kt`: board size on screen and swipe reading. No Android code.
- `SnakeView.kt`: drawing, touch, tick clock. `MainActivity.kt`: window insets, best score.

Swipe to start and steer. Tap to pause. Tap after a crash to play again.

## Build and test

Uses Android Studio's bundled JDK:

    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    ./gradlew testDebugUnitTest              # rules and layout, on the Mac
    ./gradlew connectedDebugAndroidTest      # UI tests, needs a running emulator
    ./gradlew installDebug                   # put it on the emulator

Start the emulator with `~/Library/Android/sdk/emulator/emulator -avd Snake_Pixel`,
or open this folder in Android Studio and press Run.
