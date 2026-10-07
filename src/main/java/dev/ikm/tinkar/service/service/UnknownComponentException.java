package dev.ikm.tinkar.service.service;

import dev.ikm.tinkar.common.id.PublicId;

import java.util.UUID;

/**
 * Thrown when a request names a component the knowledge base does not hold
 * ({@code IKE-Network/ike-issues#1188}).
 *
 * <p>The message identifies the component by the UUIDs the request gave, so it can be returned
 * to the caller as it is.
 */
public class UnknownComponentException extends RuntimeException {

    /**
     * Refuses a public id the knowledge base holds no component for. The message names the
     * public id by its UUIDs.
     *
     * @param publicId the public id the request named
     */
    public UnknownComponentException(PublicId publicId) {
        super("No component in this knowledge base has the public id " + uuidsOf(publicId));
    }

    /** The UUIDs of a public id, joined by commas. */
    private static String uuidsOf(PublicId publicId) {
        UUID[] uuids = publicId.asUuidArray();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < uuids.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(uuids[i]);
        }
        return text.toString();
    }
}
