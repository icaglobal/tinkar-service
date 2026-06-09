# Tinkar gRPC & REST Service

A standalone Spring Boot service that exposes a gRPC and REST API for Tinkar.

[![Powered by IKE](https://ike.network/brand/powered-by/powered-by-ike-color-on-light.svg)](https://ike.network)

---

## Getting Started

### Prerequisites

- OpenJDK Java 25

### Setup

1. Create a `data` folder and unzip the dataset into it.
2. In `application.properties`, set the dataset folder name and the controller to use.
   Defaults are `data.controller.name=Open Rocks KB` and folder `data/gudid`.

### Building and Running

Build and run tests:
```bash
./mvnw install
```

Manually compile proto files:
```bash
./mvnw protobuf:compile protobuf:compile-custom compile
```

Run the application:
```bash
./mvnw spring-boot:run
```

Or run `TinkarServiceApplication.java` directly in your IDE.

Note: If running in IDE, the datasource path may be in a different location (root tinkar-core vs under service) and the --enable-preview flag needs to passed as a VM argument

Default REST port will be on 8085 and gRPC on 9095 (configurable in `application.properties`).

[SwaggerUI URL](http://localhost:8085/swagger-ui/index.html)

Sample gRPC curl:
```
grpcurl -d '{"query":"chronic lung","max_results":200}' \
  localhost:9095 \
  ai.ica.tinkar.TinkarSearchService/ConceptSearch
```

---

## Architecture

See [docs/architecture.adoc](docs/architecture.adoc) for the full PlantUML component diagram.

---

## API Endpoint Overview

The server exposes two transports on two ports.
gRPC runs on port **9095**; REST runs on port **8085**.
There are three tiers of gRPC services and three REST base paths, organized by intended audience.

### gRPC Services (port 9095)

#### IkeGraphRAG — `dev.ikm.tinkar.service.IkeGraphRAG`

Primary service for new clients (tinkar-ui, external integrators).

| Method | Description |
|--------|-------------|
| `Search` | Full-text search across all semantics; returns matched results with FQN and STAMP. |
| `ConceptSearch` | Concept-scoped text search with a `max_results` cap. |
| `ConceptSearchWithSort` | Same as `ConceptSearch` with a `sort_by` option (`TOP_COMPONENT`, `TOP_COMPONENT_ALPHA`, `SEMANTIC`, `SEMANTIC_ALPHA`). |
| `GetEntity` | Fetch a concept's search-result record by public ID. |
| `GetChildConcepts` | Direct children of a concept in the taxonomy. |
| `GetDescendantConcepts` | Transitive descendants of a concept. |
| `GetLIDRRecordConceptsFromTestKit` | LIDR record semantics attached to a test-kit device concept. |
| `GetResultConformanceConceptsFromLIDRRecord` | Result-conformance concepts from a LIDR record semantic. |
| `GetAllowedResultConceptsFromResultConformance` | Allowed-result concepts from a result-conformance concept. |
| `RebuildSearchIndex` | Trigger an async Lucene index rebuild. |

#### IkeKnowledgeGraph — `dev.ikm.tinkar.service.IkeKnowledgeGraph`

Richer service with coordinate-aware queries and named coordinate persistence.

| Method | Description |
|--------|-------------|
| `InspectConcept` | All semantics for a concept, optionally filtered by a `CoordinateOverride`. |
| `GetChildConcepts` | Children with optional coordinate override. |
| `GetDescendantConcepts` | Descendants with optional coordinate override. |
| `GetSemanticsWithCoordinate` | Semantics filtered by a named saved coordinate. |
| `SaveStampCoordinate` | Persist a named stamp coordinate configuration. |
| `ListStampCoordinates` | List all saved stamp coordinates. |
| `SaveNavigationCoordinate` | Persist a named navigation coordinate configuration. |
| `ListNavigationCoordinates` | List all saved navigation coordinates. |
| `SaveLanguageCoordinate` | Persist a named language coordinate configuration. |
| `ListLanguageCoordinates` | List all saved language coordinates. |

#### IkeAdmin — `dev.ikm.tinkar.service.IkeAdmin`

Administrative operations (import, reasoning).

| Method | Description |
|--------|-------------|
| `ImportChangeset` | Stream a protobuf changeset file into the entity store. |
| `RunReasoner` | Execute the OWL EL++ reasoner and return a classification summary. |

#### TinkarSearchService — `dev.ikm.tinkar.service.TinkarSearchService`

**Deprecated.** Kept for backward compatibility with older Komet clients.
Exposes the same search, entity-lookup, LIDR, and index-rebuild operations as `IkeGraphRAG`, plus two methods used internally by `GrpcPrimitiveDataService`:

- `LoadConceptEntityGraph` — full entity graph (concept + semantics + patterns + stamps); called by Komet on concept open.
- `GetEntityByPublicId` — single entity bytes by public ID; called by `GrpcPrimitiveDataService` on cache miss.

---

### REST API (port 8085)

#### `/api/ike/graphrag` — GraphRAG REST controller

Primary REST surface for new clients (mirrors the `IkeGraphRAG` gRPC service).

| Method | Path | Description |
|--------|------|-------------|
| GET | `/search` | Full-text search (`?query=`). |
| GET | `/concept-search` | Concept search (`?query=`, `?maxResults=`). |
| GET | `/concept-search-sorted` | Concept search with sort option (`?query=`, `?maxResults=`, `?sortBy=`). |
| GET | `/entity` | Fetch entity by public ID (`?conceptId=`). |
| GET | `/children` | Direct children (`?conceptId=`). |
| GET | `/descendants` | Transitive descendants (`?conceptId=`). |
| GET | `/lidr-records` | LIDR records for a test-kit concept (`?testKitConceptId=`). |
| GET | `/result-conformances` | Result conformances from a LIDR record (`?lidrRecordConceptId=`). |
| GET | `/allowed-results` | Allowed results from a conformance (`?resultConformanceConceptId=`). |
| POST | `/rebuild-index` | Trigger async Lucene index rebuild. |

#### `/api/ike/knowledgegraph` — KnowledgeGraph REST controller

Mirrors the `IkeKnowledgeGraph` gRPC service; adds write operations and coordinate management.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/semantics` | All semantics for a concept (`?conceptId=`). |
| GET | `/semantics-by-coordinate` | Semantics filtered by a named coordinate (`?conceptId=`, `?coordinateName=`). |
| GET | `/comments` | Comment semantics for a concept (`?conceptId=`). |
| GET | `/entity-graph` | Full entity graph for a concept (`?conceptId=`). |
| GET | `/entity-by-id` | Single entity by public ID. |
| GET | `/children` | Direct children (`?conceptId=`). |
| GET | `/descendants` | Transitive descendants (`?conceptId=`). |
| GET | `/change-history` | Full stamp change history across all concepts. |
| GET | `/concept-change-history` | Change history for a specific concept (`?conceptId=`). |
| POST | `/changes` | Record an uncommitted field change. |
| POST | `/save` | Commit pending changes to the entity store. |
| POST | `/discard` | Discard pending changes. |
| POST | `/descendants` | Add a parent-child relationship. |
| POST | `/descendants/create` | Create a new concept and add it as a descendant. |
| DELETE | `/descendants` | Remove a parent-child relationship. |
| POST | `/coordinates/stamp` | Save a named stamp coordinate. |
| GET | `/coordinates/stamp` | List saved stamp coordinates. |
| POST | `/coordinates/navigation` | Save a named navigation coordinate. |
| GET | `/coordinates/navigation` | List saved navigation coordinates. |
| POST | `/coordinates/language` | Save a named language coordinate. |
| GET | `/coordinates/language` | List saved language coordinates. |

#### `/api/ike/admin` — Admin REST controller

Mirrors the `IkeAdmin` gRPC service.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/import` | Upload and import a protobuf changeset (multipart form). |
| POST | `/reasoner` | Run the OWL EL++ reasoner. |

#### `/api/tinkar` — Legacy Tinkar REST controller

**Deprecated.** Kept for backward compatibility; mirrors `TinkarSearchService` gRPC methods.
Prefer `/api/ike/graphrag` and `/api/ike/knowledgegraph` for new integrations.

---

## Sample Test UI

Under `sample-ui/` is a React app for testing the REST service and running test scenarios.

---

## Performance Testing

Performance tests use [Gatling 3.15.0](https://gatling.io/) and live in the `perf-tests/` folder.
The service must be running before executing the tests.

### Running the Tests

Run all simulations in a single combined report:
```bash
cd perf-tests
../mvnw gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.FullSuiteSimulation
```

Run an individual simulation:
```bash
# Search endpoints only
../mvnw gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.SearchSimulation

# Entity graph (concept, children, descendants)
../mvnw gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.ConceptGraphSimulation

# Knowledge graph UI workflow (detail panel, navigation panel, comments)
../mvnw gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.KnowledgeGraphSimulation
```

### Viewing Reports

After each run, Gatling writes an HTML report to `target/gatling/<simulation-name>-<timestamp>/index.html`.

### Available Simulations

| Class | Endpoints exercised | p95 threshold |
|-------|---------------------|---------------|
| `SearchSimulation` | `/api/ike/graphrag/search`, `/concept-search` | 200 ms |
| `ConceptGraphSimulation` | `/api/ike/graphrag/entity`, `/children`, `/descendants` | 1 000 ms |
| `KnowledgeGraphSimulation` | `/api/ike/knowledgegraph/semantics`, `/concept-change-history`, `/comments` | 2 000 ms |
| `FullSuiteSimulation` | All of the above in one run | 2 000 ms (global) |

---

## Static Analysis (SpotBugs)

SpotBugs is configured via `spotbugs-maven-plugin 4.9.8.3`.

### Command Line

Generate the XML report without failing the build:
```bash
./mvnw compile spotbugs:spotbugs
# Report written to: target/spotbugsXml.xml
```

Generate the report **and** fail the build on findings above the configured threshold:
```bash
./mvnw compile spotbugs:check
```

> **Note:** Always include the `compile` phase before the SpotBugs goal. Running
> `spotbugs:check` or `spotbugs:spotbugs` alone analyses stale bytecode from the
> previous build and will not reflect recent edits.

### Opening the SpotBugs GUI

```bash
./spotbugs-gui.sh
```

`spotbugs-gui.sh` handles everything automatically:

- Generates `target/spotbugsXml.xml` if it doesn't exist yet (runs `compile spotbugs:spotbugs`).
- Resolves the SpotBugs GUI classpath on first run via `spotbugs-pom.xml`, then caches it in `target/spotbugs-classpath.txt`.
- Launches the GUI directly with `-gui`, bypassing the Maven plugin's forked-JVM headless detection issue on macOS.

To refresh results after editing code, run `./mvnw compile spotbugs:spotbugs` and re-run the script.

### Exclude Filter

`spotbugs-exclude.xml` suppresses findings in Protobuf-generated classes under `dev.ikm.tinkar.service.proto.*`.
Add additional suppressions there for acceptable false positives rather than using `@SuppressFBWarnings` inline, so the rationale is centrally documented.
