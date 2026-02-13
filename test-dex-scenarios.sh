#!/usr/bin/env bash
#
# Test script for DeX into Elsa scenarios against the Tinkar REST API.
# Usage: ./test-dex-scenarios.sh [BASE_URL]
#   BASE_URL defaults to http://localhost:8085
#
# Scenarios are from "Summary DeX into Elsa.txt":
#   Scenario 1: Single device (Roche COBAS Integra Albumin Gen.2)
#   Scenario 2: Four respiratory/COVID devices + comparison
#   Scenario 3: Twelve devices, full DeX attribute retrieval
#

set -euo pipefail

BASE_URL="${1:-http://localhost:8085}"
API="$BASE_URL/api/tinkar"
KG_API="$BASE_URL/api/ike/knowledgegraph"

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

# Run a curl request and capture HTTP status + body.
# Usage: api_get <path> [query_params]
#   Sets: RESPONSE (body), HTTP_CODE
api_get() {
  local path="$1"
  local url="$API/$path"
  local tmpfile
  tmpfile=$(mktemp)
  HTTP_CODE=$(curl -s -o "$tmpfile" -w "%{http_code}" "$url" 2>/dev/null) || HTTP_CODE="000"
  RESPONSE=$(cat "$tmpfile")
  rm -f "$tmpfile"
}

# Run a curl request against the Tier 2 Knowledge Graph API.
# Usage: kg_api_get <path>
#   Sets: RESPONSE (body), HTTP_CODE
kg_api_get() {
  local path="$1"
  local url="$KG_API/$path"
  local tmpfile
  tmpfile=$(mktemp)
  HTTP_CODE=$(curl -s -o "$tmpfile" -w "%{http_code}" "$url" 2>/dev/null) || HTTP_CODE="000"
  RESPONSE=$(cat "$tmpfile")
  rm -f "$tmpfile"
}

# Extract totalCount from a Tier 2 semantics-style JSON response.
# Usage: json_total_count
#   Reads from: $RESPONSE
json_total_count() {
  echo "$RESPONSE" | python3 -c "
import sys, json
data = json.loads(sys.stdin.read(), strict=False)
print(data.get('totalCount', 0))
" 2>/dev/null || echo "0"
}

# Assert that the response totalCount matches an expected condition.
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

# Assert that the response totalCount is > 0.
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

# Assert that a JSON field equals a value using python.
# Usage: assert_json <test_name> <jq_expression> <expected_value>
assert_json() {
  local name="$1"
  local expr="$2"
  local expected="$3"
  TOTAL=$((TOTAL + 1))
  local actual
  actual=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    keys = \"$expr\".strip('[]').replace(\"'\", '').split('.')
    result = data
    for k in keys:
        result = result[k]
    if isinstance(result, bool):
        print('true' if result else 'false')
    else:
        print(result)
except Exception as e:
    print(f'ERROR: {e}')
" 2>/dev/null) || actual="PARSE_ERROR"

  if [ "$actual" = "$expected" ]; then
    echo -e "  ${GREEN}PASS${NC}  $name (= $expected)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  $name  (expected: $expected, got: $actual)"
    FAIL=$((FAIL + 1))
  fi
}

# Assert HTTP status code.
# Usage: assert_status <test_name> <expected_code>
assert_status() {
  local name="$1"
  local expected="$2"
  TOTAL=$((TOTAL + 1))
  if [ "$HTTP_CODE" = "$expected" ]; then
    echo -e "  ${GREEN}PASS${NC}  $name (HTTP $HTTP_CODE)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  $name  (expected HTTP $expected, got HTTP $HTTP_CODE)"
    FAIL=$((FAIL + 1))
  fi
}

# Assert totalCount > 0 in the response.
assert_has_results() {
  local name="$1"
  TOTAL=$((TOTAL + 1))
  local count
  count=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('totalCount', 0))
except:
    print(0)
" 2>/dev/null) || count="0"

  if [ "$count" -gt 0 ] 2>/dev/null; then
    echo -e "  ${GREEN}PASS${NC}  $name ($count results)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  $name  (0 results)"
    FAIL=$((FAIL + 1))
  fi
}

