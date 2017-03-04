// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "simple_tensor.h"
#include "simple_tensor_engine.h"
#include "operation.h"
#include <algorithm>

namespace vespalib {
namespace eval {

using Address = SimpleTensor::Address;
using Cell = SimpleTensor::Cell;
using Cells = SimpleTensor::Cells;
using IndexList = std::vector<size_t>;
using Label = SimpleTensor::Label;
using CellRef = std::reference_wrapper<const Cell>;

namespace {

void assert_type(const ValueType &type) {
    (void) type;
    assert(!type.is_abstract());
    assert(type.is_double() || type.is_tensor());
}

void assert_address(const Address &address, const ValueType &type) {
    assert(address.size() == type.dimensions().size());
    for (size_t i = 0; i < address.size(); ++i) {
        if (type.dimensions()[i].is_mapped()) {
            assert(address[i].is_mapped());
        } else {
            assert(address[i].is_indexed());
            assert(address[i].index < type.dimensions()[i].size);
        }
    }
}

Address select(const Address &address, const IndexList &selector) {
    Address result;
    for (size_t index: selector) {
        result.push_back(address[index]);
    }
    return result;
}

Address select(const Address &a, const Address &b, const IndexList &selector) {
    Address result;
    for (size_t index: selector) {
        if (index < a.size()) {
            result.push_back(a[index]);
        } else {
            result.push_back(b[index - a.size()]);
        }
    }
    return result;
}

size_t get_dimension_size(const ValueType &type, size_t dim_idx) {
    if (dim_idx == ValueType::Dimension::npos) {
        return 1;
    }
    return type.dimensions()[dim_idx].size;
}

size_t get_dimension_index(const Address &addr, size_t dim_idx) {
    if (dim_idx == ValueType::Dimension::npos) {
        return 0;
    }
    return addr[dim_idx].index;
}

const vespalib::string &reverse_rename(const vespalib::string &name,
                                       const std::vector<vespalib::string> &from,
                                       const std::vector<vespalib::string> &to)
{
    assert(from.size() == to.size());
    for (size_t idx = 0; idx < to.size(); ++idx) {
        if (to[idx] == name) {
            return from[idx];
        }
    }
    return name;
}

/**
 * Helper class used when building SimpleTensors. While a tensor
 * in its final form simply contains a collection of cells, the
 * builder keeps track of cell values as a block map instead. Each
 * block is a dense multi-dimensional array that is addressed by
 * the combination of all mapped Labels in a cell address. The
 * indexed labels from the same cell address is used to address
 * the appropriate cell value within the block. The reason for
 * this is to make it easier to make sure that the indexed
 * dimensions have entries for all valid Lables (densify with 0.0
 * as default value).
 **/
class Builder {
private:
    class Block {
    private:
        const ValueType &_type;
        const IndexList &_indexed;
        std::vector<double> _values;
        size_t offset_of(const Address &address) const {
            size_t offset = 0;
            for (size_t index: _indexed) {
                size_t label = address[index].index;
                size_t size = _type.dimensions()[index].size;
                offset = (offset * size) + label;
            }
            return offset;
        }
        void subconvert(Address &address, size_t n, Cells &cells_out) const {
            if (n < _indexed.size()) {
                Label &label = address[_indexed[n]];
                size_t size = _type.dimensions()[_indexed[n]].size;
                for (label.index = 0; label.index < size; ++label.index) {
                    subconvert(address, n + 1, cells_out);
                }
            } else {
                cells_out.emplace_back(address, _values[offset_of(address)]);
            }
        }
    public:
        Block(const ValueType &type, const IndexList &indexed, size_t num_values)
            : _type(type), _indexed(indexed), _values(num_values, 0.0) {}
        void set(const Address &address, double value) { _values[offset_of(address)] = value; }
        void convert(const Address &block_key, const IndexList &mapped, Cells &cells_out) const {
            Address address(_type.dimensions().size(), Label(size_t(0)));
            for (size_t i = 0; i < mapped.size(); ++i) {
                address[mapped[i]] = block_key[i];
            }
            subconvert(address, 0, cells_out);
        }
    };
    using BlockMap = std::map<Address,Block>;
    ValueType _type;
    IndexList _mapped;
    IndexList _indexed;
    size_t _block_size;
    BlockMap _blocks;
public:
    explicit Builder(const ValueType &type);
    ~Builder();
    void set(const Address &address, double value) {
        assert_address(address, _type);
        Address block_key = select(address, _mapped);
        auto pos = _blocks.find(block_key);
        if (pos == _blocks.end()) {
            pos = _blocks.emplace(block_key, Block(_type, _indexed, _block_size)).first;
        }
        pos->second.set(address, value);
    }
    void set(const TensorSpec::Address &label_map, double value) {
        Address address;
        for (const auto &dimension: _type.dimensions()) {
            auto pos = label_map.find(dimension.name);
            assert(pos != label_map.end());
            address.emplace_back(pos->second);
        }
        set(address, value);
    }
    std::unique_ptr<SimpleTensor> build() {
        Cells cells;
        for (const auto &entry: _blocks) {
            entry.second.convert(entry.first, _mapped, cells);
        }
        return std::make_unique<SimpleTensor>(_type, std::move(cells));
    }
};

Builder::Builder(const ValueType &type)
        : _type(type),
          _mapped(),
          _indexed(),
          _block_size(1),
          _blocks()
{
    assert_type(_type);
    for (size_t i = 0; i < type.dimensions().size(); ++i) {
        const auto &dimension = _type.dimensions()[i];
        if (dimension.is_mapped()) {
            _mapped.push_back(i);
        } else {
            _block_size *= dimension.size;
            _indexed.push_back(i);
        }
    }
    if (_mapped.empty()) {
        _blocks.emplace(Address(), Block(_type, _indexed, _block_size));
    }
}
Builder::~Builder() { }

/**
 * Helper class used to calculate which dimensions are shared between
 * types and which are not. Also calculates how address elements from
 * cells with the different types should be combined into a single
 * address.
 **/
struct TypeAnalyzer {
    static constexpr size_t npos = -1;
    IndexList only_a;
    IndexList overlap_a;
    IndexList overlap_b;
    IndexList only_b;
    IndexList combine;
    size_t    ignored_a;
    size_t    ignored_b;
    TypeAnalyzer(const ValueType &lhs, const ValueType &rhs, const vespalib::string &ignore = "");
    ~TypeAnalyzer();
};

TypeAnalyzer::TypeAnalyzer(const ValueType &lhs, const ValueType &rhs, const vespalib::string &ignore)
    : only_a(), overlap_a(), overlap_b(), only_b(), combine(), ignored_a(npos), ignored_b(npos)
{
    const auto &a = lhs.dimensions();
    const auto &b = rhs.dimensions();
    size_t b_idx = 0;
    for (size_t a_idx = 0; a_idx < a.size(); ++a_idx) {
        while ((b_idx < b.size()) && (b[b_idx].name < a[a_idx].name)) {
            if (b[b_idx].name != ignore) {
                only_b.push_back(b_idx);
                combine.push_back(a.size() + b_idx);
            } else {
                ignored_b = b_idx;
            }
            ++b_idx;
        }
        if ((b_idx < b.size()) && (b[b_idx].name == a[a_idx].name)) {
            if (a[a_idx].name != ignore) {
                overlap_a.push_back(a_idx);
                overlap_b.push_back(b_idx);
                combine.push_back(a_idx);
            } else {
                ignored_a = a_idx;
                ignored_b = b_idx;
            }
            ++b_idx;
        } else {
            if (a[a_idx].name != ignore) {
                only_a.push_back(a_idx);
                combine.push_back(a_idx);
            } else {
                ignored_a = a_idx;
            }
        }
    }
    while (b_idx < b.size()) {
        if (b[b_idx].name != ignore) {
            only_b.push_back(b_idx);
            combine.push_back(a.size() + b_idx);
        } else {
            ignored_b = b_idx;
        }
        ++b_idx;
    }
}

TypeAnalyzer::~TypeAnalyzer() { }

/**
 * A view is a total ordering of cells from a SimpleTensor according
 * to a subset of the dimensions in the tensor type.
 **/
class View {
public:
    /**
     * A range of cells within a view with equal values for all labels
     * corresponding to the dimensions of the view.
     **/
    class EqualRange {
    private:
        const CellRef *_begin;
        const CellRef *_end;
    public:
        EqualRange(const CellRef *begin_in, const CellRef *end_in)
            : _begin(begin_in), _end(end_in) {}
        const CellRef *begin() const { return _begin; };
        const CellRef *end() const { return _end; }
        bool empty() const { return (_begin == _end); }
    };
private:
    /**
     * Address comparator only looking at a subset of the labels.
     **/
    struct Less {
        IndexList selector;
        explicit Less(const IndexList &selector_in) : selector(selector_in) {}
        bool operator()(const CellRef &a, const CellRef &b) const {
            for (size_t idx: selector) {
                if (a.get().address[idx] != b.get().address[idx]) {
                    return (a.get().address[idx] < b.get().address[idx]);
                }
            }
            return false;
        }
    };
    Less _less;
    std::vector<CellRef> _refs;

