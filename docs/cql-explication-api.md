# CQL Explication API — Design Notes

## The Problem This Solves

Today, every CQL measure author who needs "morbid obesity" writes the same four or-clauses
by hand — the coded SNOMED path, the BMI threshold, the recomputed height/weight BMI, and
the lower-threshold-with-comorbidity conditional. Each copy diverges silently: one measure
uses 40 kg/m², another quietly uses 35 everywhere. None of them are wrong CQL. They just
have nowhere shared to live, so the same work is paid again for each new measure.

The Kompendium fixes this by storing the explication once, on the concept itself. The measure
references a single clause (`"BMI determination".result overlaps "Morbid obesity"`); the
Kompendium holds the definition. When the guideline changes, one edit propagates everywhere.

This document describes how tinkar-service exposes that stored explication — and what a CQL
editor needs to connect to it.

---

## Tinkar Storage Model

CQL explicitations live as **semantics** on the concept, using a dedicated pattern:
`CQL_EXPLICATION_PATTERN`. Each or-clause is its own semantic instance so that clauses can
be individually STAMP-versioned, governed, and replaced without touching the others.

```
Concept: Morbid obesity (SNOMED 238136002)
  │
  ├── Semantic [pattern=CQL_EXPLICATION_PATTERN]
  │     REPRESENTATION_PATH  → CQL_CODED_PATH
  │     CQL_CLAUSE_TEXT       → exists [Condition: "Morbid obesity (SNOMED 238136002)"]
  │     CQL_AST_JSON          → { "type": "ExistsExpression", ... }
  │     DESCRIPTION           → "coded path: the code carries no threshold"
  │
  ├── Semantic [pattern=CQL_EXPLICATION_PATTERN]
  │     REPRESENTATION_PATH  → CQL_BMI_VALUE
  │     CQL_CLAUSE_TEXT       → "Latest BMI".value >= 40 'kg/m2'
  │     CQL_AST_JSON          → { "type": "ComparisonExpression", ... }
  │     THRESHOLD_VALUE       → 40.0
  │     THRESHOLD_UNIT        → kg/m2
  │     DESCRIPTION           → "BMI value: cutoff defined in the Kompendium"
  │
  ├── Semantic [pattern=CQL_EXPLICATION_PATTERN]
  │     REPRESENTATION_PATH  → CQL_HEIGHT_WEIGHT
  │     CQL_CLAUSE_TEXT       → ("Latest Weight" / ("Latest Height" * "Latest Height")) >= 40
  │     CQL_AST_JSON          → { "type": "ComparisonExpression", ... }
  │     THRESHOLD_VALUE       → 40.0
  │     THRESHOLD_UNIT        → kg/m2
  │     DESCRIPTION           → "height + weight: BMI recomputed by the Kompendium"
  │
  └── Semantic [pattern=CQL_EXPLICATION_PATTERN]
        REPRESENTATION_PATH  → CQL_CONDITIONAL
        CQL_CLAUSE_TEXT       → "Latest BMI".value >= 35 'kg/m2'
                                  and exists [Condition: "Obesity-related comorbidity"]
        CQL_AST_JSON          → { "type": "AndExpression", ... }
        THRESHOLD_VALUE       → 35.0
        THRESHOLD_UNIT        → kg/m2
        SECONDARY_CONCEPT_ID  → <uuid of "Obesity-related comorbidity">
        DESCRIPTION           → "lower threshold with qualifying comorbidity"
```

Each semantic carries a full STAMP coordinate, so a measure can pin to the version of the
explication that existed when it was authored, and a governance workflow can track what
changed between versions.

---

## AST / Property Graph Structure

The `CQL_AST_JSON` field carries an ELM-compatible (Expression Logical Model) property
graph. This is what a CQL editor works with programmatically — not the text, which it can
reconstruct, but the node graph, which it can validate, compare, and manipulate.

### Coded-path clause

```json
{
  "type": "ExistsExpression",
  "operand": {
    "type": "Retrieve",
    "dataType": "Condition",
    "codeProperty": "code",
    "valueset": {
      "name": "Morbid obesity (SNOMED 238136002)",
      "conceptId": "5c40ced2-a372-5da5-8c93-f9ceb2bc0caf"
    }
  }
}
```

### BMI-value clause

```json
{
  "type": "ComparisonExpression",
  "operator": "greaterOrEqual",
  "left": {
    "type": "Property",
    "source": { "type": "ExpressionRef", "name": "Latest BMI" },
    "path": "value"
  },
  "right": {
    "type": "Quantity",
    "value": 40,
    "unit": "kg/m2"
  }
}
```

