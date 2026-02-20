#!/usr/bin/env bash
#
# Test script for gRPC services against the Tinkar gRPC API using grpcurl.
# Usage: ./test-grpc-scenarios.sh [HOST:PORT]
#   HOST:PORT defaults to localhost:9095
#
# Tests all three gRPC services:
#   - IkeGraphRAG (Tier 1: simple, opinionated API)
#   - IkeKnowledgeGraph (Tier 2: coordinate-aware knowledge graph)
#   - TinkarSearchService (deprecated, backward compatibility)
#

set -euo pipefail

GRPC_HOST="${1:-localhost:9095}"

# ── Counters ──────────────────────────────────────────────────────────
PASS=0
FAIL=0
SKIP=0
TOTAL=0

# ── Colors (disabled when piped) ──────────────────────────────────────
if [ -t 1 ]; then
  GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'
  CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
else
  GREEN=''; RED=''; YELLOW=''; CYAN=''; BOLD=''; NC=''
fi

# ── Helpers ───────────────────────────────────────────────────────────

header() {
  echo ""
  echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════${NC}"
  echo -e "${BOLD}${CYAN}  $1${NC}"
  echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════${NC}"
}

subheader() {
  echo ""
  echo -e "${BOLD}  ── $1 ──${NC}"
}

# Call a gRPC method and capture the JSON response.
# Usage: grpc_call <service/method> <json_payload>
#   Sets: RESPONSE (body), GRPC_OK (true/false)
grpc_call() {
  local method="$1"
  local payload="$2"
  local tmpfile
  tmpfile=$(mktemp)
  if grpcurl -plaintext -d "$payload" "$GRPC_HOST" "$method" > "$tmpfile" 2>/dev/null; then
    GRPC_OK="true"
  else
    GRPC_OK="false"
  fi
  RESPONSE=$(cat "$tmpfile")
  rm -f "$tmpfile"
}

# Extract total_count from a gRPC JSON response.
# gRPC uses snake_case and omits zero-valued fields.
# Usage: json_total_count
json_total_count() {
  echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('total_count', 0) or 0)
except:
    print(0)
" 2>/dev/null || echo "0"
}

# Assert that the gRPC call succeeded (no transport error).
# Usage: assert_grpc_ok <test_name>
assert_grpc_ok() {
  local name="$1"
  TOTAL=$((TOTAL + 1))
  if [ "$GRPC_OK" = "true" ]; then
    echo -e "  ${GREEN}PASS${NC}  $name (gRPC OK)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  $name  (gRPC call failed)"
    FAIL=$((FAIL + 1))
  fi
}

# Assert that the response success field is true.
# Usage: assert_success <test_name>
assert_success() {
  local name="$1"
  TOTAL=$((TOTAL + 1))
  local success
  success=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print('true' if data.get('success', False) else 'false')
except:
    print('false')
" 2>/dev/null) || success="false"
  if [ "$success" = "true" ]; then
    echo -e "  ${GREEN}PASS${NC}  $name (success=true)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  $name  (success=false)"
    FAIL=$((FAIL + 1))
  fi
}

# Assert that the response total_count matches an expected value.
# Usage: assert_count_eq <test_name> <expected_count>
assert_count_eq() {
  local name="$1"
  local expected="$2"
  local actual
  actual=$(json_total_count)
  TOTAL=$((TOTAL + 1))
  if [ "$actual" = "$expected" ]; then
    echo -e "  ${GREEN}PASS${NC}  $name (count=$actual)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  $name  (expected count=$expected, got count=$actual)"
    FAIL=$((FAIL + 1))
  fi
}

# Assert that the response total_count is > 0.
# Usage: assert_count_gt0 <test_name>
assert_count_gt0() {
  local name="$1"
  local actual
  actual=$(json_total_count)
  TOTAL=$((TOTAL + 1))
  if [ "$actual" -gt 0 ] 2>/dev/null; then
    echo -e "  ${GREEN}PASS${NC}  $name (count=$actual)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  $name  (count=0)"
    FAIL=$((FAIL + 1))
  fi
}

