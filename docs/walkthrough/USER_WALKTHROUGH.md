# User Walkthrough (Manual CLI Scenario)

This walkthrough keeps **all action logic directly in this file**.

Low-level helper functions and introspection functions live in one file:

- `docs/walkthrough/scripts/lib.sh`

## 1. Prerequisites

```bash
# terminal A (from repository root)
docker compose up -d
./gradlew quarkusDev
```

```bash
# terminal B (optional overrides)
export BASE_URL="http://localhost:8080"
export API_KEY="dev-local-api-key"
```

```bash
# load helper library (required for all steps below)
source docs/walkthrough/scripts/lib.sh
require_dependencies
assert_ready_and_authorized
```

## 2. Initialize walkthrough state

```bash
source docs/walkthrough/scripts/lib.sh

state_reset
RUN_TAG="$(date -u +%Y%m%d%H%M%S)"

CUSTOMER1_KEY="customer:C-WALK-${RUN_TAG}-001"
ACCOUNT1_KEY="account:A-WALK-${RUN_TAG}-001"
CUSTOMER2_KEY="customer:C-WALK-${RUN_TAG}-002"
ACCOUNT2_KEY="account:A-WALK-${RUN_TAG}-002"

state_set RUN_TAG "$RUN_TAG"
state_set CUSTOMER1_KEY "$CUSTOMER1_KEY"
state_set ACCOUNT1_KEY "$ACCOUNT1_KEY"
state_set CUSTOMER2_KEY "$CUSTOMER2_KEY"
state_set ACCOUNT2_KEY "$ACCOUNT2_KEY"

echo "State initialized: $STATE_FILE"
cat "$STATE_FILE"
```

## 3. Action: seed baseline data (facts + event)

```bash
source docs/walkthrough/scripts/lib.sh
state_load
assert_ready_and_authorized

BASE_CHANGESET_ID="$(new_uuid)"
TX_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

BASE_PAYLOAD="$(jq -n \
  --arg id "$BASE_CHANGESET_ID" \
  --arg customerKey "$CUSTOMER1_KEY" \
  --arg accountKey "$ACCOUNT1_KEY" \
  --arg ts "$TX_TS" \
  '{
    id: $id,
    entries: [
      {
        kind: "FACT",
        action: "UPSERT",
        factKey: $customerKey,
        factType: "Customer",
        data: {
          customerId: "C-WALK-001",
          name: "Walkthrough Customer 1",
          tier: "PREMIUM",
          balance: 1200
        }
      },
      {
        kind: "FACT",
        action: "UPSERT",
        factKey: $accountKey,
        factType: "Account",
        data: {
          accountId: "A-WALK-001",
          customerId: "C-WALK-001",
          type: "SAVINGS",
          balance: 150000
        }
      },
      {
        kind: "EVENT",
        action: "EMIT",
        entryPoint: "transactions",
        factType: "Transaction",
        timestamp: $ts,
        data: {
          txId: "TX-WALK-001",
          customerId: "C-WALK-001",
          amount: 2500
        }
      }
    ],
    metadata: {
      source: "manual-walkthrough",
      stage: "seed"
    }
  }')"

printf '%s\n' "$BASE_PAYLOAD" >"$STATE_DIR/base_changeset_payload.json"

http_call POST "/api/v1/changesets" "$BASE_PAYLOAD"
expect_status "201" "seed changeset"
pretty_json "$HTTP_BODY"

BASE_SEQUENCE="$(json_get "$HTTP_BODY" '.sequenceNum')"
BASE_DERIVED_FACT_ID="$(echo "$HTTP_BODY" | jq -r '.newDerivedFacts[]? | select(.factType == "HighValueCustomer") | .factId' | head -n1)"

state_set BASE_CHANGESET_ID "$BASE_CHANGESET_ID"
state_set BASE_SEQUENCE "$BASE_SEQUENCE"
if [[ -n "$BASE_DERIVED_FACT_ID" ]]; then
  state_set BASE_DERIVED_FACT_ID "$BASE_DERIVED_FACT_ID"
fi
```

## 4. Action: idempotency verification

