#!/bin/sh
set -eu

# Home Assistant writes this root-readable file immediately before startup.
# The override is useful for testing outside Home Assistant.
options_file="${RECEIPTSHOOTER_OPTIONS_FILE:-/data/options.json}"

required_string() {
  if ! value=$(jq -er --arg key "$1" \
    '.[$key] | select(type == "string" and length > 0 and
      ((contains("\u0000") or contains("\r") or contains("\n")) | not))' \
    "$options_file" 2>/dev/null); then
    printf 'ReceiptShooter: set a valid %s in the app configuration.\n' "$1" >&2
    exit 1
  fi
  printf '%s' "$value"
}

optional_string() {
  if ! value=$(jq -er --arg key "$1" \
    '(.[$key] // "") | select(type == "string" and
      ((contains("\u0000") or contains("\r") or contains("\n")) | not))' \
    "$options_file" 2>/dev/null); then
    printf 'ReceiptShooter: invalid %s in the app configuration.\n' "$1" >&2
    exit 1
  fi
  printf '%s' "$value"
}

if [ ! -r "$options_file" ]; then
  printf 'ReceiptShooter: app configuration is unavailable.\n' >&2
  exit 1
fi

DB_URL=$(required_string db_url)
DB_USERNAME=$(required_string db_username)
DB_PASSWORD=$(required_string db_password)
if ! heap_mb=$(jq -er '.java_heap_mb | select(type == "number" and floor == . and . >= 256 and . <= 1024)' "$options_file" 2>/dev/null); then
  printf 'ReceiptShooter: java_heap_mb must be an integer from 256 to 1024.\n' >&2
  exit 1
fi
if ! SESSION_TIMEOUT_SECONDS=$(jq -er '.session_timeout_seconds | select(type == "number" and floor == . and . >= 60 and . <= 7776000)' "$options_file" 2>/dev/null); then
  printf 'ReceiptShooter: session_timeout_seconds must be an integer from 60 to 7776000.\n' >&2
  exit 1
fi

admin_username=$(optional_string admin_username)
admin_password=$(optional_string admin_initial_password)
unset ADMIN_USERNAME ADMIN_INITIAL_PASSWORD
if [ -n "$admin_username" ] || [ -n "$admin_password" ]; then
  if [ -z "$admin_username" ] || [ -z "$admin_password" ]; then
    printf 'ReceiptShooter: provide both initial administrator fields, or neither.\n' >&2
    exit 1
  fi
  ADMIN_USERNAME=$admin_username
  ADMIN_INITIAL_PASSWORD=$admin_password
  export ADMIN_USERNAME ADMIN_INITIAL_PASSWORD
fi
unset admin_username admin_password value

SERVER_ADDRESS=0.0.0.0
export DB_URL DB_USERNAME DB_PASSWORD SERVER_ADDRESS SESSION_TIMEOUT_SECONDS

# This directory exists only in the container's ephemeral filesystem. Remove
# leftovers from an interrupted prior run before Tomcat uses it for multipart files.
rm -rf /tmp/receiptshooter
umask 077
mkdir /tmp/receiptshooter
printf 'Starting ReceiptShooter.\n'
exec java -Xms64m "-Xmx${heap_mb}m" -XX:+ExitOnOutOfMemoryError \
  -Djava.awt.headless=true -Djava.io.tmpdir=/tmp/receiptshooter \
  -jar /opt/receiptshooter/receiptshooter.jar