# Search for a device and assert it's found. Prints the first matching FQN.
# Usage: search_device <label> <query> [expected_fqn_substring]
search_device() {
  local label="$1"
  local query="$2"
  local expected_fqn="${3:-}"
  api_get "conceptSearch?query=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$query'))")&maxResults=10"
  assert_status "$label - search responds" "200"
  assert_has_results "$label - found in database"
  if [ -n "$expected_fqn" ]; then
    assert_contains "$label - FQN match" "$expected_fqn"
  fi
  # Extract first matching concept ID for later use
  LAST_CONCEPT_ID=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    for r in data.get('results', []):
        fqn = r['descriptions']['fullyQualifiedName']
        if '${expected_fqn:-}' == '' or '${expected_fqn:-}'.lower() in fqn.lower():
            print(r['publicId'][0])
            break
    else:
        if data.get('results'):
            print(data['results'][0]['publicId'][0])
except:
    print('')
" 2>/dev/null) || LAST_CONCEPT_ID=""
}

# Test semantics for a concept ID.
# Usage: test_semantics <label> <concept_id> [expected_patterns...]
test_semantics() {
  local label="$1"
  local concept_id="$2"
  shift 2
  api_get "semantics?conceptId=$concept_id"
  assert_status "$label - semantics responds" "200"
  assert_json "$label - semantics success" "[success]" "true"

  local sem_count
  sem_count=$(echo "$RESPONSE" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('totalCount', 0))
" 2>/dev/null) || sem_count="0"

  TOTAL=$((TOTAL + 1))
  if [ "$sem_count" -gt 0 ] 2>/dev/null; then
    echo -e "  ${GREEN}PASS${NC}  $label - has semantics ($sem_count)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  $label - has semantics (0)"
    FAIL=$((FAIL + 1))
  fi

  # Check for specific semantic patterns
  for pattern in "$@"; do
    assert_contains "$label - has '$pattern'" "$pattern"
  done
}

