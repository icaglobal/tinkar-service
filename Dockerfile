# ============================================
# Stage 1: Build tinkar-service
# ============================================
# Force amd64 platform - RocksDB only has linux64 (x86_64) natives, not ARM64
FROM --platform=linux/amd64 eclipse-temurin:25-jdk AS builder

WORKDIR /build/tinkar-service

# Copy local source and build. rocks-kb, tinkar-core, and tinkar-schema are
# resolved as ordinary Maven dependencies from the tinkar-nexus repository
# declared in pom.xml, so no sibling repo source is needed here.
COPY . .
RUN ./mvnw install -DskipTests -Dmaven.javadoc.skip=true -B -q

# ============================================
# Stage 2: Runtime
# ============================================
# Force amd64 platform to match the build
FROM --platform=linux/amd64 eclipse-temurin:25-jre

WORKDIR /app

# Create non-root user for security
RUN groupadd --system tinkar && \
    useradd --system --gid tinkar --shell /bin/false tinkar

# Copy the Spring Boot fat jar from builder
COPY --from=builder /build/tinkar-service/target/tinkar-service-*.jar app.jar

# Create data directory for volume mount
RUN mkdir -p /app/data && chown -R tinkar:tinkar /app

# Switch to non-root user
USER tinkar

# Expose REST (8085) and gRPC (9095) ports
EXPOSE 8085 9095

# Health check for REST endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8085/actuator/health || exit 1

# Remove stale Lucene lock files that may have been baked in from a previous run,
# then start the application.
ENTRYPOINT ["sh", "-c", "find /app/data -name 'write.lock' -delete && exec java --enable-preview -jar app.jar \"$@\"", "--"]