    EqualRange make_range(const CellRef *begin) const {
        const CellRef *end = (begin < refs_end()) ? (begin + 1) : begin;
        while ((end < refs_end()) && !_less(*(end - 1), *end)) {
            ++end;
        }
        return EqualRange(begin, end);
    }

public:
    View(const SimpleTensor &tensor, const IndexList &selector)
        : _less(selector), _refs()
    {
        for (const auto &cell: tensor.cells()) {
            _refs.emplace_back(cell);
        }
        std::sort(_refs.begin(), _refs.end(), _less);
    }
    View(const EqualRange &range, const IndexList &selector)
        : _less(selector), _refs()
    {
        for (const auto &cell: range) {
            _refs.emplace_back(cell);
        }
        std::sort(_refs.begin(), _refs.end(), _less);
    }
    ~View();
    const IndexList &selector() const { return _less.selector; }
    const CellRef *refs_begin() const { return &_refs[0]; }
    const CellRef *refs_end() const { return (refs_begin() + _refs.size()); }
    EqualRange first_range() const { return make_range(refs_begin()); }
    EqualRange next_range(const EqualRange &prev) const { return make_range(prev.end()); }
};

View::~View() { }

/**
 * Helper class used to find matching EqualRanges from two different
 * SimpleTensor Views.
 **/
class ViewMatcher {
public:
    /**
     * Comparator used to cross-compare addresses across two different
     * views only looking at the overlapping dimensions between the
     * views.
     **/
    struct CrossCompare {
        enum class Result { LESS, EQUAL, GREATER };
        IndexList a_selector;
        IndexList b_selector;
        CrossCompare(const IndexList &a_selector_in, const IndexList &b_selector_in)
            : a_selector(a_selector_in), b_selector(b_selector_in)
        {
            assert(a_selector.size() == b_selector.size());
        }
        ~CrossCompare();
        Result compare(const Cell &a, const Cell &b) const {
            for (size_t i = 0; i < a_selector.size(); ++i) {
                if (a.address[a_selector[i]] != b.address[b_selector[i]]) {
                    if (a.address[a_selector[i]] < b.address[b_selector[i]]) {
                        return Result::LESS;
                    } else {
                        return Result::GREATER;
                    }
                }
            }
            return Result::EQUAL;
        }
    };
    using EqualRange = View::EqualRange;

private:
    const View &_a;
    const View &_b;
    EqualRange _a_range;
    EqualRange _b_range;
    CrossCompare _cmp;

