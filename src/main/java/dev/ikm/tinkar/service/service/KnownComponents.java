package dev.ikm.tinkar.service.service;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.PrimitiveDataService;
import dev.ikm.tinkar.entity.EntityService;

import java.util.OptionalInt;

/**
 * Finds the component a public id names, when the public id comes from outside the knowledge
 * base: an id in a request, or the path and modules of a coordinate in one
 * ({@code IKE-Network/ike-issues#1188}).
 *
 * <p>Such a public id may name a component the knowledge base does not hold. Asking the store
 * for its nid assigns one anyway in a spined-array or an ephemeral store, and the assignment is
 * kept: a request would add to the store, and would then be answered as though the component
 * existed. Rocks refuses the same request. This lookup asks the store first whether it knows
 * the public id, and assigns nothing for one it does not know, so every store answers alike.
 *
 * <p>The knowledge base holds a component when its public id has a nid and there is an entity
 * for that nid. A nid alone is not enough: a public id gets one when another component refers
 * to it, whether or not the component it names was ever written.
 */
public final class KnownComponents {

    private KnownComponents() {
    }

    /**
     * The nid of the component a public id names, when the knowledge base holds the component.
     *
     * @param publicId a public id from outside the knowledge base
     * @return the nid, or empty when the knowledge base does not hold the component; no nid is
     *         assigned
     */
    public static OptionalInt nid(PublicId publicId) {
        PrimitiveDataService store = PrimitiveData.get();
        if (!store.hasPublicId(publicId)) {
            return OptionalInt.empty();
        }
        int nid = store.nidForPublicId(publicId);
        return EntityService.get().getEntityFast(nid) == null ? OptionalInt.empty() : OptionalInt.of(nid);
    }

    /**
     * The nid of the component a public id names.
     *
     * @param publicId a public id from outside the knowledge base
     * @return the nid
     * @throws UnknownComponentException if the knowledge base does not hold the component; no
     *                                   nid is assigned
     */
    public static int nidOrRefuse(PublicId publicId) {
        return nid(publicId).orElseThrow(() -> new UnknownComponentException(publicId));
    }
}
