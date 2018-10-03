// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.grouping.result;

import java.util.Arrays;

/**
 * This class is used in {@link Group} instances where the identifying
 * expression evaluated to a {@link com.yahoo.search.grouping.request.RawBucket}.
 *
 * @author Ulf Lilleengen
 */
public class RawBucketId extends BucketGroupId<byte[]> {

    /**
     * Constructs a new instance of this class.
     *
     * @param from The identifying inclusive-from raw buffer.
     * @param to   The identifying exclusive-to raw buffer.
     */
    public RawBucketId(byte[] from, byte[] to) {
        super("raw_bucket", from, Arrays.toString(from), to, Arrays.toString(to));
    }
}
