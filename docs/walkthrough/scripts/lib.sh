#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../../" && pwd)"

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-dev-local-api-key}"
STATE_DIR="${STATE_DIR:-/tmp/synome-walkthrough}"
STATE_FILE="$STATE_DIR/state.env"

HTTP_STATUS=""
HTTP_BODY=""

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    return 1
  fi
}

require_dependencies() {
  require_cmd curl || return 1
  require_cmd jq || return 1
}

section() {
  echo
  echo "=== $* ==="
}

new_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
    return
  fi
  cat /proc/sys/kernel/random/uuid
}

state_reset() {
  mkdir -p "$STATE_DIR"
  rm -f "$STATE_FILE"
  : >"$STATE_FILE"
}

state_set() {
  local key="$1"
  local value="$2"
  mkdir -p "$STATE_DIR"
  touch "$STATE_FILE"

  local tmp
  tmp="$(mktemp)"
  grep -v "^${key}=" "$STATE_FILE" >"$tmp" || true
  printf '%s=%s\n' "$key" "$value" >>"$tmp"
  mv "$tmp" "$STATE_FILE"
}

state_load() {
  if [[ -f "$STATE_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$STATE_FILE"
  fi
}

state_require() {
  local key="$1"
  state_load
  if [[ -z "${!key:-}" ]]; then
    echo "Missing required state key '$key'." >&2
    return 1
  fi
}

pretty_json() {
  local payload="$1"
  if [[ -z "$payload" ]]; then
    echo "<empty>"
    return
  fi
  echo "$payload" | jq .
}

json_get() {
  local payload="$1"
  local filter="$2"
  echo "$payload" | jq -r "$filter"
}

http_call_public() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local tmp
  tmp="$(mktemp)"

  if [[ -n "$body" ]]; then
    HTTP_STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" \
      "$BASE_URL$path" \
      -H 'Content-Type: application/json' \
      --data "$body")"
  else
    HTTP_STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" \
      "$BASE_URL$path")"
  fi

  HTTP_BODY="$(cat "$tmp")"
  rm -f "$tmp"
}

http_call() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local tmp
  tmp="$(mktemp)"

  if [[ -n "$body" ]]; then
    HTTP_STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" \
      "$BASE_URL$path" \
      -H "X-Api-Key: $API_KEY" \
      -H 'Content-Type: application/json' \
      --data "$body")"
  else
    HTTP_STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" \
      "$BASE_URL$path" \
      -H "X-Api-Key: $API_KEY")"
  fi

  HTTP_BODY="$(cat "$tmp")"
  rm -f "$tmp"
}

expect_status() {
  local expected="$1"
  local context="$2"
  if [[ "$HTTP_STATUS" != "$expected" ]]; then
    echo "Unexpected status in $context: got $HTTP_STATUS, expected $expected" >&2
    echo "$HTTP_BODY" >&2
    return 1
  fi
}

