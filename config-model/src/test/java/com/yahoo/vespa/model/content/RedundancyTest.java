package com.yahoo.vespa.model.content;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author bjorncs
 */
public class RedundancyTest {

    @Test
    public void effectively_globally_distributed_is_correct() {
        assertFalse(createRedundancy(4, 2, 10).isEffectivelyGloballyDistributed());
        assertFalse(createRedundancy(5, 1, 10).isEffectivelyGloballyDistributed());
        assertFalse(createRedundancy(5, 2, 12).isEffectivelyGloballyDistributed());
        assertTrue(createRedundancy(5, 2, 10).isEffectivelyGloballyDistributed());
        assertTrue(createRedundancy(5, 3, 10).isEffectivelyGloballyDistributed());
        assertTrue(createRedundancy(1, 1, 1).isEffectivelyGloballyDistributed());
    }

    private static Redundancy createRedundancy(int redundancy, int implicitGroups, int totalNodes) {
        Redundancy r = new Redundancy(redundancy, 1);
        r.setImplicitGroups(implicitGroups);
        r.setTotalNodes(totalNodes);
        return r;
    }

}
