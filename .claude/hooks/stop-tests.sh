#!/bin/bash
# Claude Code Stop hook: Claude may not finish a turn while the JVM unit tests fail.
# Exit 2 blocks the stop and sends stderr back to Claude; exit 1 lets it stop but
# shows the user an error; exit 0 is a clean stop.
# It blocks only on evidence that the code is broken (failing tests, compile errors).
# Anything else, such as a missing JDK or SDK on a fresh clone, is the user's to fix.
# Gradle skips work when nothing changed, so a stop with no edits costs about a second.

# The script's own location, not CLAUDE_PROJECT_DIR, which may point elsewhere.
cd "$(dirname "$0")/../.." || exit 1

cmd="./gradlew testDebugUnitTest --console=plain"
# The hook's own tests swap in a command. Ignored unless asked for, so a stray
# exported variable cannot quietly turn the gate off.
[ "$SNAKE_HOOK_TEST" = 1 ] && cmd="${SNAKE_TEST_CMD:-$cmd}"

jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
[ -z "$JAVA_HOME" ] && [ -d "$jbr" ] && export JAVA_HOME="$jbr"

# stop_hook_active is true when Claude is already continuing because this hook blocked
# it once; blocking again could loop forever. Only an explicit false allows a block:
# unreadable input means one missed block, never an endless loop.
if grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*false'; then
  active=false
else
  active=true
fi

log=$(mktemp) || { echo "stop hook: cannot create a temp log; tests not run" >&2; exit 1; }
trap 'rm -f "$log"' EXIT
sh -c "$cmd" >"$log" 2>&1 && exit 0

# Evidence the code is broken: a JUnit result line, Gradle's count, a Kotlin error.
broken='^[A-Za-z0-9_.$]+ > .+ FAILED$|tests completed, [0-9]+ failed|^e: '
if ! grep -Eq "$broken" "$log"; then
  printf 'stop hook: could not run the tests (%s). This is not a test failure:\n' "$cmd" >&2
  tail -20 "$log" >&2
  exit 1
fi
summary=$(grep -E "$broken" "$log" | head -40)

if [ "$active" = "true" ]; then
  printf 'Unit tests still failing after one fix attempt; stopping so you can look.\n%s\n' "$summary" >&2
  exit 1
fi
printf 'Unit tests are failing (%s). Fix them before finishing:\n%s\n' "$cmd" "$summary" >&2
exit 2