    bool has_a() const { return !_a_range.empty(); }
    bool has_b() const { return !_b_range.empty(); }
    void next_a() { _a_range = _a.next_range(_a_range); }
    void next_b() { _b_range = _b.next_range(_b_range); }

    void find_match() {
        while (valid()) {
            switch (_cmp.compare(*get_a().begin(), *get_b().begin())) {
            case CrossCompare::Result::LESS:
                next_a();
                break;
            case CrossCompare::Result::GREATER:
                next_b();
                break;
            case CrossCompare::Result::EQUAL:
                return;
            }
        }
    }

public:
    ViewMatcher(const View &a, const View &b)
        : _a(a), _b(b), _a_range(_a.first_range()), _b_range(b.first_range()),
          _cmp(a.selector(), b.selector())
    {
        find_match();
    }
    ~ViewMatcher();
    bool valid() const { return (has_a() && has_b()); }
    const EqualRange &get_a() const { return _a_range; }
    const EqualRange &get_b() const { return _b_range; }
    void next() {
        next_a();
        next_b();
        find_match();
    }
};

ViewMatcher::~ViewMatcher() { }

ViewMatcher::CrossCompare::~CrossCompare() { }

} // namespace vespalib::eval::<unnamed>

constexpr size_t TensorSpec::Label::npos;
constexpr size_t SimpleTensor::Label::npos;

SimpleTensor::SimpleTensor()
    : Tensor(SimpleTensorEngine::ref()),
      _type(ValueType::error_type()),
      _cells()
{
}

SimpleTensor::SimpleTensor(double value)
    : Tensor(SimpleTensorEngine::ref()),
      _type(ValueType::double_type()),
      _cells({Cell({},value)})
{
}

SimpleTensor::SimpleTensor(const ValueType &type_in, Cells &&cells_in)
    : Tensor(SimpleTensorEngine::ref()),
      _type(type_in),
      _cells(std::move(cells_in))
{
    assert_type(_type);
    for (const auto &cell: _cells) {
        assert_address(cell.address, _type);
    }
    std::sort(_cells.begin(), _cells.end(),
              [](const auto &a, const auto &b){ return (a.address < b.address); });
}

std::unique_ptr<SimpleTensor>
SimpleTensor::map(const std::function<double(double)> &function) const
{
    Cells cells(_cells);
    for (auto &cell: cells) {
        cell.value = function(cell.value);
    }
    return std::make_unique<SimpleTensor>(_type, std::move(cells));
}

std::unique_ptr<SimpleTensor>
SimpleTensor::reduce(Aggregator &aggr, const std::vector<vespalib::string> &dimensions) const
{
    ValueType result_type = _type.reduce(dimensions);
    if (result_type.is_error()) {
        return std::make_unique<SimpleTensor>();
    }
    Builder builder(result_type);
    IndexList selector = TypeAnalyzer(_type, result_type).overlap_a;
    View view(*this, selector);
    for (View::EqualRange range = view.first_range(); !range.empty(); range = view.next_range(range)) {
        auto pos = range.begin();
        aggr.first((pos++)->get().value);
        for (; pos != range.end(); ++pos) {
            aggr.next(pos->get().value);
        }
        builder.set(select(range.begin()->get().address, selector), aggr.result());
    }
    return builder.build();
}

std::unique_ptr<SimpleTensor>
SimpleTensor::rename(const std::vector<vespalib::string> &from, const std::vector<vespalib::string> &to) const
{
    ValueType result_type = _type.rename(from, to);
    if (result_type.is_error()) {
        return std::make_unique<SimpleTensor>();
    }
    Builder builder(result_type);   
    IndexList selector;
    for (const auto &dim: result_type.dimensions()) {
        selector.push_back(_type.dimension_index(reverse_rename(dim.name, from, to)));
    }
    for (auto &cell: _cells) {
        builder.set(select(cell.address, selector), cell.value);
    }
    return builder.build();
}

std::unique_ptr<SimpleTensor>
SimpleTensor::create(const TensorSpec &spec)
{
    Builder builder(ValueType::from_spec(spec.type()));
    for (const auto &cell: spec.cells()) {
        builder.set(cell.first, cell.second);
    }
    return builder.build();
}

bool
SimpleTensor::equal(const SimpleTensor &a, const SimpleTensor &b)
{
    if (a.type() != b.type()) {
        return false;
    }
    TypeAnalyzer type_info(a.type(), b.type());
    View view_a(a, type_info.overlap_a);
    View view_b(b, type_info.overlap_b);
    const CellRef *pos_a = view_a.refs_begin();
    const CellRef *end_a = view_a.refs_end();
    const CellRef *pos_b = view_b.refs_begin();
    const CellRef *end_b = view_b.refs_end();
    ViewMatcher::CrossCompare cmp(view_a.selector(), view_b.selector());
    while ((pos_a != end_a) && (pos_b != end_b)) {
        if (cmp.compare(pos_a->get(), pos_b->get()) != ViewMatcher::CrossCompare::Result::EQUAL) {
            return false;
        }
        if (pos_a->get().value != pos_b->get().value) {
            return false;
        }
        ++pos_a;
        ++pos_b;
    }
    return ((pos_a == end_a) && (pos_b == end_b));
}

std::unique_ptr<SimpleTensor>
SimpleTensor::join(const SimpleTensor &a, const SimpleTensor &b, const std::function<double(double,double)> &function)
{
    ValueType result_type = ValueType::join(a.type(), b.type());
    if (result_type.is_error()) {
        return std::make_unique<SimpleTensor>();
    }
    Builder builder(result_type);
    TypeAnalyzer type_info(a.type(), b.type());
    View view_a(a, type_info.overlap_a);
    View view_b(b, type_info.overlap_b);
    for (ViewMatcher matcher(view_a, view_b); matcher.valid(); matcher.next()) {
        for (const auto &ref_a: matcher.get_a()) {
            for (const auto &ref_b: matcher.get_b()) {
                builder.set(select(ref_a.get().address, ref_b.get().address, type_info.combine),
                            function(ref_a.get().value, ref_b.get().value));
            }
        }
    }
    return builder.build();
}

std::unique_ptr<SimpleTensor>
SimpleTensor::concat(const SimpleTensor &a, const SimpleTensor &b, const vespalib::string &dimension)
{
    ValueType result_type = ValueType::concat(a.type(), b.type(), dimension);
    if (result_type.is_error()) {
        return std::make_unique<SimpleTensor>();
    }
    Builder builder(result_type);
    TypeAnalyzer type_info(a.type(), b.type(), dimension);
    View view_a(a, type_info.overlap_a);
    View view_b(b, type_info.overlap_b);
    size_t cat_dim_idx = result_type.dimension_index(dimension);
    size_t cat_offset = get_dimension_size(a.type(), type_info.ignored_a);
    for (ViewMatcher matcher(view_a, view_b); matcher.valid(); matcher.next()) {
        View subview_a(matcher.get_a(), type_info.only_a);
        View subview_b(matcher.get_b(), type_info.only_b);
        for (auto range_a = subview_a.first_range(); !range_a.empty(); range_a = subview_a.next_range(range_a)) {
            for (auto range_b = subview_b.first_range(); !range_b.empty(); range_b = subview_b.next_range(range_b)) {
                Address addr = select(range_a.begin()->get().address, range_b.begin()->get().address, type_info.combine);
                addr.insert(addr.begin() + cat_dim_idx, Label(size_t(0)));
                for (const auto &ref: range_a) {
                    addr[cat_dim_idx].index = get_dimension_index(ref.get().address, type_info.ignored_a);
                    builder.set(addr, ref.get().value);
                }
                for (const auto &ref: range_b) {
                    addr[cat_dim_idx].index = cat_offset + get_dimension_index(ref.get().address, type_info.ignored_b);
                    builder.set(addr, ref.get().value);
                }
            }
        }
    }
    return builder.build();
}

} // namespace vespalib::eval
} // namespace vespalib
