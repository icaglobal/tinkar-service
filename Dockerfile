# ============================================
# Stage 1: Build rocks-kb and tinkar-core
# ============================================
# Force amd64 platform - RocksDB only has linux64 (x86_64) natives, not ARM64
FROM --platform=linux/amd64 eclipse-temurin:25-jdk AS builder

WORKDIR /build

# Install protoc and protoc-gen-doc (required for proto file generation)
RUN apt-get update && apt-get install -y --no-install-recommends \
    protobuf-compiler \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install protoc-gen-doc
RUN curl -sSL https://github.com/pseudomuto/protoc-gen-doc/releases/download/v1.5.1/protoc-gen-doc_1.5.1_linux_amd64.tar.gz \
    | tar -xz -C /usr/local/bin

# Copy rocks-kb first (it's a dependency for tinkar-core)
COPY rocks-kb/ /build/rocks-kb/

# Build and install rocks-kb to local Maven repo
WORKDIR /build/rocks-kb
RUN ./mvnw install -DskipTests -B -q

# Copy tinkar-schema (needed by protobuf plugin in tinkar-core/service)
COPY tinkar-schema/ /build/tinkar-schema/

# Copy tinkar-core
COPY tinkar-core/ /build/tinkar-core/

# Build tinkar-core (skip Javadoc to avoid preview feature issues)
WORKDIR /build/tinkar-core
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
COPY --from=builder /build/tinkar-core/service/target/service-*.jar app.jar

# Create data directory for volume mount
RUN mkdir -p /app/data && chown -R tinkar:tinkar /app

# Switch to non-root user
USER tinkar

# Expose REST (8085) and gRPC (9095) ports
EXPOSE 8085 9095

# Health check for REST endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8085/actuator/health || exit 1

# Run with preview features enabled
ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
