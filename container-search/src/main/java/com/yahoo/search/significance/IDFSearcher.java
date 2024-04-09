package com.yahoo.search.significance;

import com.google.inject.Inject;
import com.yahoo.metrics.simple.MetricReceiver;
import com.yahoo.prelude.query.CompositeItem;
import com.yahoo.prelude.query.Item;
import com.yahoo.prelude.query.NullItem;
import com.yahoo.prelude.query.WordItem;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.Searcher;
import com.yahoo.search.searchchain.Execution;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Objects;

public class IDFSearcher extends Searcher {


    public IDFSearcher() {

    }

    /**
     * Search method takes the query and an execution context. This can
     * manipulate the Query object, the Result coming back, issue multiple queries
     * in parallel or serially etc.
     */
    @Override
    public Result search(Query query, Execution execution) {
        if (documentFrequency == null) {
            return execution.search(query);
        }
        setIDF(query.getModel().getQueryTree().getRoot());
        return execution.search(query);
    }


    private void setIDF(Item root) {
        if (root == null || root instanceof NullItem) return;

        if (root instanceof WordItem) {
            int nq_i = documentFrequency.get(((WordItem) root).getWord().toLowerCase());
            double idf = calculateIDF(this.N, nq_i);
            ((WordItem) root).setSignificance(idf);
        } else if (root instanceof CompositeItem) {
            for (int i = 0; i < ((CompositeItem) root).getItemCount(); i++) {
                setIDF(((CompositeItem) root).getItem(i));
            }
        }

    }

    private static double calculateIDF(int N, int nq_i) {
        return Math.log(1 + (N - nq_i + 0.5) / (nq_i + 0.5));
    }
}
