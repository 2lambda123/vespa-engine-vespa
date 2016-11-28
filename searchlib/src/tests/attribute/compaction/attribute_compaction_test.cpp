// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/searchlib/attribute/attribute.h>
#include <vespa/searchlib/attribute/attributefactory.h>
#include <vespa/searchlib/attribute/attributevector.hpp>
#include <vespa/searchlib/attribute/integerbase.h>

#include <vespa/log/log.h>
LOG_SETUP("attribute_compaction_test");

using search::IntegerAttribute;
using search::AttributeVector;
using search::attribute::Config;
using search::attribute::BasicType;
using search::attribute::CollectionType;

using AttributePtr = AttributeVector::SP;
using AttributeStatus = search::attribute::Status;

namespace
{

template <typename VectorType>
bool is(AttributePtr &v)
{
    return dynamic_cast<VectorType *>(v.get());
}

template <typename VectorType>
VectorType &as(AttributePtr &v)
{
    return dynamic_cast<VectorType &>(*v);
}

void populateAttribute(IntegerAttribute &v, uint32_t docIdLimit, uint32_t values)
{
    for(uint32_t docId = 0; docId < docIdLimit; ++docId) {
        uint32_t checkDocId = 0;
        EXPECT_TRUE(v.addDoc(checkDocId));
        EXPECT_EQUAL(docId, checkDocId);
        v.clearDoc(docId);
        for (uint32_t vi = 0; vi <= values; ++vi) {
            EXPECT_TRUE(v.append(docId, 42, 1) );
        }
        if ((docId % 100) == 0) {
            v.commit();
        }
    }
    v.commit(true);
    v.incGeneration();
}

void populateAttribute(AttributePtr &v, uint32_t docIdLimit, uint32_t values)
{
    if (is<IntegerAttribute>(v)) {
        populateAttribute(as<IntegerAttribute>(v), docIdLimit, values);
    }
}

void hammerAttribute(IntegerAttribute &v, uint32_t docIdLow, uint32_t docIdHigh, uint32_t count)
{
    uint32_t work = 0;
    for (uint32_t i = 0; i < count; ++i) {
        for (uint32_t docId = docIdLow; docId < docIdHigh; ++docId) {
            v.clearDoc(docId);
            EXPECT_TRUE(v.append(docId, 42, 1));
        }
        work += (docIdHigh - docIdLow);
        if (work >= 100000) {
            v.commit(true);
            work = 0;
        } else {
            v.commit();
        }
    }
    v.commit(true);
    v.incGeneration();
}

void hammerAttribute(AttributePtr &v, uint32_t docIdLow, uint32_t docIdHigh, uint32_t count)
{
    if (is<IntegerAttribute>(v)) {
        hammerAttribute(as<IntegerAttribute>(v), docIdLow, docIdHigh, count);
    }
}

void cleanAttribute(AttributeVector &v, uint32_t docIdLimit)
{
    for (uint32_t docId = 0; docId < docIdLimit; ++docId) {
        v.clearDoc(docId);
    }
    v.commit(true);
    v.incGeneration();
}

}

class Fixture {
public:
    AttributePtr _v;

    Fixture(Config cfg)
        : _v()
    { _v = search::AttributeFactory::createAttribute("test", cfg); }
    ~Fixture() { }
    void populate(uint32_t docIdLimit, uint32_t values) { populateAttribute(_v, docIdLimit, values); }
    void hammer(uint32_t docIdLow, uint32_t docIdHigh, uint32_t count) { hammerAttribute(_v, docIdLow, docIdHigh, count); }
    void clean(uint32_t docIdLimit) { cleanAttribute(*_v, docIdLimit); }
    AttributeStatus getStatus() { _v->commit(true); return _v->getStatus(); }
    AttributeStatus getStatus(const vespalib::string &prefix) {
        AttributeStatus status(getStatus());
        LOG(info, "status %s: used=%zu, dead=%zu, onHold=%zu",
            prefix.c_str(), status.getUsed(), status.getDead(), status.getOnHold());
        return status;
    }
};

TEST_F("Test that compaction of integer array attribute reduces memory usage", Fixture({ BasicType::INT64, CollectionType::ARRAY }))
{
    f.populate(3000, 40);
    AttributeStatus beforeStatus = f.getStatus("before");
    f.clean(2000);
    AttributeStatus afterStatus = f.getStatus("after");
    EXPECT_LESS(afterStatus.getUsed(), beforeStatus.getUsed());
}

TEST_F("Test that compaction uses right metrics to start compaction", Fixture({ BasicType::INT8, CollectionType::ARRAY }))
{
    uint32_t largeDocs = 1024 * 1024 * 16;
    f.populate(largeDocs, 1000);
    AttributeStatus beforeStatus = f.getStatus("before");
    (void) beforeStatus;
    uint32_t docIdLow = largeDocs - 1000;
    for (uint32_t i = 0; i < 40; ++i) {
        LOG(info, "hammer gen %u", i);
        f.hammer(docIdLow, largeDocs, 100000);
        AttributeStatus afterStatus = f.getStatus("after");
        (void) afterStatus;
    }
}

TEST_MAIN() { TEST_RUN_ALL(); }
