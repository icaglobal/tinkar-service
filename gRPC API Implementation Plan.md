# IKE gRPC API — Implementation Plan

This document details the work required to implement the three-tier gRPC API design described in [gRPC API.pdf](gRPC%20API.pdf). It compares each requirement against the current implementation, identifies gaps, and organizes the work into incremental, deliverable chunks.

---

## Table of Contents

1. [Current State](#1-current-state)
2. [Chunk 1 — Coordinate System Protobuf Messages](#chunk-1--coordinate-system-protobuf-messages)
3. [Chunk 2 — Tier 1: Graph RAG Ready Service](#chunk-2--tier-1-graph-rag-ready-service)
4. [Chunk 3 — Tier 2: Concept-Aware Service](#chunk-3--tier-2-concept-aware-service)
5. [Chunk 4 — Tier 3: IKE Native Service](#chunk-4--tier-3-ike-native-service)
6. [Chunk 5 — Cross-Cutting Concerns](#chunk-5--cross-cutting-concerns)
7. [Chunk 6 — Migration & Cleanup](#chunk-6--migration--cleanup)
8. [Open Questions](#open-questions)
9. [Appendix — Existing Endpoint Mapping](#appendix--existing-endpoint-mapping)

---

## 1. Current State

### What Exists

A single gRPC service `TinkarSearchService` with 11 unary RPCs, defined in `tinkar-core/service/src/main/proto/tinkar_search.proto` and implemented in `TinkarSearchGrpcController.java`:

| # | RPC | Description |
|---|-----|-------------|
| 1 | `Search` | Basic search query |
| 2 | `ConceptSearch` | Search with maxResults |
| 3 | `ConceptSearchWithSort` | Search with sort options (TOP_COMPONENT, SEMANTIC, etc.) |
| 4 | `GetEntity` | Get concept by PublicId |
| 5 | `GetChildConcepts` | Get direct children |
| 6 | `GetDescendantConcepts` | Get all descendants |
| 7 | `GetLIDRRecordConceptsFromTestKit` | LIDR records for a test kit |
| 8 | `GetResultConformanceConceptsFromLIDRRecord` | Result conformances for LIDR record |
| 9 | `GetAllowedResultConceptsFromResultConformance` | Allowed results for conformance |
| 10 | `RebuildSearchIndex` | Admin: rebuild Lucene index |
| 11 | `GetConceptSemantics` | All semantics for a concept |

### REST-Only Endpoints (not yet in gRPC)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/change-history` | GET | Change history for a single entity |
| `/concept-change-history` | GET | Full change history for concept + all its semantics |
| `/comments` | GET | Comment semantics for a concept |
| `/create-change` | POST | Create a comment semantic |
| `/save-changes` | POST | Persist pending changes to disk |
| `/discard-changes` | POST | Discard unsaved changes |
| `/descendants` | POST | Add IS-A relationship |
| `/descendants/create` | POST | Create new concept + IS-A |
| `/descendants` | DELETE | Remove IS-A relationship |

### What's Missing (High-Level)

1. **No coordinate system** — All queries use implicit server defaults; callers cannot specify STAMP, Language, Logic, or Navigation coordinates
2. **No three-service separation** — One flat service instead of three tiered services
3. **No server streaming** — All RPCs are unary
4. **No dual-return pattern** — Responses don't carry PublicId + label for concept references
5. **No temporal queries** — No point-in-time graph state retrieval
6. **No graph traversal** — No neighborhood/subgraph exploration with depth control

---

## Chunk 1 — Coordinate System Protobuf Messages

> **Goal:** Define the protobuf message types for all four coordinate axes. These are the building blocks used by Tiers 2 and 3.

This chunk produces no new RPCs — only `.proto` message definitions and the Java server-side code to translate between protobuf coordinates and the internal Java coordinate records (nid ↔ PublicId translation).

### 1.1 STAMP Coordinate Messages

Define both the **full** (Tier 3, all fields required) and **override** (Tier 2, all fields optional) variants.

**Fields:**
- `allowed_states` — Set of status values (ACTIVE, INACTIVE)
- `position_time` — Epoch millis as int64, or sentinel for "latest"
- `position_path` — PublicId of the path concept
- `module_nids` — Repeated PublicId for module inclusion
- `excluded_module_nids` — Repeated PublicId for module exclusion
- `module_priority_list` — Ordered repeated PublicId for module priority

**Decisions needed:** StateSet representation (repeated enum vs bitmask vs named presets — see Open Question #3).

### 1.2 Language Coordinate Messages

**Fields:**
- `language_concept` — PublicId (e.g., English)
- `description_pattern_list` — Ordered repeated PublicId
- `description_type_preference_list` — Ordered repeated PublicId
- `dialect_pattern_preference_list` — Ordered repeated PublicId
- `module_preference_for_language` — Ordered repeated PublicId

### 1.3 Logic Coordinate Messages

**Fields (8 concept/pattern references):**
- `classifier` — PublicId
- `description_logic_profile` — PublicId
- `inferred_axioms_pattern` — PublicId
- `stated_axioms_pattern` — PublicId
- `concept_member_pattern` — PublicId
- `stated_navigation_pattern` — PublicId
- `inferred_navigation_pattern` — PublicId
- `root` — PublicId

### 1.4 Navigation Coordinate Messages

**Fields:**
- `navigation_patterns` — Repeated PublicId
- `vertex_states` — StateSet
- `sort_vertices` — bool
- `sort_pattern_list` — Ordered repeated PublicId

### 1.5 Server-Side Coordinate Translation

- Implement `CoordinateTranslator` (or similar) utility class that converts between protobuf coordinate messages and the internal Java record types (`StampCoordinateRecord`, `LanguageCoordinateRecord`, `LogicCoordinateRecord`, `NavigationCoordinateRecord`)
- Handle PublicId → nid translation at the boundary
- For Tier 2 overrides, merge partial caller coordinates with server defaults (field-by-field)
- Document and publish the **Tier 1 default coordinates** so Tier 2/3 users can reproduce the same behavior explicitly

### Deliverables

- [ ] New `.proto` file (e.g., `ike_coordinates.proto`) with all coordinate messages
- [ ] `StateSet` enum or message definition
- [ ] `CoordinateTranslator` Java class for protobuf ↔ Java record conversion
- [ ] Default coordinate configuration (documented, testable)
- [ ] Unit tests for coordinate translation round-trips

---

## Chunk 2 — Tier 1: Graph RAG Ready Service

> **Goal:** Implement the `IkeGraphRAG` service — the simplest, most opinionated tier. Coordinates are invisible to callers; the server applies defaults. Optimized for LLM/RAG consumption.

### 2.1 SearchConcepts

**Purpose:** Full-text search returning results ready for LLM consumption.

**Request:**
- `query` (string, required)
- `result_limit` (int32, optional)
- `timestamp` (string ISO 8601, optional — defaults to latest)
- `language_tag` (string, optional — e.g., "en-US", defaults to server default)

**Response:**
- Ranked results, each with: concept UUID, preferred name, relevance score, context snippet

**Current coverage:** `ConceptSearch` and `ConceptSearchWithSort` provide the core search but lack timestamp and language_tag parameters.

**Work required:**
- [ ] New proto message `SearchConceptsRequest` with timestamp + language_tag fields
- [ ] New proto message `SearchConceptsResponse` with simplified result structure
- [ ] Server-side: apply timestamp to STAMP coordinate position, map language_tag to pre-configured `LanguageCoordinateRecord`
- [ ] Can reuse existing `TinkarServiceImpl` search logic underneath

### 2.2 GetDeviceView

**Purpose:** Retrieve a single concept with all semantics resolved to human-readable strings (attribute name → value maps, relationships with labels).

**Request:**
- `concept_id` (string UUID, required)
- `timestamp` (string ISO 8601, optional)
- `language_tag` (string, optional)

**Response:**
- Canonical UUID
- Preferred name (resolved)
- Map of attribute names → string values (field meanings resolved to labels)
- List of relationships (type label, target UUID, target name)
- Effective timestamp
- Categorical tags/classifications

**Current coverage:** `GetEntity` returns raw concept data; `GetConceptSemantics` returns semantics with pattern names and field values. Neither resolves everything to human-readable strings in a flat map.

**Work required:**
- [ ] New proto messages `GetDeviceViewRequest` / `DeviceViewResponse`
- [ ] Server-side: combine entity lookup + semantics retrieval + label resolution into a single pre-materialized response
- [ ] Resolve all field meanings, purposes, and concept-type values to display strings
- [ ] Extract relationships from axiom patterns and resolve to (type, target UUID, target name) triples
- [ ] This is the most complex Tier 1 RPC — consider building a `DeviceViewBuilder` service class

### 2.3 GetSubgraph

**Purpose:** Retrieve a neighborhood of concepts for RAG context enrichment.

**Request:**
- `concept_id` (string UUID, required)
- `max_depth` (int32, required)
- `relationship_type_filter` (repeated string, optional — human-readable type names)
- `timestamp` (string ISO 8601, optional)
- `language_tag` (string, optional)

**Response:**
- List of concept views (same structure as `DeviceViewResponse`)
- List of edges (type label, source UUID, target UUID)
- Effective timestamp

**Current coverage:** Nothing. No graph traversal with depth control exists.

**Work required:**
- [ ] New proto messages `GetSubgraphRequest` / `SubgraphResponse`
- [ ] Server-side: implement BFS/DFS traversal from starting concept using navigation coordinates
- [ ] Reuse `DeviceViewBuilder` for each visited concept
- [ ] Filter edges by relationship type if specified
- [ ] Enforce max_depth to prevent runaway traversals

### 2.4 GetGraphSnapshot

**Purpose:** Retrieve graph state at a specific point in time. Key for regulatory use cases ("what did this look like on the approval date?").

**Request:**
- `concept_ids` (repeated string UUID, required — one or more)
- `timestamp` (string ISO 8601, **required** — this is the one Tier 1 RPC where time is not optional)
- `language_tag` (string, optional)

**Response:**
- Concept views as-of the specified timestamp
- Snapshot metadata (timestamp used)

**Current coverage:** Nothing. No temporal queries exist.

**Work required:**
- [ ] New proto messages `GetGraphSnapshotRequest` / `GraphSnapshotResponse`
- [ ] Server-side: construct a STAMP coordinate with position time set to the requested timestamp
- [ ] Reuse `DeviceViewBuilder` with the temporal coordinate
- [ ] Validate that the timestamp is a real requirement (not optional) in the proto

### Deliverables

- [ ] New `.proto` file: `ike_graph_rag.proto` with service `IkeGraphRAG` and all 4 RPCs
- [ ] `IkeGraphRagGrpcController.java` implementing all 4 RPCs
- [ ] `DeviceViewBuilder.java` for materialized concept views
- [ ] Graph traversal logic for `GetSubgraph`
- [ ] Integration tests for each RPC

---

## Chunk 3 — Tier 2: Concept-Aware Service

> **Goal:** Implement the `IkeKnowledgeGraph` service — exposes the concept-oriented structure with optional coordinate overrides and the dual-return pattern (PublicId + label on every concept reference).

**Prerequisite:** Chunk 1 (coordinate messages) must be complete.

### 3.1 GetConceptWithSemantics

**Purpose:** Retrieve a concept with its full semantic structure, including pattern references, with optional coordinate overrides.

**Request:**
- `concept_id` (PublicId, required)
- `stamp_override` (StampCoordinateOverride, optional)
- `language_override` (LanguageCoordinateOverride, optional)
- `logic_override` (LogicCoordinateOverride, optional)
- `navigation_override` (NavigationCoordinateOverride, optional)

**Response (dual-return pattern throughout):**
- Concept reference (PublicId + preferred label)
- List of semantics, each with:
  - Semantic PublicId
  - Pattern reference (PublicId + label)
  - Fields: meaning (PublicId + label), purpose (PublicId + label), datatype, typed value
  - STAMP info (status, time, author, module, path — each as PublicId + label)

**Current coverage:** `GetConceptSemantics` returns similar data but without coordinate overrides and without the dual-return pattern.

**Work required:**
- [ ] New proto messages with dual-return structures (`ConceptReference`, `SemanticDetail`, `FieldDetail`)
- [ ] Server-side: merge caller coordinate overrides with defaults
- [ ] Resolve all concept references to PublicId + label pairs
- [ ] Reuse existing `getConceptSemantics` logic as foundation

### 3.2 ResolveToText

**Purpose:** Resolve a concept to its preferred text under a given coordinate context.

**Request:**
- `concept_id` (PublicId, required)
- Coordinate overrides (all optional)

**Response:**
- Resolved text string
- Description type used (e.g., "preferred", "fully qualified")
- Semantic reference for the selected description

**Current coverage:** Nothing — no dedicated text resolution endpoint.

**Work required:**
- [ ] New proto messages
- [ ] Server-side: use language coordinate to walk preference lists and find best description
- [ ] Return which description type was selected

### 3.3 GetSemanticsByPattern

**Purpose:** Retrieve all semantics matching a given pattern, optionally filtered to a specific concept.

**Request:**
- `pattern_id` (PublicId, required)
- `concept_id` (PublicId, optional — filter to this concept)
- Coordinate overrides (all optional)

**Response:**
- List of semantics (same dual-return structure as GetConceptWithSemantics)
- Total count

**Current coverage:** Nothing — no pattern-based semantic query.

**Work required:**
- [ ] New proto messages
- [ ] Server-side: query semantic index by pattern, optionally filter by concept
- [ ] This enables powerful queries like "all Description Pattern semantics" or "all GS1 Identifier Pattern semantics for concept X"

### 3.4 QueryWithCoordinates

**Purpose:** Execute a query with fully explicit coordinate specification. The Tier 2 RPC closest to Tier 3 behavior.

**Request:**
- `query_expression` (search terms, concept filter, pattern filter — format TBD)
- Complete or partial coordinate specification for each axis

**Response:**
- Query results with dual-return references
- The **effective coordinates used** (so callers can see what defaults were applied)

**Current coverage:** Nothing.

**Work required:**
- [ ] Define the query expression format (this is the least-specified RPC in the PDF)
- [ ] New proto messages
- [ ] Server-side: apply merged coordinates to query execution
- [ ] Return effective coordinates in response

### 3.5 GetVersionHistory

**Purpose:** Retrieve the version history of a concept.

**Request:**
- `concept_id` (PublicId, required)
- `path_filter` (PublicId, optional)
- `time_range_start` / `time_range_end` (optional)

**Response:**
- Ordered list of version entries, each with: STAMP info + semantics as-of that version

**Current coverage:** REST endpoint `GET /change-history` exists but is not exposed via gRPC. Also lacks path filtering and time range support.

**Work required:**
- [ ] New proto messages
- [ ] Server-side: extend existing `getChangeHistory` logic with path and time filters
- [ ] Add gRPC controller method
- [ ] Include semantics-at-version in each history entry

### 3.6 CompareVersions

**Purpose:** Compute a diff between two temporal states of the knowledge graph.

**Request:**
- `from_timestamp` (required)
- `to_timestamp` (required)
- `scope` — optional: specific concept PublicIds, specific pattern PublicIds, or full graph
- Coordinate overrides for non-temporal axes

**Response:**
- List of changes: concept reference, change type (added/modified/retired), description of what changed

**Current coverage:** Nothing.

**Work required:**
- [ ] New proto messages
- [ ] Server-side: iterate entities, compare STAMP versions between two timestamps
- [ ] Categorize changes as added/modified/retired
- [ ] This is a complex RPC — may want to support pagination or streaming for large diffs

### Deliverables

- [ ] New `.proto` file: `ike_knowledge_graph.proto` with service `IkeKnowledgeGraph` and all 6 RPCs
- [ ] Dual-return base messages (`ConceptReference`, `FieldDetail`, etc.)
- [ ] `IkeKnowledgeGraphGrpcController.java`
- [ ] Coordinate merging logic (partial override + defaults)
- [ ] Version history and comparison services
- [ ] Integration tests

---

## Chunk 4 — Tier 3: IKE Native Service

> **Goal:** Implement the `IkePrimitives` service — raw access to the knowledge graph primitives with no label resolution and no default coordinates. Callers must provide full coordinate context.

**Prerequisite:** Chunk 1 (coordinate messages) must be complete.

### 4.1 GetConcept (unary)

**Purpose:** Retrieve a raw concept by PublicId.

**Request:**
- `concept_id` (PublicId, required)
- `stamp_coordinate` (full StampCoordinate, **required**)

**Response:**
- Concept PublicId only (no label resolution)

**Work required:**
- [ ] New proto messages with required coordinate fields
- [ ] Server-side: validate all coordinate fields are present, reject with descriptive error if missing
- [ ] Return raw concept data

### 4.2 GetSemantics (server streaming)

**Purpose:** Stream semantics matching specified criteria.

**Request:**
- `concept_id` (PublicId, optional filter)
- `pattern_id` (PublicId, optional filter)
- `stamp_coordinate` (full, required)
- `limit` (int32, optional)

**Response stream:** Individual semantics with raw PublicId references (no labels), full STAMP info, unresolved field values.

**Work required:**
- [ ] First server-streaming RPC — requires streaming infrastructure in the controller
- [ ] Server-side: iterate matching semantics, emit each as a stream element
- [ ] Handle backpressure appropriately

### 4.3 ListPatterns (server streaming)

**Purpose:** Enumerate all available patterns.

**Request:**
- `stamp_coordinate` (full, required)
- `limit` (int32, optional)
- `continuation_token` (string, optional — for pagination)

**Response stream:** Pattern definitions with PublicId, meaning/purpose references, ordered field definitions.

**Work required:**
- [ ] New proto messages for pattern definitions with field schemas
- [ ] Server-side: iterate the pattern index
- [ ] Support continuation token for resumable iteration

### 4.4 GetPattern (unary)

**Purpose:** Retrieve a single pattern definition.

**Request:**
- `pattern_id` (PublicId, required)
- `stamp_coordinate` (full, required)

**Response:** Same structure as a single ListPatterns element.

**Work required:**
- [ ] New proto messages
- [ ] Server-side: look up pattern by PublicId, return definition

### 4.5 ResolveWithCoordinates (unary)

**Purpose:** Manually resolve a concept to text using explicitly specified coordinates.

**Request:**
- `concept_id` (PublicId, required)
- `language_coordinate` (full, required)
- `stamp_coordinate` (full, required)
- `logic_coordinate` (full, optional)

**Response:**
- Resolved text
- Description type selected
- Semantic reference for the description

**Work required:**
- [ ] New proto messages
- [ ] Server-side: use provided coordinates for label resolution (no defaults)

### 4.6 QueryBySTAMP (server streaming)

**Purpose:** Retrieve all components matching a STAMP query.

**Request:**
- `stamp_coordinate` (full, required)
- `component_type_filter` (enum: CONCEPT, SEMANTIC, PATTERN — optional)

**Response stream:** Components with PublicId, component type, serialized data.

**Work required:**
- [ ] New proto messages with component type enum
- [ ] Server-side: iterate components filtered by STAMP and type
- [ ] This can produce very large result sets — streaming is essential

### 4.7 StreamAllConcepts (server streaming)

**Purpose:** Iterate over all concepts in the system.

**Request:**
- `stamp_coordinate` (full, required)
- `batch_size_hint` (int32, optional)

**Response stream:** Concept PublicIds only (no resolution).

**Work required:**
- [ ] New proto messages
- [ ] Server-side: iterate full concept index, emit PublicIds
- [ ] Respect STAMP coordinate for filtering (only active concepts, etc.)

### Deliverables

- [ ] New `.proto` file: `ike_primitives.proto` with service `IkePrimitives` and all 7 RPCs
- [ ] `IkePrimitivesGrpcController.java`
- [ ] Server-streaming infrastructure (first use of streaming in the codebase)
- [ ] Coordinate validation (reject incomplete coordinates with descriptive errors)
- [ ] Integration tests including streaming scenarios

---

## Chunk 5 — Cross-Cutting Concerns

> **Goal:** Infrastructure and design decisions that span all three tiers.

### 5.1 PublicId Wire Encoding

**Decision needed:** How to encode UUIDs in PublicId messages.

Options:
- **String** (current approach) — Human-readable, larger wire size, easy for Python/JS clients
- **Paired int64** (msb/lsb) — Compact, matches `PublicId.forEach(LongConsumer)` in Java
- **16 bytes** — Most compact, harder to use in client code

The current proto uses `dev.ikm.tinkar.schema.PublicId` which has `repeated string uuids`. This may be sufficient for all tiers unless wire size becomes a concern for Tier 3 streaming.

### 5.2 Error Handling

**Decision needed:** How to handle coordinate resolution failures.

Options:
- gRPC `NOT_FOUND` status code
- gRPC `INVALID_ARGUMENT` status code
- Response-level error field (current pattern)

The PDF raises this as Open Question #4. Recommend: `INVALID_ARGUMENT` for malformed coordinates, `NOT_FOUND` for valid PublicIds that don't resolve.

### 5.3 Tier 1 Default Coordinate Documentation

The PDF requires that Tier 1 defaults be **published and documented** so Tier 2/3 users can reproduce them. This means:

- [ ] Create a "Coordinate Cookbook" document with pre-built configurations
- [ ] Expose a `GetDefaultCoordinates` RPC or similar introspection endpoint
- [ ] Configurations to document: US English clinical terms, multi-lingual with fallback, regulatory snapshot, stated vs inferred navigation

### 5.4 Dual-Return Base Messages

Define reusable protobuf messages for the dual-return pattern used throughout Tier 2:

```
message ConceptReference {
  dev.ikm.tinkar.schema.PublicId public_id = 1;
  string label = 2;  // pre-resolved preferred name
}
```

Used in: every Tier 2 response where a concept, pattern, or field meaning is referenced.

- [ ] Define `ConceptReference`, `PatternReference`, `FieldMeaningReference` base messages
- [ ] Establish convention for when label is populated vs empty

### 5.5 Time Representation

**Decision needed:** How to represent timestamps at the gRPC boundary.

Options:
- ISO 8601 strings (human-readable, used in Tier 1 requests)
- `google.protobuf.Timestamp` (idiomatic gRPC)
- Raw `int64` epoch millis (matches Java internal representation)

Recommend: ISO 8601 strings for Tier 1 request parameters, `int64` epoch millis for Tier 3 STAMP coordinates, `google.protobuf.Timestamp` for response metadata.

### Deliverables

- [ ] Finalize all open design decisions
- [ ] Shared `.proto` file with base messages (`ConceptReference`, `StateSet`, etc.)
- [ ] Coordinate Cookbook document
- [ ] Error handling conventions documented

---

## Chunk 6 — Migration & Cleanup

> **Goal:** Transition from the current flat `TinkarSearchService` to the three-tier model.

### 6.1 Decide on Legacy Service Fate

Options:
1. **Keep `TinkarSearchService` as-is** — existing clients continue to work unchanged; new tiers are additive
2. **Deprecate and migrate** — mark existing RPCs as deprecated, point clients to the appropriate tier
3. **Wrap into Tier 1** — fold existing search RPCs into `IkeGraphRAG` as convenience methods

Recommendation: Option 1 for the initial launch. Existing clients are unaffected. Deprecate in a later phase once the tiered services are stable.

### 6.2 Mirror Remaining REST Endpoints in gRPC

These REST endpoints currently have no gRPC equivalent and should be added (either to an existing tier or a new admin/write service):

| REST Endpoint | Suggested Tier/Service |
|---------------|----------------------|
| `GET /change-history` | Tier 2 `GetVersionHistory` covers this |
| `GET /concept-change-history` | Tier 2 `GetVersionHistory` covers this |
| `GET /comments` | Tier 2 `GetSemanticsByPattern` (filter to Comment Pattern) |
| `POST /create-change` | Phase 3 (write operations) — defer |
| `POST /save-changes` | Phase 3 (write operations) — defer |
| `POST /discard-changes` | Phase 3 (write operations) — defer |
| `POST /descendants` | Phase 3 (write operations) — defer |
| `POST /descendants/create` | Phase 3 (write operations) — defer |
| `DELETE /descendants` | Phase 3 (write operations) — defer |

### 6.3 Existing LIDR Navigation RPCs

The current LIDR chain (3 RPCs) is domain-specific and not in the PDF spec. Options:
1. Keep in `TinkarSearchService` as legacy/convenience
2. Expose through Tier 1 `GetDeviceView` as part of the materialized view
3. Make available via Tier 2 `GetSemanticsByPattern` (query by LIDR patterns)

Recommendation: Keep for now, fold into `GetDeviceView` long-term.

### Deliverables

- [ ] Migration strategy document
- [ ] Deprecation annotations on legacy RPCs (if applicable)
- [ ] REST ↔ gRPC parity audit

---

## Open Questions

These are raised in the PDF (Section 12) and need decisions before implementation:

| # | Question | Options | Impact |
|---|----------|---------|--------|
| 1 | **Partial coordinate overrides** — field-by-field merge vs. all-or-nothing per axis? | Field-by-field (convenient) vs. all-or-nothing (simpler) | Chunk 1, Chunk 3 |
| 2 | **Time representation** — ISO 8601 string vs. protobuf Timestamp vs. int64 epoch millis? | See §5.5 | All chunks |
| 3 | **StateSet representation** — repeated enum vs. bitmask vs. named presets? | Repeated enum (flexible), named presets (simple) | Chunk 1 |
| 4 | **Error semantics** — gRPC status codes vs. response error fields? | See §5.2 | All chunks |
| 5 | **Module priority vs. language module preference** — unified or separate? | Separate (matches Java records) vs. unified (simpler API) | Chunk 1 |
| 6 | **PublicId wire encoding** — string vs. int64 pair vs. bytes? | See §5.1 | All chunks |
| 7 | **Tier 1 identity** — should Tier 1 responses include full PublicId as optional field? | Yes (easier migration) vs. no (keeps Tier 1 minimal) | Chunk 2 |

---

## Appendix — Existing Endpoint Mapping

### Full Scorecard: PDF Requirements vs. Current State

| Tier | RPC | Current State | Gap |
|------|-----|---------------|-----|
| **Tier 1** | SearchConcepts | Partial — `ConceptSearch` exists but no timestamp/language params | Add request fields, wire to coordinates |
| **Tier 1** | GetDeviceView | Partial — `GetEntity` + `GetConceptSemantics` exist separately | Build materialized view combining both + label resolution |
| **Tier 1** | GetSubgraph | Missing | New: graph traversal with depth control |
| **Tier 1** | GetGraphSnapshot | Missing | New: temporal subgraph query |
| **Tier 2** | GetConceptWithSemantics | Partial — `GetConceptSemantics` exists, no coordinates | Add coordinate overrides + dual-return |
| **Tier 2** | ResolveToText | Missing | New: dedicated text resolution |
| **Tier 2** | GetSemanticsByPattern | Missing | New: pattern-based semantic query |
| **Tier 2** | QueryWithCoordinates | Missing | New: query with explicit coordinates |
| **Tier 2** | GetVersionHistory | Partial — REST `change-history` exists, not in gRPC | Expose via gRPC + add filtering |
| **Tier 2** | CompareVersions | Missing | New: temporal diff |
| **Tier 3** | GetConcept | Partial — `GetEntity` exists, no required coordinates | Add required coordinate validation |
| **Tier 3** | GetSemantics (stream) | Missing | New: first streaming RPC |
| **Tier 3** | ListPatterns (stream) | Missing | New: pattern enumeration |
| **Tier 3** | GetPattern | Missing | New: single pattern retrieval |
| **Tier 3** | ResolveWithCoordinates | Missing | New: manual resolution |
| **Tier 3** | QueryBySTAMP (stream) | Missing | New: STAMP-based query |
| **Tier 3** | StreamAllConcepts (stream) | Missing | New: concept iteration |

### Suggested Implementation Order

The PDF recommends **Phase 1: Tiers 1 and 2 together**, Phase 2: Tier 3, Phase 3: Write operations. Within that:

1. **Chunk 1** (Coordinates) — Foundation; unblocks everything else
2. **Chunk 5** (Cross-cutting decisions) — Resolve open questions before building RPCs
3. **Chunk 2** (Tier 1) — Highest adoption impact; the "minutes to productivity" tier
4. **Chunk 3** (Tier 2) — Growth path for advanced users
5. **Chunk 4** (Tier 3) — Phase 2 per the PDF; can be deferred
6. **Chunk 6** (Migration) — Ongoing; can happen in parallel

### Files to Create/Modify

| File | Action | Chunk |
|------|--------|-------|
| `tinkar-core/service/src/main/proto/ike_coordinates.proto` | Create | 1 |
| `tinkar-core/service/src/main/proto/ike_graph_rag.proto` | Create | 2 |
| `tinkar-core/service/src/main/proto/ike_knowledge_graph.proto` | Create | 3 |
| `tinkar-core/service/src/main/proto/ike_primitives.proto` | Create | 4 |
| `CoordinateTranslator.java` | Create | 1 |
| `DefaultCoordinates.java` | Create | 1 |
| `DeviceViewBuilder.java` | Create | 2 |
| `IkeGraphRagGrpcController.java` | Create | 2 |
| `IkeKnowledgeGraphGrpcController.java` | Create | 3 |
| `IkePrimitivesGrpcController.java` | Create | 4 |
| `TinkarSearchGrpcController.java` | Modify (deprecation annotations) | 6 |
| `tinkar_search.proto` | Modify (deprecation annotations) | 6 |
