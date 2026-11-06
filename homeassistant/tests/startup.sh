#!/bin/sh
# Run with a POSIX shell and jq. The fake Java process never connects to a DB.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
startup="$script_dir/../receiptshooter/run.sh"
if [ -e /tmp/receiptshooter ]; then
  printf 'Refusing to use an existing /tmp/receiptshooter directory.\n' >&2
  exit 1
fi
test_dir=$(mktemp -d /tmp/receiptshooter-start-test.XXXXXXXX)
java_process=
cleanup() {
  status=$?
  if [ -n "$java_process" ]; then
    kill -TERM "$java_process" 2>/dev/null || :
    wait "$java_process" 2>/dev/null || :
  fi
  if [ "$status" -ne 0 ] && [ -f "$test_dir/output" ]; then
    cat "$test_dir/output" >&2
  fi
  rmdir /tmp/receiptshooter 2>/dev/null || :
  case "$test_dir" in /tmp/receiptshooter-start-test.*) rm -rf "$test_dir" ;; esac
}
trap cleanup EXIT HUP INT TERM
mkdir "$test_dir/bin"
cat > "$test_dir/bin/java" <<'EOF'
#!/bin/sh
set -eu
[ "$DB_URL" = 'jdbc:mariadb://core-mariadb:3306/receiptshooterdb' ]
[ "$DB_USERNAME" = 'test-user' ]
[ "$DB_PASSWORD" = 'p@$$ word!' ]
[ "$SERVER_ADDRESS" = '0.0.0.0' ]
[ "$SESSION_TIMEOUT_SECONDS" = '600' ]
if [ "$EXPECT_ADMIN" = yes ]; then
  [ "$ADMIN_USERNAME" = 'test-admin' ]
  [ "$ADMIN_INITIAL_PASSWORD" = 'temp "password"' ]
else
  [ -z "${ADMIN_USERNAME+x}" ]
  [ -z "${ADMIN_INITIAL_PASSWORD+x}" ]
fi
printf '%s\n' "$@" > "$JAVA_TEST_DIR/arguments"
EOF
chmod +x "$test_dir/bin/java"
PATH="$test_dir/bin:$PATH"
JAVA_TEST_DIR=$test_dir
RECEIPTSHOOTER_OPTIONS_FILE="$test_dir/options.json"
export PATH JAVA_TEST_DIR RECEIPTSHOOTER_OPTIONS_FILE
cat > "$RECEIPTSHOOTER_OPTIONS_FILE" <<'EOF'
{"db_url":"jdbc:mariadb://core-mariadb:3306/receiptshooterdb",
 "db_username":"test-user","db_password":"p@$$ word!",
 "java_heap_mb":320,"session_timeout_seconds":600,
 "admin_username":"test-admin","admin_initial_password":"temp \"password\""}
EOF

EXPECT_ADMIN=yes
export EXPECT_ADMIN
sh "$startup" > "$test_dir/output" 2>&1
grep -qx -- '-Xmx320m' "$test_dir/arguments"
grep -qx -- '-Djava.io.tmpdir=/tmp/receiptshooter' "$test_dir/arguments"
if grep -Fq 'p@$$ word!' "$test_dir/output"; then
  printf 'The startup output contained the test password.\n' >&2
  exit 1
fi

jq 'del(.admin_username, .admin_initial_password)' "$RECEIPTSHOOTER_OPTIONS_FILE" > "$test_dir/next.json"
mv "$test_dir/next.json" "$RECEIPTSHOOTER_OPTIONS_FILE"
EXPECT_ADMIN=no
export EXPECT_ADMIN
sh "$startup" > "$test_dir/output" 2>&1

rm "$test_dir/arguments"
jq 'del(.db_password)' "$RECEIPTSHOOTER_OPTIONS_FILE" > "$test_dir/next.json"
mv "$test_dir/next.json" "$RECEIPTSHOOTER_OPTIONS_FILE"
if sh "$startup" > "$test_dir/output" 2>&1 || [ -e "$test_dir/arguments" ]; then
  printf 'Missing database credentials did not prevent startup.\n' >&2
  exit 1
fi

jq '.db_password="p@$$ word!" | .java_heap_mb=200' "$RECEIPTSHOOTER_OPTIONS_FILE" > "$test_dir/next.json"
mv "$test_dir/next.json" "$RECEIPTSHOOTER_OPTIONS_FILE"
if sh "$startup" > "$test_dir/output" 2>&1 || [ -e "$test_dir/arguments" ]; then
  printf 'An invalid heap size did not prevent startup.\n' >&2
  exit 1
fi

jq '.java_heap_mb=320 | .admin_username="test-admin"' "$RECEIPTSHOOTER_OPTIONS_FILE" > "$test_dir/next.json"
mv "$test_dir/next.json" "$RECEIPTSHOOTER_OPTIONS_FILE"
if sh "$startup" > "$test_dir/output" 2>&1 || [ -e "$test_dir/arguments" ]; then
  printf 'An incomplete administrator bootstrap did not prevent startup.\n' >&2
  exit 1
fi
# A fake Java process records its PID and handles TERM. This checks the shell
# exec/signal path; a real JVM under Supervisor still requires on-device testing.
jq 'del(.admin_username, .admin_initial_password)' "$RECEIPTSHOOTER_OPTIONS_FILE" > "$test_dir/next.json"
mv "$test_dir/next.json" "$RECEIPTSHOOTER_OPTIONS_FILE"
cat > "$test_dir/bin/java" <<'EOF'
#!/bin/sh
trap 'printf received > "$JAVA_TEST_DIR/term"; exit 0' TERM
printf '%s' "$$" > "$JAVA_TEST_DIR/pid"
while :; do sleep 0.1; done
EOF
chmod +x "$test_dir/bin/java"
sh "$startup" > "$test_dir/output" 2>&1 &
java_process=$!
attempt=0
while [ ! -f "$test_dir/pid" ] && [ "$attempt" -lt 100 ]; do
  if ! kill -0 "$java_process" 2>/dev/null; then
    printf 'The fake Java process stopped before the signal test.\n' >&2
    exit 1
  fi
  sleep 0.1
  attempt=$((attempt + 1))
done
[ -f "$test_dir/pid" ] || { printf 'The signal test did not start.\n' >&2; exit 1; }
[ "$(cat "$test_dir/pid")" = "$java_process" ] || { printf 'The startup script did not exec Java.\n' >&2; exit 1; }
kill -TERM "$java_process"
wait "$java_process"
java_process=
[ -f "$test_dir/term" ] || { printf 'The fake Java process did not receive TERM.\n' >&2; exit 1; }
printf 'Startup configuration and signal checks passed.\n'
