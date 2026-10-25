#!/bin/bash
# Tests for stop-tests.sh. Fast cases swap in shell commands for Gradle.
# With --gradle, also puts a failing test and then a compile error into the real
# project, checks the hook blocks on each, then removes them.
cd "$(dirname "$0")/../.." || exit 1
hook=.claude/hooks/stop-tests.sh
fail=0

# check NAME EXPECTED_EXIT STDIN TEST_CMD [STDERR_MUST_CONTAIN] [STDERR_MUST_NOT_CONTAIN]
# An empty TEST_CMD runs the real Gradle command.
check() {
  if [ -n "$4" ]; then
    err=$(printf '%s' "$3" | SNAKE_HOOK_TEST=1 SNAKE_TEST_CMD="$4" "$hook" 2>&1 >/dev/null)
  else
    err=$(printf '%s' "$3" | "$hook" 2>&1 >/dev/null)
  fi
  code=$?
  if [ "$code" != "$2" ]; then echo "FAIL $1: exit $code, want $2: $err"; fail=1; return; fi
  if [ -n "$5" ] && ! printf '%s' "$err" | grep -qF -- "$5"; then
    echo "FAIL $1: stderr lacks '$5': $err"; fail=1; return
  fi
  if [ -n "$6" ] && printf '%s' "$err" | grep -qF -- "$6"; then
    echo "FAIL $1: stderr has '$6'"; fail=1; return
  fi
  echo "ok   $1"
}

first='{"hook_event_name":"Stop","stop_hook_active":false}'
again='{"hook_event_name":"Stop","stop_hook_active":true}'
red="echo 'GameTest > wall FAILED'; echo '40 tests completed, 1 failed'; false"

# Blocking: only on evidence the code is broken.
check "passing tests let Claude stop"            0 "$first" true
check "failing tests block the stop"             2 "$first" "$red" "GameTest > wall FAILED"
check "compile error blocks with file and line"  2 "$first" "echo 'e: file:///x/Game.kt:3:1 Unresolved reference foo'; false" "Game.kt:3:1"
check "block message names the command"          2 "$first" "$red # marker123" "marker123"
check "summary is capped at 40 lines"            2 "$first" "i=1; while [ \$i -le 100 ]; do echo \"T > t\$i FAILED\"; i=\$((i+1)); done; false" "T > t40 FAILED" "T > t41 FAILED"

# Setup problems are not test failures: tell the user, do not send Claude to fix them.
check "setup failure does not block"             1 "$first" "echo 'ERROR: JAVA_HOME is set to an invalid directory'; false" "could not run the tests"
check "missing command does not block"           1 "$first" "./no-such-gradlew" "could not run the tests"

# Loop guard.
check "second failure stops instead of looping"  1 "$again" "$red" "still failing"
check "passing on the retry is a clean stop"     0 "$again" true
check "unreadable input never blocks"            1 "not json" "$red"
check "empty input never blocks"                 1 "" "$red"
check "input without the flag never blocks"      1 '{"hook_event_name":"Stop"}' "$red"
# A PATH holding every tool the hook uses except jq.
nojq=$(mktemp -d) && for t in grep head tail mktemp dirname; do ln -s "$(command -v $t)" "$nojq/$t"; done
PATH="$nojq:/bin" check "loop guard works without jq on PATH" 1 "$again" "$red" "still failing"
rm -rf "$nojq"

# Runs in the project no matter what CLAUDE_PROJECT_DIR says.
export CLAUDE_PROJECT_DIR=/tmp
check "runs in its own project folder"           2 "$first" 'echo "P > $(pwd) FAILED"; false' "$(pwd) FAILED"
unset CLAUDE_PROJECT_DIR

if [ "$1" = "--gradle" ]; then
  t=app/src/test/java/dev/chavan/snake/StopHookProbeTest.kt
  trap 'rm -f "$t"' EXIT
  printf 'package dev.chavan.snake\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\nclass StopHookProbeTest { @Test fun probe() = assertEquals(1, 2) }\n' >"$t"
  check "real Gradle: failing test blocks"         2 "$first" "" "StopHookProbeTest > probe FAILED"
  printf 'package dev.chavan.snake\nclass StopHookProbeTest { val x: Int = "not an int" }\n' >"$t"
  check "real Gradle: compile error blocks"        2 "$first" "" "StopHookProbeTest.kt:2"
  rm -f "$t"
  check "real Gradle: green suite stops"           0 "$first" ""
  export SNAKE_TEST_CMD=false
  check "real Gradle: stray override is ignored"   0 "$first" ""
  unset SNAKE_TEST_CMD
fi

[ $fail = 0 ] && echo "all passed"
exit $fail
