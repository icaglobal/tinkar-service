package dev.ikm.tinkar.perf;

import io.gatling.javaapi.core.FeederBuilder;
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
 * Load test for concept search endpoints.
 *
 * Simulates concurrent users running concept-search-sorted queries with varying
 * terms. Mirrors the primary search workload from Komet and tinkar-ui.
 *
 * Run:
 *   mvn gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.SearchSimulation
 *   mvn gatling:test -Dgatling.simulationClass=dev.ikm.tinkar.perf.SearchSimulation \
 *       -Dbase.url=http://myserver:8085 -Dusers=20 -Dduration=120
 */
public class SearchSimulation extends BaseSimulation {

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

    private final ScenarioBuilder searchByScore = scenario("Search — Top Component by Score")
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

    private final ScenarioBuilder searchByAlpha = scenario("Search — Semantic Alphabetical")
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

    {
        int splitUsers = Math.max(1, CONCURRENT_USERS / 2);

        setUp(
                searchByScore.injectClosed(
                        rampConcurrentUsers(1).to(splitUsers).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(splitUsers).during(Duration.ofSeconds(DURATION_SECONDS))
                ),
                searchByAlpha.injectClosed(
                        rampConcurrentUsers(1).to(splitUsers).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantConcurrentUsers(splitUsers).during(Duration.ofSeconds(DURATION_SECONDS))
                )
        ).protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95).lte(200),
                        global().successfulRequests().percent().gte(99.0)
                );
    }
}