# Assert that the response contains a string.
# Usage: assert_contains <test_name> <expected_substring>
assert_contains() {
  local name="$1"
  local expected="$2"
  TOTAL=$((TOTAL + 1))
  if echo "$RESPONSE" | grep -qi "$expected"; then
    echo -e "  ${GREEN}PASS${NC}  $name"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  $name  (expected to find: \"$expected\")"
    FAIL=$((FAIL + 1))
  fi
}

# Build a PublicId JSON fragment from a UUID.
# Usage: pub_id <uuid>
pub_id() {
  echo "{\"public_id\":{\"uuids\":[\"$1\"]}}"
}

# Build a KnowledgeGraphConceptRequest with optional coordinate_override.
# Usage: kg_request <uuid> [coordinate_override_json]
kg_request() {
  local uuid="$1"
  local coords="${2:-}"
  if [ -z "$coords" ]; then
    echo "{\"public_id\":{\"uuids\":[\"$uuid\"]}}"
  else
    echo "{\"public_id\":{\"uuids\":[\"$uuid\"]},\"coordinate_override\":$coords}"
  fi
}

# Search via IkeGraphRAG.ConceptSearch, extract first matching concept UUID.
# Usage: grpc_search_device <label> <query> <expected_fqn_substring>
#   Sets: LAST_CONCEPT_ID
grpc_search_device() {
  local label="$1"
  local query="$2"
  local expected_fqn="${3:-}"

  grpc_call "ai.ica.tinkar.IkeGraphRAG/ConceptSearch" \
    "{\"query\":\"$query\",\"max_results\":10}"
  assert_grpc_ok "$label - ConceptSearch responds"
  assert_count_gt0 "$label - found in database"

  if [ -n "$expected_fqn" ]; then
    assert_contains "$label - FQN match" "$expected_fqn"
  fi

  # Extract first matching concept UUID
  LAST_CONCEPT_ID=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    fqn_match = '${expected_fqn:-}'.lower()
    for r in data.get('results', []):
        fqn = r.get('descriptions', {}).get('fully_qualified_name', '')
        if not fqn_match or fqn_match in fqn.lower():
            print(r['public_id']['uuids'][0])
            break
    else:
        results = data.get('results', [])
        if results:
            print(results[0]['public_id']['uuids'][0])
except:
    print('')
" 2>/dev/null) || LAST_CONCEPT_ID=""
}

# Test GetConceptSemantics (Tier 2) for a concept UUID.
# Usage: grpc_test_semantics <label> <concept_uuid> [expected_patterns...]
grpc_test_semantics() {
  local label="$1"
  local concept_uuid="$2"
  shift 2

  grpc_call "ai.ica.tinkar.IkeKnowledgeGraph/GetConceptSemantics" \
    "$(kg_request "$concept_uuid")"
  assert_grpc_ok "$label - GetConceptSemantics responds"
  assert_count_gt0 "$label - has semantics"

  for pattern in "$@"; do
    assert_contains "$label - has '$pattern'" "$pattern"
  done
}

# ══════════════════════════════════════════════════════════════════════
#  HEALTH CHECK
# ══════════════════════════════════════════════════════════════════════
header "Health Check"

echo "  Testing gRPC at $GRPC_HOST ..."

# Verify server reflection is available
TOTAL=$((TOTAL + 1))
if grpcurl -plaintext "$GRPC_HOST" list >/dev/null 2>&1; then
  echo -e "  ${GREEN}PASS${NC}  gRPC server is reachable (reflection enabled)"
  PASS=$((PASS + 1))
else
  echo -e "  ${RED}FAIL${NC}  gRPC server not reachable at $GRPC_HOST"
  FAIL=$((FAIL + 1))
  echo ""
  echo -e "  ${RED}Cannot continue — server is not running.${NC}"
  exit 1
fi

# Verify all three services are registered
for svc in "ai.ica.tinkar.IkeGraphRAG" "ai.ica.tinkar.IkeKnowledgeGraph" "ai.ica.tinkar.TinkarSearchService"; do
  TOTAL=$((TOTAL + 1))
  if grpcurl -plaintext "$GRPC_HOST" list 2>/dev/null | grep -q "$svc"; then
    echo -e "  ${GREEN}PASS${NC}  Service registered: $svc"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  Service NOT registered: $svc"
    FAIL=$((FAIL + 1))
  fi
done

# Basic search test
grpc_call "ai.ica.tinkar.IkeGraphRAG/Search" '{"query":"test"}'
assert_grpc_ok "IkeGraphRAG.Search responds"
assert_success "IkeGraphRAG.Search returns success"

# ══════════════════════════════════════════════════════════════════════
#  TIER 1: IkeGraphRAG — Scenario 1 (Albumin Gen.2)
# ══════════════════════════════════════════════════════════════════════
header "Tier 1 (IkeGraphRAG): Scenario 1 — Albumin Gen.2"

subheader "Device Search"
grpc_search_device "Albumin Gen.2" "Albumin Gen.2" "Albumin Gen.2"
ALBUMIN_ID="$LAST_CONCEPT_ID"

subheader "Concept Retrieval (uuid: $ALBUMIN_ID)"
if [ -n "$ALBUMIN_ID" ]; then
  grpc_call "ai.ica.tinkar.IkeGraphRAG/GetEntity" "$(pub_id "$ALBUMIN_ID")"
  assert_grpc_ok "GetEntity responds"
  assert_success "GetEntity returns success"
  assert_contains "GetEntity has FQN" "Albumin Gen.2"

  grpc_call "ai.ica.tinkar.IkeGraphRAG/GetChildConcepts" "$(pub_id "$ALBUMIN_ID")"
  assert_grpc_ok "GetChildConcepts responds"
  assert_success "GetChildConcepts returns success"

  grpc_call "ai.ica.tinkar.IkeGraphRAG/GetDescendantConcepts" "$(pub_id "$ALBUMIN_ID")"
  assert_grpc_ok "GetDescendantConcepts responds"
  assert_success "GetDescendantConcepts returns success"

  subheader "Tier 2 Semantics"
  grpc_test_semantics "Albumin Gen.2" "$ALBUMIN_ID" \
    "Identifier Pattern" \
    "Description Pattern"

  subheader "LIDR Records"
  grpc_call "ai.ica.tinkar.IkeGraphRAG/GetLIDRRecordConceptsFromTestKit" "$(pub_id "$ALBUMIN_ID")"
  assert_grpc_ok "GetLIDRRecordConceptsFromTestKit responds"
  TOTAL=$((TOTAL + 1))
  LIDR_ERROR=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('error_message', '') or '')
