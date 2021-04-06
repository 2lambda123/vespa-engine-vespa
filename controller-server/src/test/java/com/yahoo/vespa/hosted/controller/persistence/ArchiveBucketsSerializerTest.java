package com.yahoo.vespa.hosted.controller.persistence;

import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.hosted.controller.api.integration.archive.ArchiveBucket;
import org.junit.Test;

import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;

public class ArchiveBucketsSerializerTest {

    @Test
    public void serdes() {
        var testTenants = new LinkedHashSet<TenantName>();
        testTenants.add(TenantName.from("tenant1"));
        testTenants.add(TenantName.from("tenant2"));

        var testBuckets = new LinkedHashSet<ArchiveBucket>();
        testBuckets.add(new ArchiveBucket("bucket1Arn", "key1Arn").withTenants(testTenants));
        testBuckets.add(new ArchiveBucket("bucket2Arn", "key2Arn"));

        String zkData = "{\"buckets\":[{\"bucketArn\":\"bucket1Arn\",\"keyArn\":\"key1Arn\",\"tenantIds\":[\"tenant1\",\"tenant2\"]},{\"bucketArn\":\"bucket2Arn\",\"keyArn\":\"key2Arn\",\"tenantIds\":[]}]}";

        assertEquals(testBuckets, ArchiveBucketsSerializer.fromJsonString(zkData));
        assertEquals(testBuckets, ArchiveBucketsSerializer.fromJsonString(ArchiveBucketsSerializer.toSlime(testBuckets).toString()));
    }
}
