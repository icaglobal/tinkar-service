package dev.ikm.tinkar.perf;

import io.gatling.javaapi.core.ScenarioBuilder;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Load test for the concept-open workflow: search for a term, take the first
 * result, then load the full entity graph. This mirrors what Komet does when
 * a user clicks a search result to open the concept detail panel.
 *
 * The entity-graph endpoint returns application/x-protobuf, so responses are
 * checked for HTTP 200 only — not JSON content.
 *
 * Run:
 *   mvn gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.ConceptGraphSimulation
 *   mvn gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.ConceptGraphSimulation \
 *       -Dbase.url=http://myserver:8085 -Dusers=5 -Dduration=60
 */
public class ConceptGraphSimulation extends BaseSimulation {

    private static final List<String> SEED_TERMS = List.of(
            "diabetes", "blood pressure", "glucose", "medication", "cardiac"
    );

    private final Iterator<Map<String, Object>> termFeeder = Stream.generate(() ->
            Map.<String, Object>of(
                    "term", SEED_TERMS.get(ThreadLocalRandom.current().nextInt(SEED_TERMS.size()))
            )
    ).iterator();

    // Step 1: search — extract the first result's conceptId (UUID string).
    // Step 2: load full entity graph for that concept (protobuf response).
    // The doIf guard skips step 2 gracefully when the search returns no results.
    private final ScenarioBuilder openConceptScenario = scenario("Search → Open Concept Graph")
            .feed(termFeeder)
            .exec(
                    http("search for concept")
                            .get("/api/ike/graphrag/concept-search-sorted")
                            .header("Accept", "application/json")
                            .queryParam("query", "#{term}")
                            .queryParam("maxResults", "1")
                            .queryParam("sortBy", "TOP_COMPONENT")
                            .check(
                                    status().is(200),
                                    jsonPath("$.groupedResults[0].publicId[0]")
                                            .optional()
                                            .saveAs("conceptId")
                            )
            )
            .pause(1)
            .doIf(session -> session.contains("conceptId")).then(
                    exec(
                            http("load entity graph")
                                    .get("/api/ike/knowledgegraph/entity-graph")
                                    .queryParam("conceptId", "#{conceptId}")
                                    .check(status().is(200))
                    )
                    .pause(1)
            );

    {
        setUp(
                openConceptScenario.injectClosed(
                        rampConcurrentUsers(1).to(CONCURRENT_USERS).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(CONCURRENT_USERS).during(Duration.ofSeconds(DURATION_SECONDS))
                )
        ).protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95).lte(1000),
                        global().successfulRequests().percent().gte(99.0)
                );
    }
}
