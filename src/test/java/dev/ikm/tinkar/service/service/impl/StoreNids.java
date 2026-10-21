package dev.ikm.tinkar.service.service.impl;

import dev.ikm.tinkar.common.id.Nid;
import dev.ikm.tinkar.common.id.impl.NidLayout;
import dev.ikm.tinkar.entity.EntityHandle;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a nid of the open store written in text as a decimal number. A sequential store's nids
 * are a minus sign and ten digits, a 64-bit store's ten digits or more; a number counts as a nid
 * only when the store holds an entity under it, so a long number that is not one, such as a time
 * in milliseconds, is not taken for one (IKE-Network/ike-issues#1313).
 */
final class StoreNids {

    /** A decimal number long enough to be a nid of either layout, not part of a word or a UUID. */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w-])-?\\d{10,19}(?!\\w)");

    private StoreNids() {
    }

    /** The first number in the text that is the nid of a component the open store holds. */
    static Optional<String> find(String text) {
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            long number;
            try {
                number = Long.parseLong(matcher.group());
            } catch (NumberFormatException tooLong) {
                continue;
            }
            boolean aNidOfTheLayout = NidLayout.active() == NidLayout.SIXTY_FOUR_BIT
                    ? Nid.isValid64(number)
                    : number > Integer.MIN_VALUE && number < Integer.MAX_VALUE && number != 0;
            if (aNidOfTheLayout && !EntityHandle.get(number).isAbsent()) {
                return Optional.of(matcher.group());
            }
        }
        return Optional.empty();
    }
}
