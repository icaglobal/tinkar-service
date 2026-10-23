package dev.ikm.tinkar.service.auth;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.navigation.NavigationCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.ViewCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.service.service.CoordinateFactory;
import dev.ikm.tinkar.service.service.KnownComponents;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The authors a caller can sign in as: the ones Komet's author screen lists.
 *
 * <p>That is the leaf concepts below {@link KernelConcept#USER} under stated and inferred
 * navigation, or the user concept itself when it has none. The service and Komet read the same
 * knowledge base, so they list the same authors.
 */
public final class Authors {

    private Authors() {
    }

    /**
     * An author.
     *
     * @param id    the UUID the service names the author by: its least
     * @param uuids every UUID of the author
     * @param name  its preferred name
     * @param nid   its nid in this store; never sent to a caller
     */
    public record Author(String id, List<UUID> uuids, String name, long nid) {
        public Author {
            uuids = List.copyOf(uuids);
        }
    }

    /** Every author, by name. */
    public static List<Author> list() {
        ViewCalculatorWithCache calc = statedAndInferred();
        List<Author> authors = new ArrayList<>();
        calc.descendentsOf(KernelConcept.USER.nid()).forEach(nid -> {
            if (calc.childrenOf(nid).isEmpty()
                    && EntityHandle.get(nid).orNull() instanceof ConceptEntity<?> concept) {
                authors.add(author(concept, calc));
            }
        });
        if (authors.isEmpty()) {
            authors.add(author(EntityHandle.get(KernelConcept.USER).expectConcept(), calc));
        }
        authors.sort(Comparator.comparing(Author::name, String.CASE_INSENSITIVE_ORDER));
        return authors;
    }

    /**
     * The author a caller names, by any of its UUIDs or by its name, ignoring case.
     *
     * @param nameOrUuid a UUID of the author, or its name
     * @return the author, or empty when no author has that UUID or name, or two have the name
     */
    public static Optional<Author> find(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) {
            return Optional.empty();
        }
        String wanted = nameOrUuid.strip();
        List<Author> authors = list();
        try {
            UUID uuid = UUID.fromString(wanted);
            return authors.stream().filter(author -> author.uuids().contains(uuid)).findFirst();
        } catch (IllegalArgumentException notAUuid) {
            List<Author> named = authors.stream()
                    .filter(author -> author.name().toLowerCase(Locale.ROOT).equals(wanted.toLowerCase(Locale.ROOT)))
                    .toList();
            return named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
        }
    }

    /**
     * The nid of the author a token names, when it is one of the authors.
     *
     * @param authorId the author's UUID, as a token's subject holds it
     * @return the nid, or empty when the knowledge base does not hold it
     */
    public static OptionalLong nidOf(String authorId) {
        try {
            return KnownComponents.nid(PublicIds.of(UUID.fromString(authorId)));
        } catch (IllegalArgumentException notAUuid) {
            return OptionalLong.empty();
        }
    }

    private static Author author(ConceptEntity<?> concept, ViewCalculatorWithCache calc) {
        List<UUID> uuids = concept.publicId().asUuidList().castToList();
        String id = concept.publicId().leastUuid().toString();
        String name = calc.languageCalculator().getRegularDescriptionText(concept.nid())
                .or(() -> calc.languageCalculator().getFullyQualifiedNameText(concept.nid()))
                .orElse(id);
        return new Author(id, uuids, name, concept.nid());
    }

    /** The default view, navigating by stated as well as inferred relationships, as Komet's author screen does. */
    private static ViewCalculatorWithCache statedAndInferred() {
        ViewCalculatorWithCache calc = CoordinateFactory.defaultCalculator();
        NavigationCoordinateRecord navigation = calc.navigationCoordinate().withNavigationPatternNids(
                calc.navigationCoordinate().navigationPatternNids().with(KernelPattern.STATED_NAVIGATION_PATTERN.nid()));
        ViewCoordinateRecord view = calc.viewCoordinateRecord().withNavigationCoordinate(navigation);
        return new ViewCalculatorWithCache(view);
    }
}
