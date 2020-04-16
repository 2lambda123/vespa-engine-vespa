// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "nearest_neighbor_iterator.h"

using search::tensor::DenseTensorAttribute;
using vespalib::ConstArrayRef;
using vespalib::tensor::DenseTensorView;
using vespalib::tensor::MutableDenseTensorView;
using vespalib::tensor::TypedCells;

using CellType = vespalib::eval::ValueType::CellType;

namespace search::queryeval {

namespace {

bool
is_compatible(const vespalib::eval::ValueType& lhs,
              const vespalib::eval::ValueType& rhs)
{
    return (lhs == rhs);
}

}

/**
 * Search iterator for K nearest neighbor matching.
 * Uses unpack() as feedback mechanism to track which matches actually became hits.
 * Keeps a heap of the K best hit distances.
 * Currently always does brute-force scanning, which is very expensive.
 **/
template <bool strict>
class NearestNeighborImpl : public NearestNeighborIterator
{
public:

    NearestNeighborImpl(Params params_in)
        : NearestNeighborIterator(params_in),
          _lhs(params().queryTensor.cellsRef()),
          _fieldTensor(params().tensorAttribute.getTensorType()),
          _lastScore(0.0)
    {
        assert(is_compatible(_fieldTensor.fast_type(), params().queryTensor.fast_type()));
    }

    ~NearestNeighborImpl();

    void doSeek(uint32_t docId) override {
        double distanceLimit = params().distanceHeap.distanceLimit();
        while (__builtin_expect((docId < getEndId()), true)) {
            double d = computeDistance(docId, distanceLimit);
            if (d <= distanceLimit) {
                _lastScore = d;
                setDocId(docId);
                return;
            }
            if (strict) {
                ++docId;
            } else {
                return;
            }
        }
        setAtEnd();
    }

    void doUnpack(uint32_t docId) override {
        double score = params().distanceFunction->to_rawscore(_lastScore);
        params().tfmd.setRawScore(docId, score);
        params().distanceHeap.used(_lastScore);
    }

    Trinary is_strict() const override { return strict ? Trinary::True : Trinary::False ; }

private:
    double computeDistance(uint32_t docId, double limit) {
        params().tensorAttribute.getTensor(docId, _fieldTensor);
        auto rhs = _fieldTensor.cellsRef();
        return params().distanceFunction->calc_with_limit(_lhs, rhs, limit);
    }

    TypedCells             _lhs;
    MutableDenseTensorView _fieldTensor;
    double                 _lastScore;
};

template <bool strict>
NearestNeighborImpl<strict>::~NearestNeighborImpl() = default;

namespace {

std::unique_ptr<NearestNeighborIterator>
resolve_strict_LCT_RCT(bool strict, const NearestNeighborIterator::Params &params)
{
    CellType lct = params.queryTensor.fast_type().cell_type();
    CellType rct = params.tensorAttribute.getTensorType().cell_type();
    if (lct != rct) abort();
    if (strict) {
        using NNI = NearestNeighborImpl<true>;
        return std::make_unique<NNI>(params);
    } else {
        using NNI = NearestNeighborImpl<false>;
        return std::make_unique<NNI>(params);
    }
}

} // namespace <unnamed>

std::unique_ptr<NearestNeighborIterator>
NearestNeighborIterator::create(
        bool strict,
        fef::TermFieldMatchData &tfmd,
        const vespalib::tensor::DenseTensorView &queryTensor,
        const search::tensor::DenseTensorAttribute &tensorAttribute,
        NearestNeighborDistanceHeap &distanceHeap,
        const search::tensor::DistanceFunction *dist_fun)

{
    Params params(tfmd, queryTensor, tensorAttribute, distanceHeap, dist_fun);
    return resolve_strict_LCT_RCT(strict, params);
}

} // namespace
