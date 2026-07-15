package dev.ikm.tinkar.service.provisioning;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves the latest snapshot build of a {@link DatasetCoordinate} from a Nexus Maven
 * repository and extracts it to a local directory. Follows the standard two-level Maven
 * snapshot metadata walk:
 * <ol>
 *     <li>{@code <groupPath>/<artifactId>/maven-metadata.xml} — lists available base
 *     versions (e.g. {@code 2026-07-08-SNAPSHOT}); the latest is selected.</li>
 *     <li>{@code <groupPath>/<artifactId>/<baseVersion>/maven-metadata.xml} — resolves the
 *     base version to the timestamped unique version for the requested classifier/type.</li>
 * </ol>
 */
public class MavenSnapshotArtifactResolver {

    private final String baseUrl;
    private final String username;
    private final String password;
    private final HttpClient httpClient;

    public MavenSnapshotArtifactResolver(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.username = username;
        this.password = password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                // HTTP/1.1: large single-file downloads over HTTP/2 have been observed to fail
                // mid-transfer with "Received RST_STREAM: Internal error" in the JDK client.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Resolves and downloads the latest build of {@code coordinate}, extracting it into
     * {@code targetDir}. {@code targetDir} is created if absent; if it already contains
     * files, callers are expected to have already skipped this call.
     */
    public void resolveLatestAndExtract(DatasetCoordinate coordinate, Path targetDir) throws IOException, InterruptedException {
        String artifactPath = coordinate.groupPath() + "/" + coordinate.artifactId();

        String baseVersion = coordinate.hasExplicitVersion() ? coordinate.version() : latestBaseVersion(artifactPath);
        String resolvedVersion = resolveSnapshotVersion(artifactPath, baseVersion, coordinate);

        String fileName = coordinate.artifactId() + "-" + resolvedVersion
                + (coordinate.classifier() == null || coordinate.classifier().isBlank() ? "" : "-" + coordinate.classifier())
                + "." + coordinate.type();
        String downloadUrl = baseUrl + artifactPath + "/" + baseVersion + "/" + fileName;

        Path zipFile = Files.createTempFile("dataset-", "." + coordinate.type());
        try {
            download(downloadUrl, zipFile);
            Files.createDirectories(targetDir);
            extractZip(zipFile, targetDir);
        } finally {
            Files.deleteIfExists(zipFile);
        }
    }

    private String latestBaseVersion(String artifactPath) throws IOException, InterruptedException {
        Document metadata = fetchXml(baseUrl + artifactPath + "/maven-metadata.xml");
        NodeList versionNodes = metadata.getElementsByTagName("version");
        String latest = null;
        for (int i = 0; i < versionNodes.getLength(); i++) {
            String version = versionNodes.item(i).getTextContent().trim();
            if (latest == null || version.compareTo(latest) > 0) {
                latest = version;
            }
        }
        if (latest == null) {
            throw new IOException("No versions found in metadata at " + artifactPath + "/maven-metadata.xml");
        }
        return latest;
    }

    private String resolveSnapshotVersion(String artifactPath, String baseVersion, DatasetCoordinate coordinate) throws IOException, InterruptedException {
        Document metadata = fetchXml(baseUrl + artifactPath + "/" + baseVersion + "/maven-metadata.xml");
        NodeList snapshotVersionNodes = metadata.getElementsByTagName("snapshotVersion");
        String wantedClassifier = coordinate.classifier() == null ? "" : coordinate.classifier();

        for (int i = 0; i < snapshotVersionNodes.getLength(); i++) {
            Element snapshotVersion = (Element) snapshotVersionNodes.item(i);
            String classifier = textOf(snapshotVersion, "classifier");
            String extension = textOf(snapshotVersion, "extension");
            if (wantedClassifier.equals(classifier == null ? "" : classifier)
                    && coordinate.type().equals(extension)) {
                return textOf(snapshotVersion, "value");
            }
        }
        throw new IOException("No snapshotVersion entry matching classifier='" + wantedClassifier
                + "' type='" + coordinate.type() + "' at " + artifactPath + "/" + baseVersion + "/maven-metadata.xml");
    }

    private static String textOf(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private Document fetchXml(String url) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder(url).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("GET " + url + " returned HTTP " + response.statusCode());
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(response.body())));
        } catch (Exception e) {
            throw new IOException("Failed to parse metadata XML from " + url, e);
        }
    }

    private void download(String url, Path target) throws IOException, InterruptedException {
        // Multi-gigabyte dataset artifacts can legitimately take well over an hour on a slow
        // connection, so this gets a much longer timeout than metadata requests.
        HttpRequest request = requestBuilder(url, Duration.ofHours(3)).GET().build();
        HttpResponse<Path> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofFile(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        if (response.statusCode() != 200) {
            throw new IOException("GET " + url + " returned HTTP " + response.statusCode());
        }
    }

    private HttpRequest.Builder requestBuilder(String url) {
        return requestBuilder(url, Duration.ofMinutes(1));
    }

    private HttpRequest.Builder requestBuilder(String url, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        if (username != null && !username.isBlank()) {
            String credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + credentials);
        }
        return builder;
    }

    /**
     * Extracts {@code zipFile} into {@code targetDir}. If every entry in the archive shares
     * a single common top-level directory, that wrapper directory is stripped so the
     * dataset's actual contents land directly in {@code targetDir}.
     */
    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        String commonPrefix = findCommonTopLevelDir(zipFile);

        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (commonPrefix != null && entryName.startsWith(commonPrefix)) {
                    entryName = entryName.substring(commonPrefix.length());
                }
                if (entryName.isBlank()) {
                    continue;
                }
                Path outPath = targetDir.resolve(entryName).normalize();
                if (!outPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry escapes target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String findCommonTopLevelDir(Path zipFile) throws IOException {
        String commonDir = null;
        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash < 0) {
                    return null;
                }
                String topDir = name.substring(0, slash + 1);
                if (commonDir == null) {
                    commonDir = topDir;
                } else if (!commonDir.equals(topDir)) {
                    return null;
                }
            }
        }
        return commonDir;
    }
}
