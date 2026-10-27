package com.ledger.common;

import java.util.UUID;

/** Identifier generation. */
public final class Ids {

    private Ids() {
    }

    /**
     * A new random identifier.
     *
     * <p>UUIDs rather than autoincrement integers: identifiers have to survive a
     * JSON backup/restore round trip without renumbering, because source
     * records point at canonical transactions and a review item points at both.
     * Renumbering on import would silently rewire those links.
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * True for the readable slug ids used by system-seeded rows
     * ({@code food.groceries}, {@code cr-swiggy}). User-created rows always get
     * a {@link #newId()} UUID, so the two namespaces cannot collide.
     */
    public static boolean isSystemId(String id) {
        if (id == null) {
            return false;
        }
        try {
            UUID.fromString(id);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
