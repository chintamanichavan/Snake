#!/bin/bash
# Tests for stop-tests.sh. Fast cases swap in `true`/`false` for Gradle.
# With --gradle, also adds a failing JUnit test to the real project and checks the
# hook blocks, then removes it.
cd "$(dirname "$0")/../.." || exit 1
hook=.claude/hooks/stop-tests.sh
fail=0

# check NAME EXPECTED_EXIT STDIN TEST_CMD [STDERR_MUST_CONTAIN]
check() {
  err=$(printf '%s' "$3" | SNAKE_TEST_CMD="$4" "$hook" 2>&1 >/dev/null)
  code=$?
  if [ "$code" != "$2" ]; then echo "FAIL $1: exit $code, want $2"; fail=1; return; fi
  if [ -n "$5" ] && ! printf '%s' "$err" | grep -q "$5"; then
    echo "FAIL $1: stderr lacks '$5': $err"; fail=1; return
  fi
  echo "ok   $1"
}

first='{"hook_event_name":"Stop","stop_hook_active":false}'
again='{"hook_event_name":"Stop","stop_hook_active":true}'

check "passing tests let Claude stop"            0 "$first" true
check "failing tests block the stop"             2 "$first" "echo 'GameTest > wall FAILED'; false" "wall FAILED"
check "block message names the command"          2 "$first" false "Fix them before finishing"
check "second failure stops instead of looping"  1 "$again" false "still failing"
check "passing on the retry is a clean stop"     0 "$again" true
check "unreadable input still runs the tests"    2 "not json" false
check "empty input still runs the tests"         2 "" false
check "no FAILED lines falls back to log tail"   2 "$first" "echo something odd; false" "something odd"

if [ "$1" = "--gradle" ]; then
  t=app/src/test/java/dev/chavan/snake/StopHookProbeTest.kt
  trap 'rm -f "$t"' EXIT
  printf 'package dev.chavan.snake\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\nclass StopHookProbeTest { @Test fun probe() = assertEquals(1, 2) }\n' >"$t"
  check "real Gradle: failing test blocks"  2 "$first" "" "StopHookProbeTest"
  rm -f "$t"
  check "real Gradle: green suite stops"    0 "$first" ""
fi

[ $fail = 0 ] && echo "all passed"
exit $fail