### Height-weight clause

```json
{
  "type": "ComparisonExpression",
  "operator": "greaterOrEqual",
  "left": {
    "type": "Divide",
    "left": { "type": "ExpressionRef", "name": "Latest Weight" },
    "right": {
      "type": "Multiply",
      "left": { "type": "ExpressionRef", "name": "Latest Height" },
      "right": { "type": "ExpressionRef", "name": "Latest Height" }
    }
  },
  "right": { "type": "Quantity", "value": 40, "unit": "kg/m2" }
}
```

### Conditional clause (lower threshold + comorbidity)

```json
{
  "type": "AndExpression",
  "left": {
    "type": "ComparisonExpression",
    "operator": "greaterOrEqual",
    "left": {
      "type": "Property",
      "source": { "type": "ExpressionRef", "name": "Latest BMI" },
      "path": "value"
    },
    "right": { "type": "Quantity", "value": 35, "unit": "kg/m2" }
  },
  "right": {
    "type": "ExistsExpression",
    "operand": {
      "type": "Retrieve",
      "dataType": "Condition",
      "valueset": {
        "name": "Obesity-related comorbidity",
        "conceptId": "<uuid>"
      }
    }
  }
}
```

---

## Proto Extensions (ike_knowledge_graph.proto)

Add to the existing `IkeKnowledgeGraph` service:

```protobuf
// Which evidence pathway this clause covers
enum CqlRepresentationPath {
  CODED_PATH    = 0;  // Terminology code carries the assertion directly
  BMI_VALUE     = 1;  // Numeric BMI observation + threshold
  HEIGHT_WEIGHT = 2;  // BMI recomputed from height + weight
  CONDITIONAL   = 3;  // Lower threshold with qualifying comorbidity condition
}

// One or-clause in the concept's CQL explication
message CqlClause {
  CqlRepresentationPath path               = 1;
  string                cql_text           = 2;  // Renderable CQL fragment
  string                ast_json           = 3;  // ELM-compatible JSON AST
  double                threshold_value    = 4;  // e.g. 40.0 or 35.0
  string                threshold_unit     = 5;  // e.g. "kg/m2"
  repeated string       related_concept_ids = 6; // Supporting concept UUIDs
  string                description        = 7;  // Author annotation
  string                stamp_version      = 8;  // STAMP provenance for this clause
}

// Full CQL explication for a concept
message ConceptCqlResponse {
  dev.ikm.tinkar.schema.PublicId concept_id     = 1;
  string                          concept_label  = 2;  // "Morbid obesity"
  string                          source_sctid   = 3;  // "238136002"
  string                          compact_clause = 4;
    // The clause a measure references:
    // "BMI determination".result overlaps "Morbid obesity"
  string                          expanded_cql   = 5;
    // The full define block — all or-clauses assembled — suitable for
    // pasting into a measure that does not yet reference the Kompendium
  repeated CqlClause              clauses        = 6;  // One entry per or-clause
  string                          stamp_version  = 7;  // Concept-level STAMP
}

// Request a concept's CQL explication
message ConceptCqlRequest {
  dev.ikm.tinkar.schema.PublicId public_id = 1;
}

// --- extend IkeKnowledgeGraph ---

service IkeKnowledgeGraph {
  // ... existing RPCs unchanged ...

  // Return the concept's compact reference, full expanded CQL, and all
  // individual or-clauses with their ELM ASTs.
  rpc GetConceptCql(ConceptCqlRequest) returns (ConceptCqlResponse);

  // Search the Kompendium for concepts that have CQL explicitations.
  // Uses the existing TinkarSearchQueryResponse shape — each result
  // includes the concept's compact CQL clause in the description field.
  rpc SearchCqlConcepts(TinkarConceptIdRequest) returns (TinkarSearchQueryResponse);
}
```

---

## REST Endpoints

All new endpoints live under the existing `/api/ike/knowledgegraph` prefix.

### `GET /api/ike/knowledgegraph/concepts/{conceptId}/cql`

Returns the full explication for a concept.

**Request**
```
GET /api/ike/knowledgegraph/concepts/5c40ced2-a372-5da5-8c93-f9ceb2bc0caf/cql
```

