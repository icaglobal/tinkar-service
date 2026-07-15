package dev.ikm.tinkar.service.provisioning;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * When {@code dataset.name} is set (e.g. {@code --dataset.name=gudid}), resolves that short
 * name against the {@code dataset.registry.*} coordinates in {@code application.properties},
 * downloads the latest snapshot build of the matching artifact from the Nexus dataset
 * repository if it isn't already present under {@code data.path.parent}, and propagates the
 * name to {@code data.path.child} so {@code TinkarPrimitiveImpl} picks it up unchanged.
 * <p>
 * Runs before the ApplicationContext is created, so the dataset is guaranteed to exist on
 * disk by the time any bean tries to open it.
 * <p>
 * Uses {@code System.out}/{@code System.err} rather than a logger: this runs before Spring
 * Boot's logging system is initialized, so regular log calls are silently dropped.
 */
public class DatasetProvisioningEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PREFIX = "[dataset-provisioning] ";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String datasetName = environment.getProperty("dataset.name");
        if (datasetName == null || datasetName.isBlank()) {
            return;
        }

        String dataPathParent = environment.getProperty("data.path.parent", "data");
        Path targetDir = Path.of(dataPathParent, datasetName);

        // Make data.path.child (and, if registered, data.controller.name) follow
        // dataset.name, at the highest property precedence, so callers only need to pass
        // one flag regardless of which storage engine the dataset requires.
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("data.path.child", datasetName);
        String controllerName = environment.getProperty("dataset.registry." + datasetName + ".controllerName");
        if (controllerName != null && !controllerName.isBlank()) {
            overrides.put("data.controller.name", controllerName);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("datasetProvisioning", overrides));

        if (Files.isDirectory(targetDir) && isNonEmpty(targetDir)) {
            System.out.println(PREFIX + "Dataset '" + datasetName + "' already present at "
                    + targetDir.toAbsolutePath() + ", skipping download");
            return;
        }

        DatasetCoordinate coordinate = lookupCoordinate(environment, datasetName);
        String baseUrl = environment.getProperty("dataset.nexus.baseUrl", "");
        if (baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "dataset.name=" + datasetName + " was requested but dataset.nexus.baseUrl is not set. " +
                            "Set it to the Nexus dataset repository URL, e.g. https://nexus.tinkar.org/repository/<repo-id>/");
        }
        String username = environment.getProperty("dataset.nexus.username", "");
        String password = environment.getProperty("dataset.nexus.password", "");
        if (username.isBlank()) {
            System.err.println(PREFIX + "dataset.nexus.username is not set; download will be attempted anonymously");
        }

        System.out.println(PREFIX + "Resolving latest build of " + coordinate.groupId() + ":" + coordinate.artifactId()
                + " (classifier=" + coordinate.classifier() + ") for dataset '" + datasetName + "'");
        try {
            new MavenSnapshotArtifactResolver(baseUrl, username, password)
                    .resolveLatestAndExtract(coordinate, targetDir);
            System.out.println(PREFIX + "Dataset '" + datasetName + "' downloaded and extracted to " + targetDir.toAbsolutePath());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to provision dataset '" + datasetName + "' from " + baseUrl, e);
        }
    }

    private static DatasetCoordinate lookupCoordinate(ConfigurableEnvironment environment, String datasetName) {
        String prefix = "dataset.registry." + datasetName + ".";
        String groupId = environment.getProperty(prefix + "groupId");
        String artifactId = environment.getProperty(prefix + "artifactId");
        if (groupId == null || artifactId == null) {
            throw new IllegalStateException("No dataset registry entry for '" + datasetName +
                    "'. Add " + prefix + "groupId and " + prefix + "artifactId to application.properties.");
        }
        String version = environment.getProperty(prefix + "version", "");
        String classifier = environment.getProperty(prefix + "classifier", "");
        String type = environment.getProperty(prefix + "type", "zip");
        return new DatasetCoordinate(groupId, artifactId, version, classifier, type);
    }

    private static boolean isNonEmpty(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }
}
