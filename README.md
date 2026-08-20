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

Alternatively, see [Dataset Auto-Provisioning](#dataset-auto-provisioning) below to have a
dataset downloaded automatically from Nexus instead of manually unzipping one.

### Building and Running

Build and run tests:
```bash
./mvnw install
```

Manually compile proto files:
```bash
./mvnw protobuf:generate compile
```

Run the application:
```bash
./mvnw spring-boot:run
```

Or run `TinkarServiceApplication.java` directly in your IDE.

Note: If running in IDE, the datasource path may be in a different location (root tinkar-core vs under service) and the --enable-preview flag needs to passed as a VM argument

Default REST port will be on 8085 and gRPC on 9095 (configurable in `application.properties`).

[SwaggerUI URL](http://localhost:8085/swagger-ui/index.html)

Sample gRPC call (plaintext, the default):
```bash
grpcurl -plaintext -d '{"query":"chronic lung","max_results":200}' \
  localhost:9095 \
  dev.ikm.tinkar.service.TinkarSearchService/ConceptSearch
```

Server reflection is enabled, so you can discover services and message shapes without a
`.proto` file:
```bash
grpcurl -plaintext localhost:9095 list
grpcurl -plaintext localhost:9095 describe dev.ikm.tinkar.service.TinkarSearchService
grpcurl -plaintext localhost:9095 describe dev.ikm.tinkar.service.TinkarConceptSearchRequest
```

---

## Transport Security (TLS)

gRPC runs **plaintext by default**; TLS is opt-in per environment, so upgrading changes
nothing for an existing deployment.

### Generate a development certificate

`scripts/generate-dev-cert.sh` writes a self-signed certificate to `certs/`. It is
idempotent — an existing certificate is left alone unless it is within 30 days of expiry, or
you pass `--force`:

```bash
./scripts/generate-dev-cert.sh
# or, as a build step:
./mvnw -Pdev-tls generate-resources
```

`certs/` is gitignored. The `dev-tls` profile is opt-in so ordinary builds neither require
`openssl` nor pay for a process fork.

The certificate is **self-signed**, so the same file is both the server's certificate chain
and the client's trust anchor.

### Run with TLS

```bash
export GRPC_TLS_ENABLED=true
export GRPC_TLS_CERT_CHAIN=$PWD/certs/server-cert.pem
export GRPC_TLS_PRIVATE_KEY=$PWD/certs/server-key.pem

./mvnw spring-boot:run -Dspring-boot.run.arguments="--dataset.name=gudidsubset"
```

Only the gRPC port (9095) is affected. REST on 8085 is unchanged.

### Calling a TLS server with grpcurl

Pass the certificate as the CA. Paths below are relative, so run these from the project root:

```bash
# health check
grpcurl -cacert certs/server-cert.pem \
  localhost:9095 grpc.health.v1.Health/Check

# list services / describe a message
grpcurl -cacert certs/server-cert.pem localhost:9095 list
grpcurl -cacert certs/server-cert.pem \
  localhost:9095 describe dev.ikm.tinkar.service.TinkarConceptSearchRequest

# concept search
grpcurl -cacert certs/server-cert.pem \
  -d '{"query":"catheter","max_results":3}' \
  localhost:9095 dev.ikm.tinkar.service.TinkarSearchService/ConceptSearch

# inspect a concept's semantics, using a public_id from the search above
grpcurl -cacert certs/server-cert.pem \
  -d '{"public_id":{"uuids":["94e2b677-9d00-5a45-8fe4-028c4e74bdaa"]}}' \
  localhost:9095 dev.ikm.tinkar.service.IkeKnowledgeGraph/InspectConcept
```

If you prefer a shell variable, **quote it** — an unset variable expands to nothing and
`-cacert` silently swallows the next token, producing a misleading `Too few arguments` /
`Too many arguments` instead of a path error:

```bash
CA="$PWD/certs/server-cert.pem"
grpcurl -cacert "$CA" localhost:9095 grpc.health.v1.Health/Check
```

### Verifying the handshake

```bash
openssl s_client -connect localhost:9095 -alpn h2 -servername localhost </dev/null
```

Expect `TLSv1.3`, `ALPN protocol: h2`, and `Verify return code: 18 (self signed certificate)`
— 18 is expected for a self-signed development certificate.

To confirm TLS is actually *enforced* rather than merely available, both of these must fail:

```bash
grpcurl -plaintext localhost:9095 list   # context deadline exceeded
grpcurl localhost:9095 list              # certificate signed by unknown authority
```

### Notes

- **The certificate must carry a `subjectAltName`.** Java matches SANs only and ignores CN,
  so a CN-only certificate fails the handshake with an error that does not name the cause.
  The script sets `DNS:localhost,IP:127.0.0.1,IP:::1`; override with `CERT_SAN` for other
  hostnames.
- **The `file:` prefix in `application.properties` is required, not decorative.** Those
  properties are Spring `Resource` values; a bare absolute path resolves as a
  ServletContext-relative resource and fails with `Could not open ServletContext resource`.
  The prefix lives in the property, so the environment variables stay plain paths.
- On macOS, `openssl` is **LibreSSL**, whose `x509` has no `-ext` option. To inspect the SAN
  use `openssl x509 -in certs/server-cert.pem -noout -text | grep -A1 'Alternative Name'`.
- For production, use a CA-issued certificate. Nothing here is specific to self-signed
  material — point the same two environment variables at the real files.

---

## Docker

### Dataset Placement

Dataset is mounted into the container at runtime. Unzip your dataset into `data/<folder-name>` in this repo (e.g. `data/gudid`), matching the
`data.path.parent`/`data.path.child` values in `application.properties`.

### Build

```bash
docker build -t tinkar-service .
```

To force a fresh build (no cache):
```bash
docker build --no-cache -t tinkar-service .
```

### Run

Mount your local dataset folder to `/app/data/<folder-name>` inside the container, matching the
`data.path.child` the app is configured to use (`gudid` by default):

```bash
docker run -p 8085:8085 -p 9095:9095 \
  -v "$(pwd)/data/gudid:/app/data/gudid" \
  tinkar-service
```

---

## Architecture

See [docs/architecture.adoc](docs/architecture.adoc) for the full PlantUML component diagram.

---

## Dataset Auto-Provisioning

Instead of manually unzipping a dataset into `data/<name>`, pass `dataset.name` and the
service will download that dataset from Nexus on startup, skipping the download if
`data/<name>` already exists locally.

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--dataset.name=gudidsubset"
```

### Credentials

For a restricted repository, either export the values in your shell:

```bash
export NEXUS_USERNAME=your-username
export NEXUS_PASSWORD=your-password
```

or copy `.env.example` to `.env` (gitignored) and fill in the values.
`spring.config.import` picks it up automatically, no export needed:

```bash
cp .env.example .env
# edit .env with your credentials
```

Default configured datasets in the `application.properties` are:

| Dataset | Repository | Credentials |
|---------|-----------|-------------|
| `gudidsubset` | `ike-public` | **not needed** — served anonymously |
| `gudid` (full SOLOR-GUDID) | `ike-restricted-snapshots` | required |


### Configuration

In `application.properties`, `dataset.nexus.baseUrl` is the default repository, and any
dataset may override it with `dataset.registry.<name>.baseUrl`:

```properties
# Default for datasets that do not override it
dataset.nexus.baseUrl=https://nexus.tinkar.org/repository/ike-restricted-snapshots/

# This dataset resolves from the public repository instead, so it needs no credentials
dataset.registry.gudidsubset.baseUrl=https://nexus.tinkar.org/repository/ike-public/
```

Overriding per dataset rather than changing the default is deliberate: it lets a public
dataset be credential-free without moving the restricted datasets, which are not published
to `ike-public`.

### Adding a new dataset

`dataset.name` is resolved against a small registry in `application.properties` mapping a
short name to the Nexus artifact and the controller that the dataset
uses (`data.controller.name` follows `dataset.name` automatically, the same way
`data.path.child` does, so you don't have to flip it by hand per dataset):

```properties
dataset.registry.gudid.groupId=dev.ikm.tinkar.data
dataset.registry.gudid.artifactId=SOLOR-GUDID
dataset.registry.gudid.classifier=reasoned-sa
dataset.registry.gudid.controllerName=Open SpinedArrayStore

# Smaller dataset for fast local iteration
dataset.registry.gudidsubset.groupId=dev.ikm.tinkar.data
dataset.registry.gudidsubset.artifactId=gudid
dataset.registry.gudidsubset.version=20250804-subset+1.0.0-SNAPSHOT
dataset.registry.gudidsubset.classifier=reasoned-sa
dataset.registry.gudidsubset.controllerName=Open SpinedArrayStore
dataset.registry.gudidsubset.baseUrl=https://nexus.tinkar.org/repository/ike-public/
```

`baseUrl` is optional — omit it and the dataset resolves from `dataset.nexus.baseUrl`.

`version` is optional — omitted, the latest base version is auto-resolved (by comparing
`<version>` strings in the artifact's `maven-metadata.xml`, which only reflects true recency
when versions are date-prefixed). Pin `version` explicitly when an artifactId publishes
multiple variants under the same date (as `gudidsubset`'s `gudid` artifactId does, with `ALL`
and `subset` builds sharing a date but not an ordering).

Add a new `dataset.registry.<name>.*` block to register another dataset.

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
<!-- BEGIN ike-managed: developer-setup -->

## Developer Setup

New to IKE development? The
[Developer Environment guide](https://ike.network/ike-tooling/ike-build-standards/developer-environment.html)
covers IDE configuration, JDK 25 setup, and the tooling conventions
every IKE workspace expects — start there before your first build.
<!-- END ike-managed: developer-setup -->