except:
    print('')
" 2>/dev/null) || LIDR_ERROR=""
  if [ -n "$LIDR_ERROR" ]; then
    echo -e "  ${RED}FAIL${NC}  LIDR records (error: $LIDR_ERROR)"
    FAIL=$((FAIL + 1))
  else
    LIDR_COUNT=$(json_total_count)
    if [ "$LIDR_COUNT" -gt 0 ] 2>/dev/null; then
      echo -e "  ${GREEN}PASS${NC}  LIDR records ($LIDR_COUNT found)"
      PASS=$((PASS + 1))
    else
      echo -e "  ${YELLOW}SKIP${NC}  LIDR records (0 records, data not loaded)"
      SKIP=$((SKIP + 1))
    fi
  fi
fi

# ══════════════════════════════════════════════════════════════════════
#  TIER 1: IkeGraphRAG — Scenario 2 (Four Devices)
# ══════════════════════════════════════════════════════════════════════
header "Tier 1 (IkeGraphRAG): Scenario 2 — Four Devices"

SCENARIO2_QUERIES=(
  "Panther Fusion SARS Flu RSV"
  "ARIES Flu RSV Assay"
  "Simplexa COVID-19 Flu"
  "Xpert Xpress Flu RSV"
)
SCENARIO2_FQNS=(
  "Panther Fusion"
  "ARIES"
  "Simplexa COVID-19"
  "Xpert Xpress Flu"
)
SCENARIO2_LABELS=(
  "Panther Fusion SARS-CoV-2/Flu A/B/RSV"
  "ARIES Flu A/B & RSV Assay"
  "Simplexa COVID-19 & Flu A/B Direct"
  "Xpert Xpress CoV-2/Flu/RSV plus"
)

for i in 0 1 2 3; do
  subheader "${SCENARIO2_LABELS[$i]}"
  grpc_search_device "${SCENARIO2_LABELS[$i]}" "${SCENARIO2_QUERIES[$i]}" "${SCENARIO2_FQNS[$i]}"
  if [ -n "$LAST_CONCEPT_ID" ]; then
    grpc_test_semantics "${SCENARIO2_LABELS[$i]}" "$LAST_CONCEPT_ID" \
      "Identifier Pattern" \
      "Description Pattern"
  fi
done

# ══════════════════════════════════════════════════════════════════════
#  TIER 1: IkeGraphRAG — Scenario 3 (Twelve Devices)
# ══════════════════════════════════════════════════════════════════════
header "Tier 1 (IkeGraphRAG): Scenario 3 — Twelve Devices"

