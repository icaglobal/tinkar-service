package dev.ikm.tinkar.perf;

import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * Shared configuration for all IKM service simulations.
 *
 * System properties (override at runtime with -D):
 *   base.url   — target server base URL  (default: http://localhost:8085)
 *   users      — peak concurrent users    (default: 10)
 *   duration   — hold duration in seconds (default: 60)
 */
abstract class BaseSimulation extends Simulation {

    static final String BASE_URL =
            System.getProperty("base.url", "http://localhost:8085");

    static final int CONCURRENT_USERS =
            Integer.parseInt(System.getProperty("users", "10"));

    static final int RAMP_SECONDS = 10;

    static final int DURATION_SECONDS =
            Integer.parseInt(System.getProperty("duration", "60"));

    final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .shareConnections();
}