**Response**
```json
{
  "conceptId": "5c40ced2-a372-5da5-8c93-f9ceb2bc0caf",
  "conceptLabel": "Morbid obesity",
  "sourceSctid": "238136002",
  "compactClause": "\"BMI determination\".result overlaps \"Morbid obesity\"",
  "expandedCql": "define \"Has morbid obesity\":\n  exists [Condition: \"Morbid obesity (SNOMED 238136002)\"]\n  or \"Latest BMI\".value >= 40 'kg/m2'\n  or (\"Latest Weight\" / (\"Latest Height\" * \"Latest Height\")) >= 40\n  or (\"Latest BMI\".value >= 35 'kg/m2'\n        and exists [Condition: \"Obesity-related comorbidity\"])",
  "clauses": [
    {
      "path": "CODED_PATH",
      "cqlText": "exists [Condition: \"Morbid obesity (SNOMED 238136002)\"]",
      "astJson": "{ \"type\": \"ExistsExpression\", ... }",
      "description": "coded path: the code carries no threshold",
      "stampVersion": "2026-01-15T00:00:00Z | SNOMEDCT US | Development"
    },
    {
      "path": "BMI_VALUE",
      "cqlText": "\"Latest BMI\".value >= 40 'kg/m2'",
      "astJson": "{ \"type\": \"ComparisonExpression\", ... }",
      "thresholdValue": 40.0,
      "thresholdUnit": "kg/m2",
      "description": "BMI value: cutoff defined in the Kompendium",
      "stampVersion": "2026-01-15T00:00:00Z | SNOMEDCT US | Development"
    },
    {
      "path": "HEIGHT_WEIGHT",
      "cqlText": "(\"Latest Weight\" / (\"Latest Height\" * \"Latest Height\")) >= 40",
      "astJson": "{ \"type\": \"ComparisonExpression\", ... }",
      "thresholdValue": 40.0,
      "thresholdUnit": "kg/m2",
      "description": "height + weight: BMI recomputed by the Kompendium",
      "stampVersion": "2026-01-15T00:00:00Z | SNOMEDCT US | Development"
    },
    {
      "path": "CONDITIONAL",
      "cqlText": "(\"Latest BMI\".value >= 35 'kg/m2' and exists [Condition: \"Obesity-related comorbidity\"])",
      "astJson": "{ \"type\": \"AndExpression\", ... }",
      "thresholdValue": 35.0,
      "thresholdUnit": "kg/m2",
      "relatedConceptIds": ["<uuid of Obesity-related comorbidity>"],
      "description": "lower threshold with qualifying comorbidity",
      "stampVersion": "2026-01-15T00:00:00Z | SNOMEDCT US | Development"
    }
  ],
  "stampVersion": "2026-01-15T00:00:00Z | SNOMEDCT US | Development"
}
```

### `GET /api/ike/knowledgegraph/concepts/{conceptId}/cql/clauses`

Returns the individual or-clauses only — lighter payload for an editor's
inline documentation panel.

**Response**
```json
{
  "conceptId": "5c40ced2-a372-5da5-8c93-f9ceb2bc0caf",
  "conceptLabel": "Morbid obesity",
  "clauses": [
    { "path": "CODED_PATH",    "cqlText": "...", "description": "..." },
    { "path": "BMI_VALUE",     "cqlText": "...", "thresholdValue": 40.0, "thresholdUnit": "kg/m2" },
    { "path": "HEIGHT_WEIGHT", "cqlText": "...", "thresholdValue": 40.0, "thresholdUnit": "kg/m2" },
    { "path": "CONDITIONAL",   "cqlText": "...", "thresholdValue": 35.0, "thresholdUnit": "kg/m2",
      "relatedConceptIds": ["<uuid>"] }
  ]
}
```

### `GET /api/ike/knowledgegraph/concepts/{conceptId}/cql/ast`

Returns the full AST graph for the concept's definition — the `AndExpression`
(or `OrExpression`) that combines all clauses. This is what the editor works
with programmatically when it needs to compare or validate a user-authored
clause against the Kompendium's stored definition.

**Response**
```json
{
  "conceptId": "5c40ced2-a372-5da5-8c93-f9ceb2bc0caf",
  "conceptLabel": "Morbid obesity",
  "rootAst": {
    "type": "OrExpression",
    "operands": [
      { "path": "CODED_PATH",    "ast": { "type": "ExistsExpression", ... } },
      { "path": "BMI_VALUE",     "ast": { "type": "ComparisonExpression", ... } },
      { "path": "HEIGHT_WEIGHT", "ast": { "type": "ComparisonExpression", ... } },
      { "path": "CONDITIONAL",   "ast": { "type": "AndExpression", ... } }
    ]
  }
}
```

### `GET /api/ike/knowledgegraph/cql/search?q=obesity&hasCql=true`

Search for concepts that have CQL explicitations. Used for editor autocomplete.

