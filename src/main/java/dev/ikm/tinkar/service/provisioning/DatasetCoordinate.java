package dev.ikm.tinkar.service.provisioning;

/**
 * Maven coordinate identifying a dataset artifact hosted on the Nexus data repository.
 *
 * @param version explicit base version (e.g. {@code 20250804-subset+1.0.0-SNAPSHOT}), or
 *                {@code null}/blank to resolve the latest base version automatically.
 *                Auto-resolution picks the lexicographically greatest {@code <version>} in
 *                the artifact's {@code maven-metadata.xml}, which only reflects true
 *                recency when versions are date-prefixed; pin a version explicitly when an
 *                artifactId has multiple concurrent variants (e.g. a full build and a
 *                subset build published under the same date).
 */
public record DatasetCoordinate(String groupId, String artifactId, String version, String classifier, String type) {

    public String groupPath() {
        return groupId.replace('.', '/');
    }

    public boolean hasExplicitVersion() {
        return version != null && !version.isBlank();
    }
}