SCENARIO3_QUERIES=(
  "Albumin Gen.2"
  "Panther Fusion SARS Flu RSV"
  "ARIES Flu RSV Assay"
  "Simplexa COVID-19 Flu"
  "Xpert Xpress Flu RSV"
  "Atellica IM Troponin"
  "i-STAT Troponin"
  "Architect Hemoglobin A1c"
  "VITROS HbA1c"
  "HER2 IQFISH"
  "RealTime IDH1"
  "UniCel DxH"
)
SCENARIO3_FQNS=(
  "Albumin Gen.2"
  "Panther Fusion"
  "ARIES"
  "Simplexa COVID-19"
  "Xpert"
  "Atellica IM"
  "i-STAT"
  "Architect"
  "VITROS"
  "HER2 IQFISH"
  "IDH1"
  "UniCel DxH"
)
SCENARIO3_LABELS=(
  "Roche COBAS Integra Albumin Gen.2"
  "Panther Fusion SARS-CoV-2/Flu A/B/RSV"
  "ARIES Flu A/B & RSV Assay"
  "Simplexa COVID-19 & Flu A/B Direct"
  "GeneXpert Xpert Xpress CoV-2/Flu/RSV"
  "Atellica IM High-Sensitivity Troponin I"
  "i-STAT hs-TnI Cartridge"
  "Architect Hemoglobin A1c"
  "VITROS Chemistry HbA1c"
  "HER2 IQFISH pharmDx Kit"
  "Abbott RealTime IDH1"
  "UniCel DxH hematology systems"
)

FOUND_COUNT=0
for i in "${!SCENARIO3_QUERIES[@]}"; do
  subheader "${SCENARIO3_LABELS[$i]}"
  grpc_search_device "${SCENARIO3_LABELS[$i]}" "${SCENARIO3_QUERIES[$i]}" "${SCENARIO3_FQNS[$i]}"
  if [ -n "$LAST_CONCEPT_ID" ]; then
    FOUND_COUNT=$((FOUND_COUNT + 1))
    grpc_test_semantics "${SCENARIO3_LABELS[$i]}" "$LAST_CONCEPT_ID" "Identifier Pattern"
  fi
done

subheader "Scenario 3 Summary"
echo -e "  Devices found: $FOUND_COUNT / ${#SCENARIO3_QUERIES[@]}"

# ══════════════════════════════════════════════════════════════════════
#  TIER 2: IkeKnowledgeGraph — Coordinate Override Scenarios
# ══════════════════════════════════════════════════════════════════════
header "Tier 2 (IkeKnowledgeGraph): Coordinate Override Scenarios"

# "Diabetes insipidus, NOS" — has many inactive semantics (9 of 14)
# Stamp times: 2002-01-30 (1012435200000), 2017-07-30 (1501459200000), 2024-01-31 (1706745600000)
COORD_TEST_ID="52d02a6d-eaad-57ff-9cd4-82fae97fb044"

# "Albumin (substance)" — has 2 different modules (6 SNOMED CT core + 2 SOLOR overlay)
MODULE_TEST_ID="02afcfce-19f6-536c-b331-9f17107e0858"
SNOMED_CT_CORE_MODULE="6b341bca-9c47-5e9e-83fb-9782c8fea56e"
SOLOR_OVERLAY_MODULE="9ecc154c-e490-5cf8-805d-d2865d62aef3"

# Path UUIDs
DEVELOPMENT_PATH="1f200ca6-960e-11e5-8994-feff819cdc9f"
SANDBOX_PATH="80710ea6-983c-5fa0-8908-e479f1f03ea9"

# Timestamp cutoffs for positionTime tests
TIME_BEFORE_ALL=946684800000       # 2000-01-01 — before any data
TIME_BEFORE_2017=1501459199999     # just before 2017-07-30 stamp
TIME_AT_2017=1501459200000         # exactly at 2017-07-30 stamp

