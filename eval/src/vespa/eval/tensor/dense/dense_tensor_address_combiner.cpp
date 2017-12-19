// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "dense_tensor_address_combiner.h"
#include <vespa/vespalib/util/exceptions.h>
#include <cassert>

namespace vespalib::tensor {

using Address = DenseTensorAddressCombiner::Address;

namespace {

class AddressReader
{
private:
    const Address &_address;
    size_t _idx;

public:
    AddressReader(const Address &address)
        : _address(address),
          _idx(0)
    {}
    Address::value_type nextLabel() {
        return _address[_idx++];
    }
    bool valid() {
        return _idx < _address.size();
    }
};

}

DenseTensorAddressCombiner::~DenseTensorAddressCombiner() { }

DenseTensorAddressCombiner::DenseTensorAddressCombiner(const eval::ValueType &lhs,
                                                       const eval::ValueType &rhs)
    : _ops(),
      _combinedAddress()
{
    auto rhsItr = rhs.dimensions().cbegin();
    auto rhsItrEnd = rhs.dimensions().cend();
    for (const auto &lhsDim : lhs.dimensions()) {
        while ((rhsItr != rhsItrEnd) && (rhsItr->name < lhsDim.name)) {
            _ops.push_back(AddressOp::RHS);
            ++rhsItr;
        }
        if ((rhsItr != rhsItrEnd) && (rhsItr->name == lhsDim.name)) {
            _ops.push_back(AddressOp::BOTH);
            ++rhsItr;
        } else {
            _ops.push_back(AddressOp::LHS);
        }
    }
    while (rhsItr != rhsItrEnd) {
        _ops.push_back(AddressOp::RHS);
        ++rhsItr;
    }
    _combinedAddress.resize(_ops.size());
}

bool
DenseTensorAddressCombiner::combine(const CellsIterator &lhsItr,
                                    const CellsIterator &rhsItr)
{
    uint32_t index(0);
    AddressReader lhsReader(lhsItr.address());
    AddressReader rhsReader(rhsItr.address());
    for (const auto &op : _ops) {
        switch (op) {
        case AddressOp::LHS:
            _combinedAddress[index] = lhsReader.nextLabel();
            break;
        case AddressOp::RHS:
            _combinedAddress[index] = rhsReader.nextLabel();
            break;
        case AddressOp::BOTH:
            Address::value_type lhsLabel = lhsReader.nextLabel();
            Address::value_type rhsLabel = rhsReader.nextLabel();
            if (lhsLabel != rhsLabel) {
                return false;
            }
            _combinedAddress[index] = lhsLabel;
        }
        index++;
    }
    return true;
}

eval::ValueType
DenseTensorAddressCombiner::combineDimensions(const eval::ValueType &lhs,
                                              const eval::ValueType &rhs)
{
    // NOTE: both lhs and rhs are sorted according to dimension names.
    std::vector<eval::ValueType::Dimension> result;
    auto lhsItr = lhs.dimensions().cbegin();
    auto rhsItr = rhs.dimensions().cbegin();
    while (lhsItr != lhs.dimensions().end() &&
           rhsItr != rhs.dimensions().end()) {
        if (lhsItr->name == rhsItr->name) {
            result.emplace_back(lhsItr->name,
                                std::min(lhsItr->size, rhsItr->size));
            ++lhsItr;
            ++rhsItr;
        } else if (lhsItr->name < rhsItr->name) {
            result.emplace_back(*lhsItr++);
        } else {
            result.emplace_back(*rhsItr++);
        }
    }
    while (lhsItr != lhs.dimensions().end()) {
        result.emplace_back(*lhsItr++);
    }
    while (rhsItr != rhs.dimensions().end()) {
        result.emplace_back(*rhsItr++);
    }
    return (result.empty() ?
            eval::ValueType::double_type() :
            eval::ValueType::tensor_type(std::move(result)));
}

}