```bash
source docs/walkthrough/scripts/lib.sh
state_load
assert_ready_and_authorized

BASE_PAYLOAD="$(cat "$STATE_DIR/base_changeset_payload.json")"

# same ID + same payload => same sequence
http_call POST "/api/v1/changesets" "$BASE_PAYLOAD"
expect_status "201" "idempotency same payload"
pretty_json "$HTTP_BODY"

REPLAY_SEQUENCE="$(json_get "$HTTP_BODY" '.sequenceNum')"
if [[ "$REPLAY_SEQUENCE" != "$BASE_SEQUENCE" ]]; then
  echo "Expected same sequence ($BASE_SEQUENCE), got $REPLAY_SEQUENCE" >&2
  echo "Idempotency check failed. Stop here and inspect /api/v1/changesets before continuing." >&2
fi

# same ID + different payload => 409
MISMATCH_PAYLOAD="$(echo "$BASE_PAYLOAD" | jq '.entries[0].data.name = "Walkthrough Customer 1 Changed"')"
http_call POST "/api/v1/changesets" "$MISMATCH_PAYLOAD"
expect_status "409" "idempotency mismatch"
pretty_json "$HTTP_BODY"

state_set IDEMPOTENCY_ERROR_CODE "$(json_get "$HTTP_BODY" '.code')"
```

## 5. Action: create manual checkpoint

```bash
source docs/walkthrough/scripts/lib.sh
state_load
assert_ready_and_authorized

http_call POST "/api/v1/checkpoints"
expect_status "201" "create checkpoint"
pretty_json "$HTTP_BODY"

state_set CHECKPOINT_ID "$(json_get "$HTTP_BODY" '.checkpointId')"
state_set CHECKPOINT_SEQUENCE "$(json_get "$HTTP_BODY" '.sequenceNum')"
```

## 6. Action: rule lifecycle (validate, upload, activate)

Candidate file:

- `docs/walkthrough/scripts/rules/candidate-walkthrough.drl`

```bash
source docs/walkthrough/scripts/lib.sh
state_load
assert_ready_and_authorized

RULE_LABEL="walkthrough-candidate-$(date -u +%Y%m%d%H%M%S)"
RULE_REQUEST="$(jq -n \
  --arg version_label "$RULE_LABEL" \
  --arg uploadedBy "manual-walkthrough" \
  --rawfile drl docs/walkthrough/scripts/rules/candidate-walkthrough.drl \
  '{
    versionLabel: $version_label,
    uploadedBy: $uploadedBy,
    drlFiles: {
      "com/sky/synome/rules/walkthrough-candidate.drl": $drl
    }
  }')"

http_call POST "/api/v1/rules/validate" "$RULE_REQUEST"
expect_status "200" "rules validate"
pretty_json "$HTTP_BODY"

if [[ "$(json_get "$HTTP_BODY" '.valid')" != "true" ]]; then
  echo "Validation failed. Stop here, fix candidate rules, then rerun this step." >&2
else
  http_call POST "/api/v1/rules/upload" "$RULE_REQUEST"
  expect_status "201" "rules upload"
  pretty_json "$HTTP_BODY"

  RULE_VERSION_ID="$(json_get "$HTTP_BODY" '.versionId')"
  state_set RULE_VERSION_ID "$RULE_VERSION_ID"

  http_call POST "/api/v1/rules/${RULE_VERSION_ID}/activate"
  expect_status "200" "rules activate"
  pretty_json "$HTTP_BODY"

  state_set ACTIVATED_VERSION_ID "$(json_get "$HTTP_BODY" '.activatedVersionId')"
  state_set PREVIOUS_VERSION_ID "$(json_get "$HTTP_BODY" '.previousVersionId')"
fi
```

## 7. Action: post-activation behavior check

