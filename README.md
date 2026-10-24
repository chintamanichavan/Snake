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

## Playing on the emulator

Swipe with two fingers on the trackpad, click and drag, or use the arrow keys (space pauses).

An emulator made from the terminal with `avdmanager` has no keyboard and drops sideways
trackpad swipes. Turn both on in `~/.android/avd/Snake_Pixel.avd/config.ini`, then restart it:

    hw.keyboard=yes
    hw.touchpad0=yes

Checked with `adb shell getevent -lt`: before these settings, left and right two-finger swipes
sent no input at all and arrow keys never arrived.
