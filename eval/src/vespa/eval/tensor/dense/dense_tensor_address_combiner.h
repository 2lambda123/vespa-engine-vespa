// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "dense_tensor_cells_iterator.h"
#include <vespa/eval/tensor/tensor.h>
#include <vespa/eval/tensor/types.h>
#include <vespa/eval/eval/value_type.h>

namespace vespalib::tensor {


/**
 * Combines two dense tensor addresses to a new tensor address.
 * The resulting dimensions is the union of the input dimensions and
 * common dimensions must have matching labels.
 */
class DenseTensorAddressCombiner
{
public:
    using Address = DenseTensorCellsIterator::Address;
    using Mapping = std::vector<std::pair<uint32_t, uint32_t>>;

private:
    enum class AddressOp { LHS, RHS, BOTH };

    using CellsIterator = DenseTensorCellsIterator;

    class AddressReader
    {
    private:
        const Address &_address;
        uint32_t       _idx;
    public:
        AddressReader(const Address &address) : _address(address), _idx(0) {}
        Address::value_type nextLabel() { return _address[_idx++]; }
    };

    std::vector<AddressOp> _ops;
    Address                _combinedAddress;
    Mapping                _left;
    Mapping                _commonRight;
    Mapping                _right;
    void update(const Address & addr, const Mapping & mapping) {
        for (const auto & m : mapping) {
            _combinedAddress[m.first] = addr[m.second];
        }
    }
public:
    DenseTensorAddressCombiner(const eval::ValueType &lhs, const eval::ValueType &rhs);
    ~DenseTensorAddressCombiner();
    void updateLeftAndCommon(const Address & addr) { update(addr, _left); }
    void updateRight(const Address & addr) { update(addr, _right); }
    bool hasCommonWithRight(const Address & addr) const {
        for (const auto & m : _commonRight) {
            if (_combinedAddress[m.first] != addr[m.second]) return false;
        }
        return true;
    }

    const Mapping & getCommonRight() const { return _commonRight; }
    const Mapping & getRight() const { return _right; }

    bool hasAnyRightOnlyDimensions() const { return ! _right.empty(); }

    const Address &address() const { return _combinedAddress; }
    Address &address() { return _combinedAddress; }

    bool combine(const Address & lhs, const Address & rhs) {
        uint32_t index(0);
        AddressReader lhsReader(lhs);
        AddressReader rhsReader(rhs);
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

    static eval::ValueType combineDimensions(const eval::ValueType &lhs, const eval::ValueType &rhs);
};


/**
 * Utility class to iterate over common cells in a dense tensor.
 */
class CommonDenseTensorCellsIterator
{
public:
    using size_type = eval::ValueType::Dimension::size_type;
    using Address = std::vector<size_type>;
    using Mapping = DenseTensorAddressCombiner::Mapping;
private:
    using Dims = std::vector<uint32_t>;
    using CellsRef = vespalib::ConstArrayRef<double>;
    const eval::ValueType &_type;
    CellsRef               _cells;
    Address                _address;
    const Mapping         &_common;
    const Mapping         &_mutable;
    std::vector<size_t>    _accumulatedSize;

    double cell(size_t cellIdx) const { return _cells[cellIdx]; }
    size_t index(const Address &address) const {
        size_t cellIdx(0);
        for (uint32_t i(0); i < address.size(); i++) {
            cellIdx += address[i]*_accumulatedSize[i];
        }
        return cellIdx;
    }
public:
    CommonDenseTensorCellsIterator(const Mapping & common, const Mapping & right,
                                   const eval::ValueType &type_in, CellsRef cells);
    ~CommonDenseTensorCellsIterator();
    template <typename Func>
    void for_each(Address & combined, Func && func) {
        const int32_t lastDimension = _mutable.size() - 1;
        int32_t curDimension = lastDimension;
        size_t cellIdx = index(_address);
        while (curDimension >= 0) {
            const uint32_t rdim = _mutable[curDimension].second;
            const uint32_t cdim = _mutable[curDimension].first;
            size_type & rindex = _address[rdim];
            size_type & cindex = combined[cdim];
            if (curDimension == lastDimension) {
                for (rindex = 0, cindex = 0; rindex < _type.dimensions()[rdim].size; rindex++, cindex++) {
                    func(combined, cell(cellIdx));
                    cellIdx += _accumulatedSize[rdim];
                }
                rindex = 0; cindex = 0;
                cellIdx -= _accumulatedSize[rdim] * _type.dimensions()[rdim].size;
                curDimension--;
            } else {
                if (rindex < _type.dimensions()[rdim].size) {
                    rindex++; cindex++;
                    cellIdx += _accumulatedSize[rdim];
                    curDimension++;
                } else {
                    cellIdx -= _accumulatedSize[rdim] * _type.dimensions()[rdim].size;
                    rindex = 0; cindex = 0;
                    curDimension--;
                }
            }
        }
    }
    bool updateCommon(const Address & combined) {
        for (const auto & m : _common) {
            if (combined[m.first] >= _type.dimensions()[m.second].size) return false;
            _address[m.second] = combined[m.first];
        }
        return true;
    }
    double cell() const {
        return cell(index(_address));
    }

    const eval::ValueType &fast_type() const { return _type; }
};

}
