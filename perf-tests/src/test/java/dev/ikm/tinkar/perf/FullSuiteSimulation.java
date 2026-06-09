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
 * Combined load test — all IKM service endpoints in one run, producing a
 * single Gatling report.
 *
 * Scenarios and approximate user share (with default -Dusers=10):
 *   Search TOP_COMPONENT        ~20%  — /graphrag/concept-search-sorted
 *   Search SEMANTIC_ALPHA       ~20%  — /graphrag/concept-search-sorted
 *   Concept Graph               ~20%  — search → /entity-graph
 *   Detail Panel                ~15%  — search → /semantics → /concept-change-history
 *   Navigation Panel            ~15%  — search → /children → /descendants
 *   Comments Panel              ~10%  — search → /comments
 *
 * Run:
 *   ../tinkar-core/mvnw gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.FullSuiteSimulation
 *   ../tinkar-core/mvnw gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.FullSuiteSimulation \
 *       -Dbase.url=http://myserver:8085 -Dusers=20 -Dduration=120
 */
public class FullSuiteSimulation extends BaseSimulation {

    private static final List<String> SEARCH_TERMS = List.of(
            "diabetes", "hypertension", "blood pressure", "glucose", "insulin",
            "medication", "fever", "infection", "cardiac", "respiratory",
            "cancer", "inflammation", "chronic", "acute", "syndrome",
            "disorder", "kidney", "liver", "thyroid", "neurological"
    );

    private final Iterator<Map<String, Object>> termFeeder = Stream.generate(() ->
            Map.<String, Object>of(
                    "term", SEARCH_TERMS.get(ThreadLocalRandom.current().nextInt(SEARCH_TERMS.size()))
            )
    ).iterator();

    // ── Search scenarios ──────────────────────────────────────────────

    private final ScenarioBuilder searchByScore = scenario("Search — TOP_COMPONENT")
            .feed(termFeeder)
            .exec(
                    http("concept-search-sorted TOP_COMPONENT")
                            .get("/api/ike/graphrag/concept-search-sorted")
                            .header("Accept", "application/json")
                            .queryParam("query", "#{term}")
                            .queryParam("maxResults", "20")
                            .queryParam("sortBy", "TOP_COMPONENT")
                            .check(status().is(200))
            )
            .pause(1);

    private final ScenarioBuilder searchByAlpha = scenario("Search — SEMANTIC_ALPHA")
            .feed(termFeeder)
            .exec(
                    http("concept-search-sorted SEMANTIC_ALPHA")
                            .get("/api/ike/graphrag/concept-search-sorted")
                            .header("Accept", "application/json")
                            .queryParam("query", "#{term}")
                            .queryParam("maxResults", "20")
                            .queryParam("sortBy", "SEMANTIC_ALPHA")
                            .check(status().is(200))
            )
            .pause(1);

    // ── Concept graph scenario (search → entity-graph) ────────────────

    private final ScenarioBuilder conceptGraph = scenario("Concept Graph — Search → Entity Graph")
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

    // ── Knowledge-graph detail chains (search step shared) ───────────

    private final ChainBuilder kgSearchStep = exec(
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

    private final ScenarioBuilder detailPanel = scenario("Detail Panel — Semantics + Change History")
            .feed(termFeeder)
            .exec(kgSearchStep)
            .doIf(session -> session.contains("conceptId")).then(
                    exec(
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
                    .pause(1)
            );

    private final ScenarioBuilder navigationPanel = scenario("Navigation Panel — Children + Descendants")
            .feed(termFeeder)
            .exec(kgSearchStep)
            .doIf(session -> session.contains("conceptId")).then(
                    exec(
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
                    .pause(1)
            );

    private final ScenarioBuilder commentsPanel = scenario("Comments Panel — Comments")
            .feed(termFeeder)
            .exec(kgSearchStep)
            .doIf(session -> session.contains("conceptId")).then(
                    exec(
                            http("get comments")
                                    .get("/api/ike/knowledgegraph/comments")
                                    .header("Accept", "application/json")
                                    .queryParam("conceptId", "#{conceptId}")
                                    .check(status().is(200))
                    )
                    .pause(1)
            );

    {
        int u = CONCURRENT_USERS;
        int search  = Math.max(1, u / 5);   // ~20% each
        int graph   = Math.max(1, u / 5);   // ~20%
        int detail  = Math.max(1, u / 7);   // ~15%
        int nav     = Math.max(1, u / 7);   // ~15%
        int comments = Math.max(1, u / 10); // ~10%

        setUp(
                searchByScore.injectClosed(
                        rampConcurrentUsers(1).to(search).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(search).during(Duration.ofSeconds(DURATION_SECONDS))
                ),
                searchByAlpha.injectClosed(
                        rampConcurrentUsers(1).to(search).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(search).during(Duration.ofSeconds(DURATION_SECONDS))
                ),
                conceptGraph.injectClosed(
                        rampConcurrentUsers(1).to(graph).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(graph).during(Duration.ofSeconds(DURATION_SECONDS))
                ),
                detailPanel.injectClosed(
                        rampConcurrentUsers(1).to(detail).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(detail).during(Duration.ofSeconds(DURATION_SECONDS))
                ),
                navigationPanel.injectClosed(
                        rampConcurrentUsers(1).to(nav).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(nav).during(Duration.ofSeconds(DURATION_SECONDS))
                ),
                commentsPanel.injectClosed(
                        rampConcurrentUsers(1).to(comments).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(comments).during(Duration.ofSeconds(DURATION_SECONDS))
                )
        ).protocols(httpProtocol)
                .assertions(
                        // Global threshold must accommodate entity-graph (heaviest endpoint)
                        global().responseTime().percentile(95).lte(2000),
                        global().successfulRequests().percent().gte(99.0)
                );
    }
}
