// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/vespalib/tensor/compact/compact_tensor_v2.h>
#include <vespa/vespalib/tensor/compact/compact_tensor_v2_builder.h>
#include <vespa/vespalib/tensor/types.h>
#include <vespa/vespalib/tensor/default_tensor.h>
#include <vespa/vespalib/tensor/tensor_factory.h>
#include <vespa/vespalib/tensor/serialization/typed_binary_format.h>
#include <vespa/vespalib/tensor/serialization/slime_binary_format.h>
#include <vespa/vespalib/data/slime/slime.h>
#include <iostream>

using namespace vespalib::tensor;

template <typename BuilderType>
struct Fixture
{
    BuilderType _builder;
    Fixture() : _builder() {}

    Tensor::UP createTensor(const TensorCells &cells) {
        return vespalib::tensor::TensorFactory::create(cells, _builder);
    }
    Tensor::UP createTensor(const TensorCells &cells, const TensorDimensions &dimensions) {
        return TensorFactory::create(cells, dimensions, _builder);
    }

    static inline uint32_t getTensorTypeId();

    void assertSerialized(const vespalib::string &exp, const TensorCells &rhs,
                          const TensorDimensions &rhsDimensions) {
        Tensor::UP rhsTensor(createTensor(rhs, rhsDimensions));
        auto slime = SlimeBinaryFormat::serialize(*rhsTensor);
        vespalib::slime::Memory memory_exp(exp);
        vespalib::Slime expSlime;
        size_t used = vespalib::slime::JsonFormat::decode(memory_exp, expSlime);
        EXPECT_EQUAL(used, memory_exp.size);
        EXPECT_EQUAL(expSlime, *slime);
    }
};

template <>
uint32_t
Fixture<CompactTensorV2Builder>::getTensorTypeId() { return 2u; }


using CompactV2Fixture = Fixture<CompactTensorV2Builder>;


namespace {
vespalib::string twoCellsJson[3] =
{
    "{ dimensions: [ 'x', 'y' ],"
    " cells: ["
    "{ address: { y:'3'}, value: 4.0 },"
    "{ address: { x:'1'}, value: 3.0 }"
    "] }",
    "{ dimensions: [ 'x', 'y' ],"
    " cells: ["
    "{ address: { x:'1'}, value: 3.0 },"
    "{ address: { y:'3'}, value: 4.0 }"
    "] }",
    "{ dimensions: [ 'x', 'y' ],"
    " cells: ["
    "{ address: { x:'1'}, value: 3.0 },"
    "{ address: { y:'3'}, value: 4.0 }"
    "] }",
};
}


template <typename FixtureType>
void
testTensorSlimeSerialization(FixtureType &f)
{
    TEST_DO(f.assertSerialized("{ dimensions: [], cells: [] }", {}, {}));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x' ], cells: [] }",
                               {}, { "x" }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x', 'y' ], cells: [] }",
                               {}, { "x", "y" }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x' ],"
                               "cells: ["
                               "{ address: { x: '1' }, value: 3.0 }"
                               "] }",
                               { {{{"x","1"}}, 3} }, { "x" }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x', 'y' ],"
                               " cells: ["
                               "{ address: { }, value: 3.0 }"
                               "] }",
                               { {{}, 3} }, { "x", "y"}));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x', 'y' ],"
                               " cells: ["
                               "{ address: { x: '1' }, value: 3.0 }"
                               "] }",
                               { {{{"x","1"}}, 3} }, { "x", "y" }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x', 'y' ],"
                               " cells: ["
                               "{ address: { y: '3' }, value: 3.0 }"
                               "] }",
                               { {{{"y","3"}}, 3} }, { "x", "y" }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x', 'y' ],"
                               " cells: ["
                               "{ address: { x:'2', y:'4'}, value: 3.0 }"
                               "] }",
                               { {{{"x","2"}, {"y", "4"}}, 3} }, { "x", "y" }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x', 'y' ],"
                               " cells: ["
                               "{ address: { x:'1'}, value: 3.0 }"
                               "] }",
                               { {{{"x","1"}}, 3} }, {"x", "y"}));
    TEST_DO(f.assertSerialized(twoCellsJson[FixtureType::getTensorTypeId()],
                               { {{{"x","1"}}, 3}, {{{"y","3"}}, 4} },
                               {"x", "y"}));
}

TEST_F("test tensor slime serialization for CompactTensorV2", CompactV2Fixture)
{
    testTensorSlimeSerialization(f);
}


struct DenseFixture
{
    DenseFixture() {}

    Tensor::UP createTensor(const DenseTensorCells &cells) {
        return vespalib::tensor::TensorFactory::createDense(cells);
    }

    void assertSerialized(const vespalib::string &exp,
                          const DenseTensorCells &rhs) {
        Tensor::UP rhsTensor(createTensor(rhs));
        auto slime = SlimeBinaryFormat::serialize(*rhsTensor);
        vespalib::slime::Memory memory_exp(exp);
        vespalib::Slime expSlime;
        size_t used = vespalib::slime::JsonFormat::decode(memory_exp, expSlime);
        EXPECT_EQUAL(used, memory_exp.size);
        EXPECT_EQUAL(expSlime, *slime);
    }
};


TEST_F("test tensor slime serialization for DenseTensor", DenseFixture)
{
    TEST_DO(f.assertSerialized("{ dimensions: [], cells: ["
                               "{ address: { }, value: 0.0 }"
                               "] }", {}));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x' ], cells: ["
                               "{ address: { x: '0' }, value: 0.0 }"
                               "] }",
                               { {{{"x",0}}, 0} }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x', 'y' ], cells: ["
                               "{ address: { x: '0', y: '0' }, value: 0.0 }"
                               "] }",
                               { {{{"x",0},{"y",0}}, 0} }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x' ],"
                               "cells: ["
                               "{ address: { x: '0' }, value: 0.0 },"
                               "{ address: { x: '1' }, value: 3.0 }"
                               "] }",
                               { {{{"x",1}}, 3} }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x', 'y' ],"
                               " cells: ["
                               "{ address: { x: '0', y: '0' }, value: 3.0 }"
                               "] }",
                               { {{{"x",0},{"y",0}}, 3} }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x', 'y' ],"
                               " cells: ["
                               "{ address: { x: '0', y: '0' }, value: 0.0 },"
                               "{ address: { x: '1', y: '0' }, value: 3.0 }"
                               "] }",
                               { {{{"x",1},{"y", 0}}, 3} }));
    TEST_DO(f.assertSerialized("{ dimensions: [ 'x', 'y' ],"
                               " cells: ["
                               "{ address: { x: '0', y: '0' }, value: 0.0 },"
                               "{ address: { x: '0', y: '1' }, value: 0.0 },"
                               "{ address: { x: '0', y: '2' }, value: 0.0 },"
                               "{ address: { x: '0', y: '3' }, value: 3.0 }"
                               "] }",
                               { {{{"x",0},{"y",3}}, 3} }));
}


TEST_MAIN() { TEST_RUN_ALL(); }