**Response**
```json
{
  "results": [
    {
      "conceptId": "5c40ced2-a372-5da5-8c93-f9ceb2bc0caf",
      "conceptLabel": "Morbid obesity",
      "sourceSctid": "238136002",
      "compactClause": "\"BMI determination\".result overlaps \"Morbid obesity\"",
      "clauseCount": 4
    },
    {
      "conceptId": "<uuid>",
      "conceptLabel": "Severe obesity",
      "sourceSctid": "83911000119104",
      "compactClause": "\"BMI determination\".result overlaps \"Severe obesity\"",
      "clauseCount": 2
    }
  ]
}
```

---

## CQL Editor Integration Scenarios

These are the four interactions a CQL editor needs to support.

### 1. Autocomplete: user types a concept name

The editor calls the search endpoint as the user types. Each result in the dropdown
shows the concept name and its compact clause, so the author can see what they're
inserting before they commit.

```
User types: "morb"

GET /api/ike/knowledgegraph/cql/search?q=morb&hasCql=true

Editor renders autocomplete entry:
  Morbid obesity (SNOMED 238136002)
  → "BMI determination".result overlaps "Morbid obesity"
    [4 or-clauses: coded path, BMI value, height+weight, conditional]
```

### 2. Definition injection: user selects a concept

The editor fetches the full response and offers two insertion modes:

**Compact** — inserts the Kompendium reference. The measure stays thin:
```cql
define "Has morbid obesity":
  // the Kompendium's clause for the concept, published once
  "BMI determination".result overlaps "Morbid obesity"
```

**Expanded** — inserts the full or-clause block. Used when the measure owner
needs the logic inline for local review or for a CQL engine that cannot yet
resolve Kompendium references:
```cql
define "Has morbid obesity":
  exists [Condition: "Morbid obesity (SNOMED 238136002)"]
  or "Latest BMI".value >= 40 'kg/m2'
  or ("Latest Weight" / ("Latest Height" * "Latest Height")) >= 40
  or ("Latest BMI".value >= 35 'kg/m2'
        and exists [Condition: "Obesity-related comorbidity"])
```

Both forms come from one API call — `expandedCql` vs `compactClause` — so the
editor never builds either string itself.

### 3. Inline documentation: hover on a referenced concept

When the author hovers over `"Morbid obesity"` inside an overlaps expression,
the editor fetches the clauses endpoint and renders:

```
Morbid obesity (SNOMED 238136002) — Kompendium definition

  CODED PATH      exists [Condition: "Morbid obesity (SNOMED 238136002)"]
  BMI VALUE       Latest BMI ≥ 40 kg/m²
  HEIGHT+WEIGHT   Latest Weight / (Latest Height)² ≥ 40
  CONDITIONAL     Latest BMI ≥ 35 kg/m² AND Obesity-related comorbidity

  Version: 2026-01-15 | SNOMEDCT US | Development
  [Navigate to concept]  [Copy compact clause]  [Copy expanded block]
```

### 4. AST-level equivalence check: validate a hand-authored clause

When a measure author edits a clause by hand, the editor can compare their
authored AST against the stored Kompendium AST to detect divergence before
it becomes a silent inconsistency across measures:

```
POST /api/ike/knowledgegraph/cql/check-equivalence
{
  "conceptId": "5c40ced2-a372-5da5-8c93-f9ceb2bc0caf",
  "authoredCql": "\"Most Recent BMI\".value >= 40 'kg/m2'",
  "path": "BMI_VALUE"
}

Response:
{
  "equivalent": false,
  "diff": {
    "left":  { "type": "Property", "source": "Most Recent BMI" },
    "right": { "type": "Property", "source": "Latest BMI" },
    "note": "expression reference name differs; semantics may be equivalent but cannot be confirmed"
  },
  "kompendiumClause": "\"Latest BMI\".value >= 40 'kg/m2'"
}
```

This is the Kompendium's guarantee made actionable: the editor knows when a
measure has drifted from the shared definition, and can offer to re-align it.

---

## What Compounds

The four or-clauses for "Has morbid obesity" are authored once, stored on the
concept, and referenced everywhere. When the guideline changes — say the BMI
threshold for the conditional path drops to 30 for a new comorbidity category —
one change to the `CQL_CONDITIONAL` semantic propagates through the API. Every
measure that references the Kompendium clause instead of embedding its own copy
gets the correction for free. Every measure that embedded the expanded form gets
a diff notification the next time the editor checks equivalence.

The API surface is the mechanism. The compounding is the result.
