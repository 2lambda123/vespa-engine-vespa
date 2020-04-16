// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.querytransform;

import static com.yahoo.prelude.querytransform.NormalizingSearcher.ACCENT_REMOVAL;
import static com.yahoo.prelude.querytransform.StemmingSearcher.STEMMING;

import java.util.Collection;

import com.yahoo.component.chain.dependencies.After;
import com.yahoo.component.chain.dependencies.Provides;
import com.yahoo.prelude.Index;
import com.yahoo.prelude.IndexFacts;
import com.yahoo.prelude.query.WordItem;

/**
 * Transform terms in query tree to lower case based on Vespa index settings.
 *
 * @author Steinar Knutsen
 */
@After({ STEMMING, ACCENT_REMOVAL })
@Provides(VespaLowercasingSearcher.LOWERCASING)
public class VespaLowercasingSearcher extends LowercasingSearcher {

    public static final String LOWERCASING = "LowerCasing";

    public VespaLowercasingSearcher(LowercasingConfig cfg) {
        super(cfg);
    }

    @Override
    public boolean shouldLowercase(WordItem word, IndexFacts.Session indexFacts) {
        if (word.isLowercased()) return false;

        Index index = indexFacts.getIndex(word.getIndexName());
        return index.isLowercase() || index.isAttribute();
    }

    @Override
    public boolean shouldLowercase(String commonPath, WordItem word, IndexFacts.Session indexFacts) {
        if (word.isLowercased()) return false;

        StringBuilder sb = new StringBuilder();
        sb.append(commonPath).append(".").append(word.getIndexName());
        Index index = indexFacts.getIndex(sb.toString());
        return index.isLowercase() || index.isAttribute();
    }

}