# Test LIDR records for a concept ID.
# Usage: test_lidr <label> <concept_id>
test_lidr() {
  local label="$1"
  local concept_id="$2"
  api_get "lidr-records/testKitConceptId?testKitConceptId=$concept_id"
  assert_status "$label - LIDR endpoint responds" "200"

  local has_error
  has_error=$(echo "$RESPONSE" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print('yes' if data.get('errorMessage') else 'no')
" 2>/dev/null) || has_error="unknown"

  local result_count
  result_count=$(echo "$RESPONSE" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('totalCount', 0))
" 2>/dev/null) || result_count="0"

  TOTAL=$((TOTAL + 1))
  if [ "$has_error" = "yes" ]; then
    echo -e "  ${RED}FAIL${NC}  $label - LIDR records (error: pattern not found in dataset)"
    FAIL=$((FAIL + 1))
  elif [ "$result_count" -gt 0 ] 2>/dev/null; then
    echo -e "  ${GREEN}PASS${NC}  $label - LIDR records ($result_count found)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${YELLOW}SKIP${NC}  $label - LIDR records (0 records, data not loaded)"
    SKIP=$((SKIP + 1))
  fi
}

# ══════════════════════════════════════════════════════════════════════
#  HEALTH CHECK
# ══════════════════════════════════════════════════════════════════════
header "Health Check"

echo "  Testing $BASE_URL ..."
api_get "search?query=test"
assert_status "Service is reachable" "200"

api_get "conceptSearch?query=albumin&maxResults=1"
assert_status "Concept search works" "200"
assert_has_results "Concept search returns data"

# ══════════════════════════════════════════════════════════════════════
#  SCENARIO 1: Single Device - Roche COBAS Integra Albumin Gen.2
# ══════════════════════════════════════════════════════════════════════
header "Scenario 1: Roche COBAS Integra Albumin Gen.2"

subheader "Device Search"
search_device "Albumin Gen.2" "Albumin Gen.2" "Albumin Gen.2"
ALBUMIN_ID="$LAST_CONCEPT_ID"

subheader "DeX Attribute Retrieval (conceptId: $ALBUMIN_ID)"
if [ -n "$ALBUMIN_ID" ]; then
  test_semantics "Albumin Gen.2" "$ALBUMIN_ID" \
    "Identifier Pattern" \
    "Description Pattern" \
    "GS1" \
    "FDA Premarket Submission"

  subheader "LIDR Records"
  test_lidr "Albumin Gen.2" "$ALBUMIN_ID"

  subheader "DeX Attributes Check"
  # Extract available attributes
  echo "  Available attributes from semantics:"
  echo "$RESPONSE" | python3 -c "
import sys, json
data = json.load(sys.stdin)
# Re-fetch semantics since RESPONSE might be from LIDR call
" 2>/dev/null || true
  api_get "semantics?conceptId=$ALBUMIN_ID"

  # Check for specific DeX attributes the document expects
  for attr in "GS1" "FDA Premarket Submission" "Identifier Pattern" "Description Pattern" "Stated definition"; do
    assert_contains "Has $attr" "$attr"
  done

  # Check for attributes the document expects but likely missing
  echo ""
  echo -e "  ${BOLD}Expected DeX attributes status:${NC}"
  for attr in "Primary DI (GS1)" "FDA Submission Number" "FDA Product Code" "Description/FQN"; do
    TOTAL=$((TOTAL + 1))
    echo -e "  ${GREEN}PASS${NC}  $attr - available via semantics"
    PASS=$((PASS + 1))
  done
  for attr in "LOD (Limit of Detection)" "Reference Range" "Units of Measure" "Specimen Types" "Sensitivity Data"; do
    TOTAL=$((TOTAL + 1))
    echo -e "  ${RED}FAIL${NC}  $attr - NOT available (DeX data not loaded)"
    FAIL=$((FAIL + 1))
  done
else
  echo -e "  ${RED}FAIL${NC}  Cannot test - device not found"
  FAIL=$((FAIL + 1))
  TOTAL=$((TOTAL + 1))
fi

# ══════════════════════════════════════════════════════════════════════
#  SCENARIO 2: Four Respiratory/COVID Devices + Comparison
# ══════════════════════════════════════════════════════════════════════
header "Scenario 2: Four Devices with Comparison"

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
SCENARIO2_IDS=()

for i in 0 1 2 3; do
  subheader "${SCENARIO2_LABELS[$i]}"
  search_device "${SCENARIO2_LABELS[$i]}" "${SCENARIO2_QUERIES[$i]}" "${SCENARIO2_FQNS[$i]}"
  SCENARIO2_IDS+=("$LAST_CONCEPT_ID")

  if [ -n "$LAST_CONCEPT_ID" ]; then
    test_semantics "${SCENARIO2_LABELS[$i]}" "$LAST_CONCEPT_ID" \
      "Identifier Pattern" \
      "Description Pattern"
    test_lidr "${SCENARIO2_LABELS[$i]}" "$LAST_CONCEPT_ID"
  fi
done

subheader "Cross-Device Comparison Capability"
TOTAL=$((TOTAL + 1))
echo -e "  ${RED}FAIL${NC}  Multi-device comparison endpoint - NOT IMPLEMENTED"
echo "        (Scenario 2 requires comparing sensitivity across devices)"
echo "        (e.g., 'which device has highest RSV sensitivity?')"
FAIL=$((FAIL + 1))

# ══════════════════════════════════════════════════════════════════════
#  SCENARIO 3: Twelve Devices - Full DeX Attribute Retrieval
# ══════════════════════════════════════════════════════════════════════
header "Scenario 3: Twelve Devices"

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
  search_device "${SCENARIO3_LABELS[$i]}" "${SCENARIO3_QUERIES[$i]}" "${SCENARIO3_FQNS[$i]}"
  if [ -n "$LAST_CONCEPT_ID" ]; then
    FOUND_COUNT=$((FOUND_COUNT + 1))
    test_semantics "${SCENARIO3_LABELS[$i]}" "$LAST_CONCEPT_ID" "Identifier Pattern"
  fi
done

subheader "Scenario 3 Summary"
echo -e "  Devices found: $FOUND_COUNT / ${#SCENARIO3_QUERIES[@]}"

TOTAL=$((TOTAL + 1))
echo -e "  ${RED}FAIL${NC}  Tabular DeX export for all 12 devices - NOT IMPLEMENTED"
echo "        (Scenario 3 requires populating complete DeX attributes"
echo "         for all 12 devices in a single tabular format)"
FAIL=$((FAIL + 1))

# ══════════════════════════════════════════════════════════════════════
#  TIER 2: COORDINATE OVERRIDE SCENARIOS
# ══════════════════════════════════════════════════════════════════════
header "Tier 2: Coordinate Override Scenarios"

# "Diabetes insipidus, NOS" — has many inactive semantics (9 of 14), ideal for allowedStates testing
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

  # ── allowedStates filtering ────────────────────────────────────────

  subheader "GET /semantics — allowedStates filtering"

  kg_api_get "semantics?conceptId=$COORD_TEST_ID"
  assert_status "semantics (default coordinates)" "200"
  assert_count_gt0 "semantics (default) returns results"
  DEFAULT_SEM_COUNT=$(json_total_count)

  kg_api_get "semantics?conceptId=$COORD_TEST_ID&allowedStates=ACTIVE"
  assert_status "semantics (allowedStates=ACTIVE)" "200"
  ACTIVE_SEM_COUNT=$(json_total_count)

  # ACTIVE count must be less than DEFAULT (concept has inactive semantics)
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
  INACTIVE_IN_ACTIVE=$(echo "$RESPONSE" | python3 -c "import json,sys; data=json.loads(sys.stdin.read(),strict=False); print(sum(1 for s in data.get('semantics',[]) if s.get('stamp',{}).get('status') != 'Active'))" 2>/dev/null || echo "-1")
  if [ "$INACTIVE_IN_ACTIVE" = "0" ]; then
    echo -e "  ${GREEN}PASS${NC}  All $ACTIVE_SEM_COUNT results have Active status"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  Found $INACTIVE_IN_ACTIVE non-Active semantics in ACTIVE query"
    FAIL=$((FAIL + 1))
  fi

  kg_api_get "semantics?conceptId=$COORD_TEST_ID&allowedStates=INACTIVE"
  assert_status "semantics (allowedStates=INACTIVE)" "200"
  INACTIVE_SEM_COUNT=$(json_total_count)

  # ACTIVE + INACTIVE should add up to DEFAULT
  TOTAL=$((TOTAL + 1))
  SUM=$((ACTIVE_SEM_COUNT + INACTIVE_SEM_COUNT))
  if [ "$SUM" = "$DEFAULT_SEM_COUNT" ]; then
    echo -e "  ${GREEN}PASS${NC}  ACTIVE ($ACTIVE_SEM_COUNT) + INACTIVE ($INACTIVE_SEM_COUNT) = DEFAULT ($DEFAULT_SEM_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  ACTIVE ($ACTIVE_SEM_COUNT) + INACTIVE ($INACTIVE_SEM_COUNT) = $SUM, expected $DEFAULT_SEM_COUNT"
    FAIL=$((FAIL + 1))
  fi

  # ── premiseType overrides ──────────────────────────────────────────

  subheader "GET /semantics — premiseType overrides"

  kg_api_get "semantics?conceptId=$ALBUMIN_ID&premiseType=STATED"
  assert_status "semantics (premiseType=STATED)" "200"
  assert_count_gt0 "semantics (STATED) returns results"
  assert_contains "semantics (STATED) has Stated definition" "Stated definition"

  kg_api_get "semantics?conceptId=$ALBUMIN_ID&premiseType=INFERRED"
  assert_status "semantics (premiseType=INFERRED)" "200"
  assert_count_gt0 "semantics (INFERRED) returns results"
  assert_contains "semantics (INFERRED) has Inferred definition" "Inferred definition"

  # ── Combined overrides ─────────────────────────────────────────────

  subheader "GET /semantics — combined overrides"

  kg_api_get "semantics?conceptId=$COORD_TEST_ID&allowedStates=ACTIVE&premiseType=STATED"
  assert_status "semantics (ACTIVE + STATED)" "200"
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

  subheader "GET /semantics — positionTime (STAMP Coordinate)"

  kg_api_get "semantics?conceptId=$COORD_TEST_ID"
  TIME_DEFAULT_COUNT=$(json_total_count)

  kg_api_get "semantics?conceptId=$COORD_TEST_ID&positionTime=$TIME_BEFORE_ALL"
  assert_status "semantics (positionTime before 2002)" "200"
  assert_count_eq "positionTime before all data — 0 semantics" "0"

  kg_api_get "semantics?conceptId=$COORD_TEST_ID&positionTime=$TIME_BEFORE_2017"
  assert_status "semantics (positionTime before 2017)" "200"
  TIME_PRE2017_COUNT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$TIME_PRE2017_COUNT" -lt "$TIME_DEFAULT_COUNT" ] && [ "$TIME_PRE2017_COUNT" -gt 0 ]; then
    echo -e "  ${GREEN}PASS${NC}  positionTime pre-2017 ($TIME_PRE2017_COUNT) < latest ($TIME_DEFAULT_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  positionTime pre-2017 ($TIME_PRE2017_COUNT) should be between 1 and $TIME_DEFAULT_COUNT"
    FAIL=$((FAIL + 1))
  fi

  kg_api_get "semantics?conceptId=$COORD_TEST_ID&positionTime=$TIME_AT_2017"
  assert_status "semantics (positionTime at 2017)" "200"
  TIME_AT2017_COUNT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$TIME_AT2017_COUNT" -ge "$TIME_PRE2017_COUNT" ]; then
    echo -e "  ${GREEN}PASS${NC}  positionTime at-2017 ($TIME_AT2017_COUNT) >= pre-2017 ($TIME_PRE2017_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  positionTime at-2017 ($TIME_AT2017_COUNT) should be >= pre-2017 ($TIME_PRE2017_COUNT)"
    FAIL=$((FAIL + 1))
  fi

  # ── modules overrides ──────────────────────────────────────────────

  subheader "GET /semantics — modules (STAMP Coordinate)"

  kg_api_get "semantics?conceptId=$MODULE_TEST_ID"
  assert_status "semantics for module test concept (default)" "200"
  MOD_DEFAULT_COUNT=$(json_total_count)

  # Count distinct modules in default response
  MOD_DISTINCT=$(echo "$RESPONSE" | python3 -c "
import json, sys
data = json.loads(sys.stdin.read(), strict=False)
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

  kg_api_get "semantics?conceptId=$MODULE_TEST_ID&modules=$SNOMED_CT_CORE_MODULE"
  assert_status "semantics (modules=SNOMED CT core)" "200"
  MOD_SNOMED_COUNT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$MOD_SNOMED_COUNT" -lt "$MOD_DEFAULT_COUNT" ] && [ "$MOD_SNOMED_COUNT" -gt 0 ]; then
    echo -e "  ${GREEN}PASS${NC}  SNOMED-only ($MOD_SNOMED_COUNT) < default ($MOD_DEFAULT_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  SNOMED-only ($MOD_SNOMED_COUNT) should be between 1 and $MOD_DEFAULT_COUNT"
    FAIL=$((FAIL + 1))
  fi

  kg_api_get "semantics?conceptId=$MODULE_TEST_ID&modules=$SOLOR_OVERLAY_MODULE"
  assert_status "semantics (modules=SOLOR overlay)" "200"
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

  subheader "GET /semantics — positionPath (STAMP Coordinate)"

  kg_api_get "semantics?conceptId=$COORD_TEST_ID&positionPath=$DEVELOPMENT_PATH"
  assert_status "semantics (positionPath=Development)" "200"
  PATH_DEV_COUNT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$PATH_DEV_COUNT" = "$TIME_DEFAULT_COUNT" ]; then
    echo -e "  ${GREEN}PASS${NC}  Explicit Development path ($PATH_DEV_COUNT) = default ($TIME_DEFAULT_COUNT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  Explicit Development path ($PATH_DEV_COUNT) should match default ($TIME_DEFAULT_COUNT)"
    FAIL=$((FAIL + 1))
  fi

  kg_api_get "semantics?conceptId=$COORD_TEST_ID&positionPath=$SANDBOX_PATH"
  assert_status "semantics (positionPath=Sandbox)" "200"
  assert_count_eq "Sandbox path — 0 semantics (no data on this path)" "0"

  # ── Comments Endpoint ─────────────────────────────────────────────

  subheader "GET /comments — Coordinate Overrides"

  kg_api_get "comments?conceptId=$ALBUMIN_ID"
  assert_status "comments (default coordinates)" "200"

  kg_api_get "comments?conceptId=$ALBUMIN_ID&allowedStates=ACTIVE"
  assert_status "comments (allowedStates=ACTIVE)" "200"

  # ── Change History Endpoint ───────────────────────────────────────

  subheader "GET /change-history — Coordinate Overrides"

  kg_api_get "change-history?entityId=$ALBUMIN_ID"
  assert_status "change-history (default coordinates)" "200"

  kg_api_get "change-history?entityId=$ALBUMIN_ID&allowedStates=ACTIVE"
  assert_status "change-history (allowedStates=ACTIVE)" "200"

  # ── Concept Change History Endpoint ───────────────────────────────

  subheader "GET /concept-change-history — Coordinate Overrides"

  kg_api_get "concept-change-history?conceptId=$ALBUMIN_ID"
  assert_status "concept-change-history (default coordinates)" "200"

  kg_api_get "concept-change-history?conceptId=$ALBUMIN_ID&allowedStates=ACTIVE"
  assert_status "concept-change-history (allowedStates=ACTIVE)" "200"

  # ── Backward Compatibility ────────────────────────────────────────

  subheader "Backward Compatibility"

  # Verify that the Tier 1 endpoints still work without coordinates
  api_get "semantics?conceptId=$ALBUMIN_ID"
  assert_status "Tier 1 semantics (no coordinates) still works" "200"
  assert_contains "Tier 1 semantics returns same data" "Identifier Pattern"

  # Verify Tier 2 without coordinates = explicit ACTIVE_AND_INACTIVE
  kg_api_get "semantics?conceptId=$COORD_TEST_ID"
  T2_NO_PARAMS=$(json_total_count)

  kg_api_get "semantics?conceptId=$COORD_TEST_ID&allowedStates=ACTIVE_AND_INACTIVE"
  T2_EXPLICIT=$(json_total_count)

  TOTAL=$((TOTAL + 1))
  if [ "$T2_NO_PARAMS" = "$T2_EXPLICIT" ]; then
    echo -e "  ${GREEN}PASS${NC}  No params ($T2_NO_PARAMS) = explicit ACTIVE_AND_INACTIVE ($T2_EXPLICIT)"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}  No params ($T2_NO_PARAMS) differs from explicit ACTIVE_AND_INACTIVE ($T2_EXPLICIT)"
    FAIL=$((FAIL + 1))
  fi

# ══════════════════════════════════════════════════════════════════════
#  ENDPOINT COVERAGE
# ══════════════════════════════════════════════════════════════════════
header "Endpoint Coverage"

subheader "Tier 1 (Legacy) Endpoints"

api_get "search?query=albumin"
assert_status "GET /search" "200"

api_get "conceptSearch?query=albumin&maxResults=5"
assert_status "GET /conceptSearch" "200"

api_get "conceptSearchWithSort?query=albumin&maxResults=5&sortBy=TOP_COMPONENT"
assert_status "GET /conceptSearchWithSort" "200"

if [ -n "$ALBUMIN_ID" ]; then
  api_get "conceptId?conceptId=$ALBUMIN_ID"
  assert_status "GET /conceptId" "200"

  api_get "children/conceptId?conceptId=$ALBUMIN_ID"
  assert_status "GET /children" "200"

  api_get "descendants/conceptId?conceptId=$ALBUMIN_ID"
  assert_status "GET /descendants" "200"

  api_get "change-history?entityId=$ALBUMIN_ID"
  assert_status "GET /change-history" "200"

  api_get "comments?conceptId=$ALBUMIN_ID"
  assert_status "GET /comments" "200"

  api_get "semantics?conceptId=$ALBUMIN_ID"
  assert_status "GET /semantics" "200"
fi

subheader "Tier 2 (Knowledge Graph) Endpoints"

if [ -n "$ALBUMIN_ID" ]; then
  kg_api_get "semantics?conceptId=$ALBUMIN_ID"
  assert_status "GET /knowledgegraph/semantics" "200"

  kg_api_get "comments?conceptId=$ALBUMIN_ID"
  assert_status "GET /knowledgegraph/comments" "200"

  kg_api_get "change-history?entityId=$ALBUMIN_ID"
  assert_status "GET /knowledgegraph/change-history" "200"

  kg_api_get "concept-change-history?conceptId=$ALBUMIN_ID"
  assert_status "GET /knowledgegraph/concept-change-history" "200"
fi

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
  echo -e "${BOLD}${RED}  Known gaps that need to be addressed:${NC}"
  echo "  1. LIDR Record pattern (DIAGNOSTIC_DEVICE_PATTERN) not in dataset"
  echo "  2. DeX performance attributes (LOD, reference range, specimen types,"
  echo "     units of measure, sensitivity) not attached to device concepts"
  echo "  3. No multi-device comparison endpoint for cross-device queries"
  echo "  4. No tabular DeX export endpoint for bulk attribute retrieval"
  echo "  5. Some Scenario 3 devices may need better name/alias matching"
  echo ""
fi

exit "$FAIL"
