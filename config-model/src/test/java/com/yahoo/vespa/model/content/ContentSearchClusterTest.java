// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.content;

import com.yahoo.vespa.config.search.core.ProtonConfig;
import com.yahoo.vespa.model.content.cluster.ContentCluster;
import com.yahoo.vespa.model.content.utils.ContentClusterBuilder;
import com.yahoo.vespa.model.content.utils.SearchDefinitionBuilder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.yahoo.config.model.test.TestUtil.joinLines;
import static com.yahoo.vespa.model.content.utils.ContentClusterUtils.createCluster;
import static com.yahoo.vespa.model.content.utils.SearchDefinitionBuilder.createSearchDefinitions;
import static junit.framework.TestCase.assertEquals;

/**
 * Unit tests for content search cluster.
 *
 * @author geirst
 */
public class ContentSearchClusterTest {

    private static double EPSILON = 0.000001;

    private static ContentCluster createClusterWithOneDocumentType() throws Exception {
        return createCluster(new ContentClusterBuilder().getXml());
    }

    private static ContentCluster createClusterWithTwoDocumentType() throws Exception {
        return createCluster(new ContentClusterBuilder().docTypes("foo", "bar").getXml(),
                createSearchDefinitions("foo", "bar"));
    }

    private static ContentCluster createClusterWithGlobalType() throws Exception {
        return createCluster(new ContentClusterBuilder().docTypes(Arrays.asList(
                new ContentClusterBuilder.DocType("global", true),
                new ContentClusterBuilder.DocType("regular"))).getXml(),
                createSearchDefinitions("global", "regular"));
    }

    private static ProtonConfig getProtonConfig(ContentCluster cluster) {
        ProtonConfig.Builder protonCfgBuilder = new ProtonConfig.Builder();
        cluster.getSearch().getConfig(protonCfgBuilder);
        return new ProtonConfig(protonCfgBuilder);
    }

    private static void assertProtonResourceLimits(double expDiskLimit, double expMemoryLimits, String clusterXml) throws Exception {
        ProtonConfig cfg = getProtonConfig(createCluster(clusterXml));
        assertEquals(expDiskLimit, cfg.writefilter().disklimit(), EPSILON);
        assertEquals(expMemoryLimits, cfg.writefilter().memorylimit(), EPSILON);
    }

    @Test
    public void requireThatProtonInitializeThreadsIsSet() throws Exception {
        assertEquals(2, getProtonConfig(createClusterWithOneDocumentType()).initialize().threads());
        assertEquals(3, getProtonConfig(createClusterWithTwoDocumentType()).initialize().threads());
    }

    @Test
    public void requireThatProtonResourceLimitsCanBeSet() throws Exception {
        assertProtonResourceLimits(0.88, 0.77,
                new ContentClusterBuilder().protonDiskLimit(0.88).protonMemoryLimit(0.77).getXml());
    }

    @Test
    public void requireThatOnlyDiskLimitCanBeSet() throws Exception {
        assertProtonResourceLimits(0.88, 0.8,
                new ContentClusterBuilder().protonDiskLimit(0.88).getXml());
    }

    @Test
    public void requireThatOnlyMemoryLimitCanBeSet() throws Exception {
        assertProtonResourceLimits(0.8, 0.77,
                new ContentClusterBuilder().protonMemoryLimit(0.77).getXml());
    }

    @Test
    public void requireThatGloballyDistributedDocumentTypeIsTaggedAsSuch() throws Exception {
        ProtonConfig cfg = getProtonConfig(createClusterWithGlobalType());
        assertEquals(2, cfg.documentdb().size());
        assertDocumentDb("global", true, cfg.documentdb(0));
        assertDocumentDb("regular", false, cfg.documentdb(1));
    }

    private static void assertDocumentDb(String expName, boolean expGlobal, ProtonConfig.Documentdb db) {
        assertEquals(expName, db.inputdoctypename());
        assertEquals(expGlobal, db.global());
    }

    @Test
    public void require_that_document_types_with_references_are_topologically_sorted() throws Exception {
        ProtonConfig cfg = getProtonConfig(createClusterWithThreeDocumentTypes());
        assertEquals(3, cfg.documentdb().size());
        assertDocumentDb("c", true, cfg.documentdb(0));
        assertDocumentDb("b", true, cfg.documentdb(1));
        assertDocumentDb("a", false, cfg.documentdb(2));
    }

    private static ContentCluster createClusterWithThreeDocumentTypes() throws Exception {
        List<String> searchDefinitions = new ArrayList<>();
        searchDefinitions.add(new SearchDefinitionBuilder().name("a")
                .content(joinLines("field ref_to_b type reference<b> { indexing: attribute }",
                                   "field ref_to_c type reference<c> { indexing: attribute }")).build());
        searchDefinitions.add(new SearchDefinitionBuilder().name("b")
                .content("field ref_to_c type reference<c> { indexing: attribute }").build());
        searchDefinitions.add(new SearchDefinitionBuilder().name("c").build());
        return createCluster(new ContentClusterBuilder().docTypes(Arrays.asList(
                new ContentClusterBuilder.DocType("a"),
                new ContentClusterBuilder.DocType("b", true),
                new ContentClusterBuilder.DocType("c", true))).getXml(),
                searchDefinitions);
    }

}