KG_SVC="ai.ica.tinkar.IkeKnowledgeGraph/GetConceptSemantics"

  # ── allowedStates filtering ────────────────────────────────────────

  subheader "GetConceptSemantics — allowedStates (STAMP Coordinate)"

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID")"
  assert_grpc_ok "semantics (default coordinates)"
  assert_count_gt0 "semantics (default) returns results"
  DEFAULT_SEM_COUNT=$(json_total_count)

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID" '{"allowed_states":"ACTIVE_ONLY"}')"
  assert_grpc_ok "semantics (allowed_states=ACTIVE_ONLY)"
  ACTIVE_SEM_COUNT=$(json_total_count)

  # ACTIVE count must be less than DEFAULT
  TOTAL=$((TOTAL + 1))
  if [ "$ACTIVE_SEM_COUNT" -lt "$DEFAULT_SEM_COUNT" ]; then
    echo -e "  ${GREEN}PASS${NC}  ACTIVE ($ACTIVE_SEM_COUNT) < DEFAULT ($DEFAULT_SEM_COUNT) — inactive semantics filtered"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  ACTIVE ($ACTIVE_SEM_COUNT) should be less than DEFAULT ($DEFAULT_SEM_COUNT)"
    FAIL=$((FAIL + 1))
  fi

  # All results from ACTIVE query should have Active status
  TOTAL=$((TOTAL + 1))
  INACTIVE_IN_ACTIVE=$(echo "$RESPONSE" | python3 -c "
import json, sys
data = json.load(sys.stdin)
print(sum(1 for s in data.get('semantics', []) if s.get('stamp', {}).get('status') != 'Active'))
" 2>/dev/null || echo "-1")
  if [ "$INACTIVE_IN_ACTIVE" = "0" ]; then
    echo -e "  ${GREEN}PASS${NC}  All $ACTIVE_SEM_COUNT results have Active status"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  Found $INACTIVE_IN_ACTIVE non-Active semantics in ACTIVE query"
    FAIL=$((FAIL + 1))
  fi

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID" '{"allowed_states":"INACTIVE_ONLY"}')"
  assert_grpc_ok "semantics (allowed_states=INACTIVE_ONLY)"
  INACTIVE_SEM_COUNT=$(json_total_count)

  # ACTIVE + INACTIVE = DEFAULT
  TOTAL=$((TOTAL + 1))
  SUM=$((ACTIVE_SEM_COUNT + INACTIVE_SEM_COUNT))
  if [ "$SUM" = "$DEFAULT_SEM_COUNT" ]; then
    echo -e "  ${GREEN}PASS${NC}  ACTIVE ($ACTIVE_SEM_COUNT) + INACTIVE ($INACTIVE_SEM_COUNT) = DEFAULT ($DEFAULT_SEM_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  ACTIVE ($ACTIVE_SEM_COUNT) + INACTIVE ($INACTIVE_SEM_COUNT) = $SUM, expected $DEFAULT_SEM_COUNT"
    FAIL=$((FAIL + 1))
  fi

  # ── premiseType — hierarchy (children/descendants) ───────────────

  subheader "GetChildConcepts / GetDescendantConcepts — premise_type (Navigation Coordinate)"

  # "Disease (disorder)" — has 167 inferred children, 0 stated children
  HIERARCHY_TEST_ID="c3735e2d-9206-58bb-aa12-f92c4e5730a7"

  KG_CHILDREN="ai.ica.tinkar.IkeKnowledgeGraph/GetChildConcepts"
  KG_DESCENDANTS="ai.ica.tinkar.IkeKnowledgeGraph/GetDescendantConcepts"

  grpc_call "$KG_CHILDREN" "$(kg_request "$HIERARCHY_TEST_ID")"
  assert_grpc_ok "children (default/INFERRED)"
  CHILDREN_INFERRED=$(json_total_count)
  assert_count_gt0 "children (INFERRED) returns results"

  grpc_call "$KG_CHILDREN" "$(kg_request "$HIERARCHY_TEST_ID" '{"premise_type":"STATED"}')"
  assert_grpc_ok "children (STATED)"
  CHILDREN_STATED=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$CHILDREN_INFERRED" -gt "$CHILDREN_STATED" ]; then
    echo -e "  ${GREEN}PASS${NC}  INFERRED children ($CHILDREN_INFERRED) > STATED children ($CHILDREN_STATED) — premise_type has effect"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  INFERRED ($CHILDREN_INFERRED) should be > STATED ($CHILDREN_STATED)"
    FAIL=$((FAIL + 1))
  fi

  grpc_call "$KG_CHILDREN" "$(kg_request "$HIERARCHY_TEST_ID" '{"premise_type":"INFERRED"}')"
  assert_grpc_ok "children (explicit INFERRED)"
  CHILDREN_EXPLICIT_INF=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$CHILDREN_EXPLICIT_INF" = "$CHILDREN_INFERRED" ]; then
    echo -e "  ${GREEN}PASS${NC}  Explicit INFERRED ($CHILDREN_EXPLICIT_INF) = default ($CHILDREN_INFERRED)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  Explicit INFERRED ($CHILDREN_EXPLICIT_INF) should equal default ($CHILDREN_INFERRED)"
    FAIL=$((FAIL + 1))
  fi

  # Descendants: "Abscess (disorder)" — smaller subtree for descendants
  ABSCESS_TEST_ID="9728786a-1eb1-5553-892b-e0aad91bc034"

  grpc_call "$KG_DESCENDANTS" "$(kg_request "$ABSCESS_TEST_ID")"
  assert_grpc_ok "descendants (default/INFERRED)"
  DESC_INFERRED=$(json_total_count)
  assert_count_gt0 "descendants (INFERRED) returns results"

  grpc_call "$KG_DESCENDANTS" "$(kg_request "$ABSCESS_TEST_ID" '{"premise_type":"STATED"}')"
  assert_grpc_ok "descendants (STATED)"
  DESC_STATED=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$DESC_INFERRED" -gt "$DESC_STATED" ]; then
    echo -e "  ${GREEN}PASS${NC}  INFERRED descendants ($DESC_INFERRED) > STATED descendants ($DESC_STATED)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  INFERRED ($DESC_INFERRED) should be > STATED ($DESC_STATED)"
    FAIL=$((FAIL + 1))
  fi

  # ── Combined overrides ─────────────────────────────────────────────

  subheader "GetConceptSemantics — combined overrides"

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID" '{"allowed_states":"ACTIVE_ONLY","premise_type":"STATED"}')"
  assert_grpc_ok "semantics (ACTIVE + STATED)"
  COMBINED_COUNT=$(json_total_count)
  assert_count_gt0 "semantics (ACTIVE + STATED) returns results"

  TOTAL=$((TOTAL + 1))
  if [ "$COMBINED_COUNT" = "$ACTIVE_SEM_COUNT" ]; then
    echo -e "  ${GREEN}PASS${NC}  ACTIVE+STATED ($COMBINED_COUNT) matches ACTIVE ($ACTIVE_SEM_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  ACTIVE+STATED ($COMBINED_COUNT) differs from ACTIVE ($ACTIVE_SEM_COUNT)"
    FAIL=$((FAIL + 1))
  fi

  # ── positionTime overrides ────────────────────────────────────────

  subheader "GetConceptSemantics — position_time (STAMP Coordinate)"

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID")"
  TIME_DEFAULT_COUNT=$(json_total_count)

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID" "{\"position_time\":$TIME_BEFORE_ALL}")"
  assert_grpc_ok "semantics (position_time before 2002)"
  assert_count_eq "position_time before all data — 0 semantics" "0"

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID" "{\"position_time\":$TIME_BEFORE_2017}")"
  assert_grpc_ok "semantics (position_time before 2017)"
  TIME_PRE2017_COUNT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$TIME_PRE2017_COUNT" -lt "$TIME_DEFAULT_COUNT" ] && [ "$TIME_PRE2017_COUNT" -gt 0 ]; then
    echo -e "  ${GREEN}PASS${NC}  position_time pre-2017 ($TIME_PRE2017_COUNT) < latest ($TIME_DEFAULT_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  position_time pre-2017 ($TIME_PRE2017_COUNT) should be between 1 and $TIME_DEFAULT_COUNT"
    FAIL=$((FAIL + 1))
  fi

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID" "{\"position_time\":$TIME_AT_2017}")"
  assert_grpc_ok "semantics (position_time at 2017)"
  TIME_AT2017_COUNT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$TIME_AT2017_COUNT" -ge "$TIME_PRE2017_COUNT" ]; then
    echo -e "  ${GREEN}PASS${NC}  position_time at-2017 ($TIME_AT2017_COUNT) >= pre-2017 ($TIME_PRE2017_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  position_time at-2017 ($TIME_AT2017_COUNT) should be >= pre-2017 ($TIME_PRE2017_COUNT)"
    FAIL=$((FAIL + 1))
  fi

  # ── modules overrides ──────────────────────────────────────────────

  subheader "GetConceptSemantics — module_ids (STAMP Coordinate)"

  grpc_call "$KG_SVC" "$(kg_request "$MODULE_TEST_ID")"
  assert_grpc_ok "semantics for module test concept (default)"
  MOD_DEFAULT_COUNT=$(json_total_count)

  # Count distinct modules in default response
  MOD_DISTINCT=$(echo "$RESPONSE" | python3 -c "
import json, sys
data = json.load(sys.stdin)
modules = set(s.get('stamp',{}).get('module','') for s in data.get('semantics',[]) if s.get('stamp',{}).get('module'))
print(len(modules))
" 2>/dev/null || echo "0")

  TOTAL=$((TOTAL + 1))
  if [ "$MOD_DISTINCT" -ge 2 ]; then
    echo -e "  ${GREEN}PASS${NC}  Module test concept has $MOD_DISTINCT distinct modules"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  Expected >=2 distinct modules, found $MOD_DISTINCT"
    FAIL=$((FAIL + 1))
  fi

  grpc_call "$KG_SVC" "$(kg_request "$MODULE_TEST_ID" "{\"module_ids\":[\"$SNOMED_CT_CORE_MODULE\"]}")"
  assert_grpc_ok "semantics (module_ids=SNOMED CT core)"
  MOD_SNOMED_COUNT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$MOD_SNOMED_COUNT" -lt "$MOD_DEFAULT_COUNT" ] && [ "$MOD_SNOMED_COUNT" -gt 0 ]; then
    echo -e "  ${GREEN}PASS${NC}  SNOMED-only ($MOD_SNOMED_COUNT) < default ($MOD_DEFAULT_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  SNOMED-only ($MOD_SNOMED_COUNT) should be between 1 and $MOD_DEFAULT_COUNT"
    FAIL=$((FAIL + 1))
  fi

  grpc_call "$KG_SVC" "$(kg_request "$MODULE_TEST_ID" "{\"module_ids\":[\"$SOLOR_OVERLAY_MODULE\"]}")"
  assert_grpc_ok "semantics (module_ids=SOLOR overlay)"
  MOD_SOLOR_COUNT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$MOD_SOLOR_COUNT" -lt "$MOD_DEFAULT_COUNT" ] && [ "$MOD_SOLOR_COUNT" -gt 0 ]; then
    echo -e "  ${GREEN}PASS${NC}  SOLOR-only ($MOD_SOLOR_COUNT) < default ($MOD_DEFAULT_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  SOLOR-only ($MOD_SOLOR_COUNT) should be between 1 and $MOD_DEFAULT_COUNT"
    FAIL=$((FAIL + 1))
  fi

  MOD_SUM=$((MOD_SNOMED_COUNT + MOD_SOLOR_COUNT))
  TOTAL=$((TOTAL + 1))
  if [ "$MOD_SUM" = "$MOD_DEFAULT_COUNT" ]; then
    echo -e "  ${GREEN}PASS${NC}  SNOMED ($MOD_SNOMED_COUNT) + SOLOR ($MOD_SOLOR_COUNT) = default ($MOD_DEFAULT_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  SNOMED ($MOD_SNOMED_COUNT) + SOLOR ($MOD_SOLOR_COUNT) = $MOD_SUM, expected $MOD_DEFAULT_COUNT"
    FAIL=$((FAIL + 1))
  fi

  # ── positionPath overrides ─────────────────────────────────────────

  subheader "GetConceptSemantics — position_path_id (STAMP Coordinate)"

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID" "{\"position_path_id\":\"$DEVELOPMENT_PATH\"}")"
  assert_grpc_ok "semantics (position_path_id=Development)"
  PATH_DEV_COUNT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$PATH_DEV_COUNT" = "$TIME_DEFAULT_COUNT" ]; then
    echo -e "  ${GREEN}PASS${NC}  Explicit Development path ($PATH_DEV_COUNT) = default ($TIME_DEFAULT_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  Explicit Development path ($PATH_DEV_COUNT) should match default ($TIME_DEFAULT_COUNT)"
    FAIL=$((FAIL + 1))
  fi

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID" "{\"position_path_id\":\"$SANDBOX_PATH\"}")"
  assert_grpc_ok "semantics (position_path_id=Sandbox)"
  assert_count_eq "Sandbox path — 0 semantics (no data on this path)" "0"

  # ── Backward Compatibility ────────────────────────────────────────

  subheader "Backward Compatibility"

  # Verify default = explicit ACTIVE_AND_INACTIVE
  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID")"
  T2_NO_PARAMS=$(json_total_count)

  grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID" '{"allowed_states":"ACTIVE_AND_INACTIVE"}')"
  T2_EXPLICIT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$T2_NO_PARAMS" = "$T2_EXPLICIT" ]; then
    echo -e "  ${GREEN}PASS${NC}  No overrides ($T2_NO_PARAMS) = explicit ACTIVE_AND_INACTIVE ($T2_EXPLICIT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  No overrides ($T2_NO_PARAMS) differs from explicit ACTIVE_AND_INACTIVE ($T2_EXPLICIT)"
    FAIL=$((FAIL + 1))
  fi

  # Verify deprecated TinkarSearchService.GetConceptSemantics still works
  grpc_call "ai.ica.tinkar.TinkarSearchService/GetConceptSemantics" "$(pub_id "$COORD_TEST_ID")"
  assert_grpc_ok "TinkarSearchService.GetConceptSemantics (deprecated) responds"
  assert_count_gt0 "TinkarSearchService.GetConceptSemantics returns results"

# ══════════════════════════════════════════════════════════════════════
#  RPC METHOD COVERAGE
# ══════════════════════════════════════════════════════════════════════
header "RPC Method Coverage"

subheader "IkeGraphRAG (Tier 1)"

grpc_call "ai.ica.tinkar.IkeGraphRAG/Search" '{"query":"albumin"}'
assert_grpc_ok "Search"
assert_success "Search success"

grpc_call "ai.ica.tinkar.IkeGraphRAG/ConceptSearch" '{"query":"albumin","max_results":5}'
assert_grpc_ok "ConceptSearch"
assert_success "ConceptSearch success"

grpc_call "ai.ica.tinkar.IkeGraphRAG/ConceptSearchWithSort" '{"query":"albumin","max_results":5,"sort_by":"TOP_COMPONENT"}'
assert_grpc_ok "ConceptSearchWithSort"
assert_success "ConceptSearchWithSort success"

if [ -n "$ALBUMIN_ID" ]; then
  grpc_call "ai.ica.tinkar.IkeGraphRAG/GetEntity" "$(pub_id "$ALBUMIN_ID")"
  assert_grpc_ok "GetEntity"
  assert_success "GetEntity success"

  grpc_call "ai.ica.tinkar.IkeGraphRAG/GetChildConcepts" "$(pub_id "$ALBUMIN_ID")"
  assert_grpc_ok "GetChildConcepts"
  assert_success "GetChildConcepts success"

  grpc_call "ai.ica.tinkar.IkeGraphRAG/GetDescendantConcepts" "$(pub_id "$ALBUMIN_ID")"
  assert_grpc_ok "GetDescendantConcepts"
  assert_success "GetDescendantConcepts success"

  grpc_call "ai.ica.tinkar.IkeGraphRAG/GetLIDRRecordConceptsFromTestKit" "$(pub_id "$ALBUMIN_ID")"
  assert_grpc_ok "GetLIDRRecordConceptsFromTestKit"
fi

subheader "IkeKnowledgeGraph (Tier 2)"

grpc_call "$KG_SVC" "$(kg_request "$COORD_TEST_ID")"
assert_grpc_ok "GetConceptSemantics"
assert_count_gt0 "GetConceptSemantics returns results"

grpc_call "ai.ica.tinkar.IkeKnowledgeGraph/GetChildConcepts" "$(kg_request "$HIERARCHY_TEST_ID")"
assert_grpc_ok "GetChildConcepts (Tier 2)"
assert_count_gt0 "GetChildConcepts (Tier 2) returns results"

grpc_call "ai.ica.tinkar.IkeKnowledgeGraph/GetDescendantConcepts" "$(kg_request "$ABSCESS_TEST_ID")"
assert_grpc_ok "GetDescendantConcepts (Tier 2)"
assert_count_gt0 "GetDescendantConcepts (Tier 2) returns results"

subheader "TinkarSearchService (Deprecated)"

grpc_call "ai.ica.tinkar.TinkarSearchService/Search" '{"query":"albumin"}'
assert_grpc_ok "Search (deprecated)"
assert_success "Search (deprecated) success"

grpc_call "ai.ica.tinkar.TinkarSearchService/ConceptSearch" '{"query":"albumin","max_results":5}'
assert_grpc_ok "ConceptSearch (deprecated)"
assert_success "ConceptSearch (deprecated) success"

grpc_call "ai.ica.tinkar.TinkarSearchService/GetConceptSemantics" "$(pub_id "$COORD_TEST_ID")"
assert_grpc_ok "GetConceptSemantics (deprecated)"
assert_count_gt0 "GetConceptSemantics (deprecated) returns results"

# ══════════════════════════════════════════════════════════════════════
#  FINAL REPORT
# ══════════════════════════════════════════════════════════════════════
header "Test Results"

echo ""
echo -e "  ${GREEN}PASS: $PASS${NC}"
echo -e "  ${RED}FAIL: $FAIL${NC}"
echo -e "  ${YELLOW}SKIP: $SKIP${NC}"
echo -e "  ${BOLD}TOTAL: $TOTAL${NC}"
echo ""

if [ "$FAIL" -gt 0 ]; then
  echo -e "${BOLD}${RED}  Known gaps:${NC}"
  echo "  1. LIDR Record pattern (DIAGNOSTIC_DEVICE_PATTERN) not in dataset"
  echo "  2. Stated taxonomy empty in this dataset — STATED premise_type returns 0"
  echo "     hierarchy results (this is expected, demonstrates STATED vs INFERRED difference)"
  echo ""
fi

exit "$FAIL"
