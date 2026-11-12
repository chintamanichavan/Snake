#!/bin/bash
# Tests for ./check (exit 0 green, 1 a test failed, 2 could not run). Red cases run on a
# copy of the repo's files, so the real tree is never touched. Takes a few minutes: each
# copy is a fresh Gradle build.
D=$(cd "$(dirname "$0")/.." && pwd)
fail=0
ok()  { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }
# expect NAME WANT_EXIT OUTPUT_MUST_CONTAIN -- COMMAND...
expect() {
  local name=$1 want=$2 needle=$3; shift 4
  out=$("$@" 2>&1); code=$?
  [ "$code" = "$want" ] || { bad "$name: exit $code, want $want"; printf '%s\n' "$out" | tail -8; return; }
  [ -z "$needle" ] || printf '%s' "$out" | grep -qF -- "$needle" || { bad "$name: output lacks '$needle'"; return; }
  ok "$name"
}
# copy: tracked and untracked-unignored files, plus local.properties (ignored, names the SDK).
copy() {
  local c; c=$(mktemp -d)
  (cd "$D" && git ls-files -z --cached --others --exclude-standard | tar --null -T - -cf -) | (cd "$c" && tar xf -)
  cp "$D/local.properties" "$c/"
  echo "$c"
}
t=app/src/test/java/dev/chavan/snake/CheckProbeTest.kt

expect "green from any cwd" 0 "ok   unit" -- sh -c "cd / && '$D/check'"
printf '%s' "$out" | grep -q Terminated && bad "green run prints job-control noise" || ok "green run is quiet"

c=$(copy)
printf 'package dev.chavan.snake\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\nclass CheckProbeTest { @Test fun probe() = assertEquals(1, 2) }\n' > "$c/$t"
expect "a failing unit test exits 1" 1 "CheckProbeTest > probe FAILED" -- "$c/check"
printf 'package dev.chavan.snake\nclass CheckProbeTest { val x: Int = "not an int" }\n' > "$c/$t"
expect "a compile error exits 1" 1 "CheckProbeTest.kt" -- "$c/check"
rm -f "$c/$t"

rm "$c/local.properties"
expect "no Android SDK exits 2, not 1" 2 "could not run" -- env ANDROID_HOME= ANDROID_SDK_ROOT= "$c/check"
cp "$D/local.properties" "$c/"

# A hung suite is stopped by check's own limit and leaves nothing running.
printf '#!/bin/bash\nsleep 3704\n' > "$c/.claude/hooks/test_stop_tests.sh"
expect "a hung suite exits 2" 2 "did not finish" -- env CHECK_TEST=1 CHECK_LIMIT=20 "$c/check"
pgrep -qx -f 'sleep 3704' && bad "hung suite left running" || ok "hung suite is killed"
rm -rf "$c"

[ "$fail" = 0 ] && echo "all passed"
exit "$fail"