wait_for_ready() {
  local attempts=60
  local i
  for ((i = 1; i <= attempts; i++)); do
    local status
    status="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/q/health/ready" || true)"
    if [[ "$status" == "200" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

assert_ready_and_authorized() {
  section "Checking service readiness"
  if ! wait_for_ready; then
    echo "Service is not ready at $BASE_URL. Start it first (docker compose + quarkusDev)." >&2
    return 1
  fi

  http_call GET "/api/v1/facts"
  if [[ "$HTTP_STATUS" != "200" ]]; then
    echo "Authentication/authorization check failed. Expected 200 from /api/v1/facts." >&2
    echo "$HTTP_BODY" >&2
    return 1
  fi
  echo "Service ready and API key accepted."
}

stream_once() {
  local path="$1"
  local max_events="${2:-3}"
  curl -sS -N --max-time 6 \
    -H "X-Api-Key: $API_KEY" \
    "$BASE_URL$path?maxEvents=$max_events"
}

inspect_context() {
  section "Walkthrough context"
  state_load
  echo "BASE_URL=$BASE_URL"
  echo "API_KEY=${API_KEY:0:4}... (length ${#API_KEY})"
  echo "STATE_FILE=$STATE_FILE"
  if [[ -f "$STATE_FILE" ]]; then
    cat "$STATE_FILE"
  else
    echo "State file not initialized yet."
  fi
}

inspect_health() {
  section "Health"
  http_call_public GET "/q/health"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"

  section "Readiness"
  http_call_public GET "/q/health/ready"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"
}

inspect_changesets() {
  state_load

  section "Changeset list"
  http_call GET "/api/v1/changesets?limit=20&offset=0"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"

  if [[ -n "${BASE_CHANGESET_ID:-}" ]]; then
    section "Baseline changeset"
    http_call GET "/api/v1/changesets/$BASE_CHANGESET_ID"
    echo "Status: $HTTP_STATUS"
    pretty_json "$HTTP_BODY"
  fi

  if [[ -n "${RULE_CHECK_CHANGESET_ID:-}" ]]; then
    section "Post-activation changeset"
    http_call GET "/api/v1/changesets/$RULE_CHECK_CHANGESET_ID"
    echo "Status: $HTTP_STATUS"
    pretty_json "$HTTP_BODY"
  fi
}

inspect_facts() {
  state_load

  section "All base facts"
  http_call GET "/api/v1/facts"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"

  section "Fact stats"
  http_call GET "/api/v1/facts/stats"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"

  section "Derived fact summaries"
  http_call GET "/api/v1/facts/derived?limit=50&offset=0"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"
}

inspect_events() {
  state_load

  section "Recent events"
  http_call GET "/api/v1/events?limit=20&offset=0"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"

  if [[ -n "${BASE_CHANGESET_ID:-}" ]]; then
    section "Events for baseline changeset"
    http_call GET "/api/v1/events/$BASE_CHANGESET_ID?limit=20&offset=0"
    echo "Status: $HTTP_STATUS"
    pretty_json "$HTTP_BODY"
  fi
}

inspect_provenance() {
  state_load

  section "Provenance search"
  http_call GET "/api/v1/provenance/search?activeOnly=true&limit=50&offset=0"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"

  if [[ -n "${BASE_DERIVED_FACT_ID:-}" ]]; then
    section "Baseline derived provenance"
    http_call GET "/api/v1/provenance/facts/$BASE_DERIVED_FACT_ID"
    echo "Status: $HTTP_STATUS"
    pretty_json "$HTTP_BODY"

    http_call GET "/api/v1/provenance/facts/$BASE_DERIVED_FACT_ID/explain?maxDepth=5"
    echo "Status: $HTTP_STATUS"
    pretty_json "$HTTP_BODY"
  fi

  if [[ -n "${RULE_DERIVED_FACT_ID:-}" ]]; then
    section "Post-activation derived provenance"
    http_call GET "/api/v1/provenance/facts/$RULE_DERIVED_FACT_ID"
    echo "Status: $HTTP_STATUS"
    pretty_json "$HTTP_BODY"

    http_call GET "/api/v1/provenance/facts/$RULE_DERIVED_FACT_ID/explain?maxDepth=5"
    echo "Status: $HTTP_STATUS"
    pretty_json "$HTTP_BODY"
  fi
}

inspect_checkpoints() {
  state_load

  section "Checkpoint list"
  http_call GET "/api/v1/checkpoints?limit=20&offset=0"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"

  section "Latest checkpoint"
  http_call GET "/api/v1/checkpoints/latest"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"

  if [[ -n "${CHECKPOINT_ID:-}" ]]; then
    section "Checkpoint by ID"
    http_call GET "/api/v1/checkpoints/$CHECKPOINT_ID"
    echo "Status: $HTTP_STATUS"
    pretty_json "$HTTP_BODY"
  fi
}

inspect_rules() {
  section "Rule versions"
  http_call GET "/api/v1/rules?limit=20&offset=0"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"

  section "Active rule version"
  http_call GET "/api/v1/rules/active"
  echo "Status: $HTTP_STATUS"
  pretty_json "$HTTP_BODY"
}

inspect_streams() {
  section "SSE: changesets"
  stream_once "/api/v1/ops/stream/changesets" 3

  section "SSE: checkpoints"
  stream_once "/api/v1/ops/stream/checkpoints" 3

  section "SSE: recovery"
  stream_once "/api/v1/ops/stream/recovery" 3
}

inspect_db() {
  section "Database introspection (optional)"

  if ! command -v docker >/dev/null 2>&1; then
    echo "docker command not found. Skipping DB view."
    return
  fi

  if ! docker compose ps postgres >/dev/null 2>&1; then
    echo "docker compose postgres service unavailable. Skipping DB view."
    return
  fi

  docker compose exec -T postgres psql -U engine -d decision_engine -c \
    "select sequence_num, changeset_id, applied_at, rules_fired, duration_ms from changeset_log order by sequence_num desc limit 10;"

  docker compose exec -T postgres psql -U engine -d decision_engine -c \
    "select checkpoint_id, sequence_num, created_at, fact_count, size_bytes from checkpoints order by created_at desc limit 10;"

  docker compose exec -T postgres psql -U engine -d decision_engine -c \
    "select event_id, changeset_id, sequence_num, entry_point, event_timestamp from changeset_events order by event_id desc limit 10;"
}

inspect_provenance_db() {
  section "Provenance persistence (database)"
  state_load

  if ! command -v docker >/dev/null 2>&1; then
    echo "docker command not found. Skipping provenance DB checks."
    return
  fi

  if ! docker compose ps postgres >/dev/null 2>&1; then
    echo "docker compose postgres service unavailable. Skipping provenance DB checks."
    return
  fi

  docker compose exec -T postgres psql -U engine -d decision_engine -c \
    "select fact_id, fact_type, produced_by_rule, insertion_type, created_at, retracted_at
     from fact_provenance
     order by created_at desc
     limit 20;"

  if [[ -n "${BASE_DERIVED_FACT_ID:-}" || -n "${RULE_DERIVED_FACT_ID:-}" ]]; then
    docker compose exec -T postgres psql -U engine -d decision_engine -c \
      "select fact_id, fact_type, produced_by_rule, insertion_type, input_fact_ids, changeset_id, created_at
       from fact_provenance
       where fact_id in ('${BASE_DERIVED_FACT_ID:-}', '${RULE_DERIVED_FACT_ID:-}');"
  fi

  docker compose exec -T postgres psql -U engine -d decision_engine -c \
    "select fact_id, rule_name, modified_at
     from fact_modifications
     order by modified_at desc
     limit 20;"
}

inspect_persistence_db() {
  section "Core persistence (database)"
  inspect_db
}

inspect_all() {
  inspect_context
  inspect_health
  inspect_changesets
  inspect_facts
  inspect_events
  inspect_provenance
  inspect_checkpoints
  inspect_rules
  inspect_streams
  inspect_provenance_db
  inspect_persistence_db
}