```bash
source docs/walkthrough/scripts/lib.sh
state_load
assert_ready_and_authorized

RULE_CHECK_CHANGESET_ID="$(new_uuid)"
RULE_CHECK_PAYLOAD="$(jq -n \
  --arg id "$RULE_CHECK_CHANGESET_ID" \
  --arg customerKey "$CUSTOMER2_KEY" \
  --arg accountKey "$ACCOUNT2_KEY" \
  '{
    id: $id,
    entries: [
      {
        kind: "FACT",
        action: "UPSERT",
        factKey: $customerKey,
        factType: "Customer",
        data: {
          customerId: "C-WALK-002",
          name: "Walkthrough Customer 2",
          tier: "PREMIUM",
          balance: 300
        }
      },
      {
        kind: "FACT",
        action: "UPSERT",
        factKey: $accountKey,
        factType: "Account",
        data: {
          accountId: "A-WALK-002",
          customerId: "C-WALK-002",
          type: "SAVINGS",
          balance: 60000
        }
      }
    ],
    metadata: {
      source: "manual-walkthrough",
      stage: "rules-activation-check"
    }
  }')"

http_call POST "/api/v1/changesets" "$RULE_CHECK_PAYLOAD"
expect_status "201" "post-activation changeset"
pretty_json "$HTTP_BODY"

RULE_DERIVED_FACT_ID="$(echo "$HTTP_BODY" | jq -r '.newDerivedFacts[]? | select(.factType == "HighValueCustomer") | .factId' | head -n1)"

state_set RULE_CHECK_CHANGESET_ID "$RULE_CHECK_CHANGESET_ID"
if [[ -n "$RULE_DERIVED_FACT_ID" ]]; then
  state_set RULE_DERIVED_FACT_ID "$RULE_DERIVED_FACT_ID"
fi
```

## 8. Introspection commands (from `lib.sh`)

Each command below uses the helper + introspection functions from one file.

```bash
source docs/walkthrough/scripts/lib.sh
inspect_context
inspect_health
inspect_changesets
inspect_facts
inspect_events
inspect_provenance
inspect_provenance_db
inspect_checkpoints
inspect_rules
inspect_streams
inspect_persistence_db
```

Optional low-level DB introspection:

```bash
source docs/walkthrough/scripts/lib.sh
inspect_db
```

Full introspection in one call:

```bash
source docs/walkthrough/scripts/lib.sh
inspect_all
```

## 9. Provenance verification

```bash
source docs/walkthrough/scripts/lib.sh
inspect_provenance
```

- derived facts are visible through API,
- explanation trees are traversable,
- provenance IDs captured during actions can be fetched again.

### 9.1 Provenance rows persisted in PostgreSQL

Quick helper:

```bash
source docs/walkthrough/scripts/lib.sh
inspect_provenance_db
```

Manual SQL (copy/paste):

```bash
source docs/walkthrough/scripts/lib.sh
state_load

docker compose exec -T postgres psql -U engine -d decision_engine -c \
"select fact_id, fact_type, produced_by_rule, insertion_type, created_at, retracted_at
 from fact_provenance
 order by created_at desc
 limit 20;"

docker compose exec -T postgres psql -U engine -d decision_engine -c \
"select fact_id, fact_type, produced_by_rule, insertion_type, input_fact_ids, changeset_id, created_at
 from fact_provenance
 where fact_id in ('${BASE_DERIVED_FACT_ID:-}', '${RULE_DERIVED_FACT_ID:-}');"

docker compose exec -T postgres psql -U engine -d decision_engine -c \
"select fact_id, rule_name, modified_at
 from fact_modifications
 order by modified_at desc
 limit 20;"
```

Note: provenance persistence is asynchronous. If rows are not visible immediately, wait 1-2 seconds and query again.

## 10. Persistence verification

### 10.1 Core persisted state in PostgreSQL

Quick helper:

```bash
source docs/walkthrough/scripts/lib.sh
inspect_persistence_db
```

Manual SQL (copy/paste):

```bash
source docs/walkthrough/scripts/lib.sh

docker compose exec -T postgres psql -U engine -d decision_engine -c \
"select sequence_num, changeset_id, applied_at, rules_fired, duration_ms
 from changeset_log
 order by sequence_num desc
 limit 20;"

docker compose exec -T postgres psql -U engine -d decision_engine -c \
"select checkpoint_id, sequence_num, created_at, fact_count, size_bytes
 from checkpoints
 order by created_at desc
 limit 20;"

docker compose exec -T postgres psql -U engine -d decision_engine -c \
"select event_id, changeset_id, sequence_num, entry_point, event_timestamp
 from changeset_events
 order by event_id desc
 limit 20;"
```

### 10.2 Durability across restart

1. Stop and start the app (`quarkusDev`) in your app terminal.
2. Re-run:

```bash
source docs/walkthrough/scripts/lib.sh
inspect_changesets
inspect_checkpoints
inspect_events
inspect_provenance
inspect_provenance_db
inspect_persistence_db
```

If the same changesets/checkpoints/events/provenance rows are still present, persistence and replay durability are working as expected.
