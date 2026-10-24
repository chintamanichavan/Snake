#!/bin/bash
# Claude Code Stop hook: Claude may not finish a turn while the JVM unit tests fail.
# Exit 2 blocks the stop and sends stderr back to Claude; exit 1 lets it stop but
# shows the user an error; exit 0 is a clean stop.
# Gradle skips work when nothing changed, so a stop with no edits costs about a second.

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 1
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
# Overridable so the hook's own tests can stand in a passing or failing command.
cmd="${SNAKE_TEST_CMD:-./gradlew testDebugUnitTest --console=plain}"

# Claude Code sets stop_hook_active when Claude is already continuing because this
# hook blocked it once. Blocking again could loop forever on a test Claude cannot fix.
active=$(jq -r '.stop_hook_active // false' 2>/dev/null)

log=$(mktemp) || exit 1
trap 'rm -f "$log"' EXIT
sh -c "$cmd" >"$log" 2>&1 && exit 0

summary=$(grep -E 'FAILED|Exception|Error|expected|tests completed' "$log" | head -40)
[ -n "$summary" ] || summary=$(tail -40 "$log")

if [ "$active" = "true" ]; then
  printf 'Unit tests still failing after one fix attempt; stopping so you can look.\n%s\n' "$summary" >&2
  exit 1
fi
printf 'Unit tests are failing (%s). Fix them before finishing:\n%s\n' "$cmd" "$summary" >&2
exit 2
