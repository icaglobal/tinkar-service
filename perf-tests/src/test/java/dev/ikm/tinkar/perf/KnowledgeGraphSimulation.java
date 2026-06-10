package dev.ikm.tinkar.perf;

import io.gatling.javaapi.core.ChainBuilder;
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
 * Load test for the Tier-2 knowledge-graph detail endpoints.
 *
 * Models the two most common patterns after a user opens a concept in Komet:
 *   Scenario A — Detail panel: search → /semantics → /concept-change-history
 *   Scenario B — Navigation panel: search → /children → /descendants
 *   Scenario C — Comments panel: search → /comments
 *
 * Users are split 40 / 40 / 20 across the three scenarios.
 *
 * Run:
 *   mvn gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.KnowledgeGraphSimulation
 *   mvn gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.KnowledgeGraphSimulation \
 *       -Dbase.url=http://myserver:8085 -Dusers=10 -Dduration=60
 */
public class KnowledgeGraphSimulation extends BaseSimulation {

    private static final List<String> SEED_TERMS = List.of(
            "diabetes", "blood pressure", "glucose", "medication", "cardiac",
            "hypertension", "insulin", "fever", "infection", "respiratory"
    );

    private final Iterator<Map<String, Object>> termFeeder = Stream.generate(() ->
            Map.<String, Object>of(
                    "term", SEED_TERMS.get(ThreadLocalRandom.current().nextInt(SEED_TERMS.size()))
            )
    ).iterator();

    // Search step shared across all scenarios: extracts the first result's UUID as "conceptId".
    private final ChainBuilder searchStep = exec(
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
    ).pause(1);

    // Chain A: semantics + concept-change-history (description + history panels)
    private final ChainBuilder detailChain = exec(
            http("get semantics")
                    .get("/api/ike/knowledgegraph/semantics")
                    .header("Accept", "application/json")
                    .queryParam("conceptId", "#{conceptId}")
                    .check(status().is(200))
    )
    .pause(1)
    .exec(
            http("get concept change history")
                    .get("/api/ike/knowledgegraph/concept-change-history")
                    .header("Accept", "application/json")
                    .queryParam("conceptId", "#{conceptId}")
                    .check(status().is(200))
    )
    .pause(1);

    // Chain B: children + descendants (navigation/taxonomy panel)
    private final ChainBuilder navigationChain = exec(
            http("get children")
                    .get("/api/ike/knowledgegraph/children")
                    .header("Accept", "application/json")
                    .queryParam("conceptId", "#{conceptId}")
                    .check(status().is(200))
    )
    .pause(1)
    .exec(
            http("get descendants")
                    .get("/api/ike/knowledgegraph/descendants")
                    .header("Accept", "application/json")
                    .queryParam("conceptId", "#{conceptId}")
                    .check(status().is(200))
    )
    .pause(1);

    // Chain C: comments (lighter)
    private final ChainBuilder commentsChain = exec(
            http("get comments")
                    .get("/api/ike/knowledgegraph/comments")
                    .header("Accept", "application/json")
                    .queryParam("conceptId", "#{conceptId}")
                    .check(status().is(200))
    )
    .pause(1);

    private final ScenarioBuilder detailPanel = scenario("Detail Panel — Semantics + Concept Change History")
            .feed(termFeeder)
            .exec(searchStep)
            .doIf(session -> session.contains("conceptId")).then(detailChain);

    private final ScenarioBuilder navigationPanel = scenario("Navigation Panel — Children + Descendants")
            .feed(termFeeder)
            .exec(searchStep)
            .doIf(session -> session.contains("conceptId")).then(navigationChain);

    private final ScenarioBuilder commentsPanel = scenario("Comments Panel — Comments")
            .feed(termFeeder)
            .exec(searchStep)
            .doIf(session -> session.contains("conceptId")).then(commentsChain);

    {
        int heavy = Math.max(1, (CONCURRENT_USERS * 2) / 5);  // 40%
        int light = Math.max(1, CONCURRENT_USERS / 5);         // 20%

        setUp(
                detailPanel.injectClosed(
                        rampConcurrentUsers(1).to(heavy).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(heavy).during(Duration.ofSeconds(DURATION_SECONDS))
                ),
                navigationPanel.injectClosed(
                        rampConcurrentUsers(1).to(heavy).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(heavy).during(Duration.ofSeconds(DURATION_SECONDS))
                ),
                commentsPanel.injectClosed(
                        rampConcurrentUsers(1).to(light).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(light).during(Duration.ofSeconds(DURATION_SECONDS))
                )
        ).protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95).lte(2000),
                        global().successfulRequests().percent().gte(99.0)
                );
    }
}
