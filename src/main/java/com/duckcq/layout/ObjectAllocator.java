package com.duckcq.layout;

import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;

/**
 * Allocates instances without invoking a constructor, so that
 * {@link ColumnarLayout#reflective(Class)} can rebuild objects which have no no-arg constructor.
 * Uses Objenesis, which CQEngine already depends on through Kryo.
 */
final class ObjectAllocator {

    private static final Objenesis OBJENESIS = new ObjenesisStd(true);

    private ObjectAllocator() {
    }

    static <O> O allocate(Class<O> type) {
        return OBJENESIS.newInstance(type);
    }
}
