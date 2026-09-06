package com.duckcq.query;

/**
 * One row of a join: an object from each collection.
 *
 * @param left  the object from the collection the join started from
 * @param right the object it matched in the other collection
 */
public record JoinPair<L, R>(L left, R right) {
}
