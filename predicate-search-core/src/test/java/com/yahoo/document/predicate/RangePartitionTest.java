// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.predicate;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

/**
 * @author <a href="mailto:magnarn@yahoo-inc.com">Magnar Nedland</a>
 */
public class RangePartitionTest {

    @Test
    public void requireThatRangePartitionIsAValue() {
        assertTrue(PredicateValue.class.isAssignableFrom(RangePartition.class));
    }

    @Test
    public void requireThatConstructorsWork() {
        RangePartition part = new RangePartition("foo=10-19");
        assertEquals("foo=10-19", part.getLabel());
        part = new RangePartition("foo", 10, 19, false);
        assertEquals("foo=10-19", part.getLabel());
        part = new RangePartition("foo", 10, 19, true);
        assertEquals("foo=-19-10", part.getLabel());
    }

    @Test
    public void requireThatHashCodeIsImplemented() {
        assertEquals(new RangePartition("foo=0-9").hashCode(), new RangePartition("foo=0-9").hashCode());
    }

    @Test
    public void requireThatEqualsIsImplemented() {
        RangePartition lhs = new RangePartition("foo=10-19");
        assertTrue(lhs.equals(lhs));
        assertFalse(lhs.equals(new Object()));

        RangePartition rhs = new RangePartition("bar=1000-1999");
        assertFalse(lhs.equals(rhs));
        rhs = new RangePartition("foo=10-19");
        assertTrue(lhs.equals(rhs));
    }

    @Test
    public void requireThatKeyIsEscapedInToString() {
        assertEquals("foo=10-19", new RangePartition("foo=10-19").toString());
        assertEquals("'\\foo=10-19'", new RangePartition("\foo=10-19").toString());
        assertEquals("'\\x27foo\\x27=10-19'", new RangePartition("'foo'=10-19").toString());
    }
}
