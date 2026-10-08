package dev.ikm.tinkar.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gate on the service's own sources: none of them makes a call that writes a nid into the
 * text of a response ({@code IKE-Network/ike-issues#1177}).
 *
 * <p>A nid means nothing outside the store that assigned it, and in gRPC mode the text of a
 * response is what the assistant's tools return, so it is kept. The calls below are the ones
 * that put a nid into text: each answers with the nid when a name does not resolve, or always.
 * They are a method call away on every view calculator, the compiler has no objection to them,
 * and on a store whose components all have descriptions they never show the nid, so no test of
 * behavior catches one being added. This test reads the sources instead.
 *
 * <p>When it fails, use the replacement it names. {@code TinkarServiceImpl} holds the name and
 * the identifier the service writes for a component.
 *
 * <p>Three calls are known and allowed for now ({@link #KNOWN}). The gate fails when their
 * number changes in either direction, so the list stays true.
 */
class NoNidInResponseTextGateTest {

    /** The service's main sources, relative to the module directory the tests run in. */
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** A call that writes a nid into text, and what to use in its place. */
    private record Forbidden(String what, Pattern call, String instead) {
    }

    private static final List<Forbidden> FORBIDDEN = List.of(
            new Forbidden("a calculator method that answers with the nid when no description resolves",
                    Pattern.compile("\\w*OrNid\\s*\\("),
                    "TinkarServiceImpl.getDescriptionForNid"),
            new Forbidden("the store's default text, which is <nid> when there is no description",
                    Pattern.compile("PrimitiveData\\s*\\.\\s*text(?:Fast|WithNid|List)?\\s*\\("),
                    "TinkarServiceImpl.getDescriptionForNid"),
            new Forbidden("the calculator's semantic text, which names its parts with the OrNid methods",
                    Pattern.compile("\\.\\s*getSemanticText\\s*\\("),
                    "TinkarServiceImpl.getDescriptionForNid"),
            new Forbidden("a nid appended to a label",
                    Pattern.compile("\"[^\"\\n]*\\bnid\\s*[=:]?\\s*\"\\s*\\+"),
                    "TinkarServiceImpl.identifierFor"),
            new Forbidden("a coordinate's own text, which takes each name from the store's default text",
                    Pattern.compile("\\.\\s*toUserString\\s*\\("),
                    "text composed from TinkarServiceImpl.getDescriptionForNid"));

    /**
     * Calls the gate allows, by source file: how many, and why.
     *
     * <p>The reasoner results send the stamp, logic, and edit coordinates of the run as text
     * for display. tinkar-core writes that text, and it holds {@code <nid>} for a path, module,
     * or pattern that has no description. It changes when tinkar-core's own name fallback
     * changes; until then these three calls remain.
     */
    private static final Map<String, Integer> KNOWN = Map.of(
            "dev/ikm/tinkar/service/controller/admin/AdminGrpcController.java", 3);

    /** Block comments and javadoc, which may name the forbidden calls in prose. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** A line comment, from {@code //} to the end of the line, unless the slashes follow a colon (a URL). */
    private static final Pattern LINE_COMMENT = Pattern.compile("(?<!:)//[^\\n]*");

    @Test
    void noMainSourceCallsWhatWritesANidIntoText() throws IOException {
        assertThat(MAIN_SOURCES)
                .as("The gate reads " + MAIN_SOURCES.toAbsolutePath() + "; run the tests from the module directory.")
                .isDirectory();

        List<String> violations = new ArrayList<>();
        Map<String, Integer> found = new TreeMap<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                scanned++;
                String name = MAIN_SOURCES.relativize(file).toString().replace('\\', '/');
                String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
                for (Forbidden forbidden : FORBIDDEN) {
                    Matcher matcher = forbidden.call().matcher(code);
                    while (matcher.find()) {
                        found.merge(name, 1, Integer::sum);
                        if (!KNOWN.containsKey(name)) {
                            violations.add(name + ":" + lineOf(code, matcher.start())
                                    + "  " + matcher.group().strip() + "  — " + forbidden.what()
                                    + "; use " + forbidden.instead());
                        }
                    }
                }
            }
        }
        assertThat(scanned).as("sources the gate read; too few means it is not reading the service").isGreaterThan(30);
        assertThat(violations).as("Calls that write a nid into text").isEmpty();
        for (Map.Entry<String, Integer> known : KNOWN.entrySet()) {
            assertThat(found.getOrDefault(known.getKey(), 0))
                    .as("known calls in " + known.getKey() + "; update KNOWN when one is added or removed")
                    .isEqualTo(known.getValue());
        }
    }

    @Test
    void theGateRecognizesEachForbiddenCall() {
        // The gate is only as good as its patterns: each must match the call it stands for, and
        // none may match the replacement or a comment that names the call.
        assertThat(hits("return calc.languageCalculator().getDescriptionTextOrNid(nid);")).isEqualTo(1);
        assertThat(hits("return lc.getFullyQualifiedDescriptionTextWithFallbackOrNid(nid);")).isEqualTo(1);
        assertThat(hits("sb.append(PrimitiveData.text(nid));")).isEqualTo(1);
        assertThat(hits("sb.append(PrimitiveData.textWithNid(nid));")).isEqualTo(1);
        assertThat(hits("builder.append(PrimitiveData.textList(nids));")).isEqualTo(1);
        assertThat(hits("Optional<String> t = lc.getSemanticText(nid);")).isEqualTo(1);
        assertThat(hits("return \"nid: \" + nid;")).isEqualTo(1);
        assertThat(hits("return \"[nid \" + nid + \"]\";")).isEqualTo(1);
        assertThat(hits("builder.setStampCoordinateText(view.stampCoordinate().toUserString());")).isEqualTo(1);

        assertThat(hits("String s = getDescriptionForNid(nid, calc);")).isZero();
        assertThat(hits("log.warn(\"Failed to get STAMP data for nid {}: {}\", nid, ex.getMessage());")).isZero();
        assertThat(hits("// the calculator's getDescriptionTextOrNid(nid) answers with the nid")).isZero();
        assertThat(hits("/** Replaces {@code lc.getSemanticText(nid)}. */ int x;")).isZero();
        assertThat(hits("return \"[\" + UNIDENTIFIED + \"]\";")).isZero();
    }

    /** How many forbidden calls the gate finds in a piece of source. */
    private static int hits(String source) {
        String code = withoutComments(source);
        int hits = 0;
        for (Forbidden forbidden : FORBIDDEN) {
            Matcher matcher = forbidden.call().matcher(code);
            while (matcher.find()) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * The source with its comments blanked. Line breaks are kept, so a match still reports the
     * line it is on.
     */
    private static String withoutComments(String source) {
        String withoutBlocks = BLOCK_COMMENT.matcher(source)
                .replaceAll(match -> match.group().replaceAll("[^\\n]", " "));
        return LINE_COMMENT.matcher(withoutBlocks).replaceAll("");
    }

    /** The one-based line of an offset. */
    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
