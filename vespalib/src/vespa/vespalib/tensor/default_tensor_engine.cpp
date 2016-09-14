// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "default_tensor_engine.h"
#include <vespa/vespalib/eval/value.h>
#include <vespa/vespalib/eval/tensor_spec.h>
#include <vespa/vespalib/eval/operation_visitor.h>
#include "tensor.h"
#include "dense/dense_tensor_builder.h"
#include "default_tensor.h"

namespace vespalib {
namespace tensor {

using ErrorValue = eval::ErrorValue;
using DoubleValue = eval::DoubleValue;
using TensorValue = eval::TensorValue;

const DefaultTensorEngine DefaultTensorEngine::_engine;

eval::ValueType
DefaultTensorEngine::type_of(const Tensor &tensor) const
{
    assert(&tensor.engine() == this);
    const tensor::Tensor &my_tensor = static_cast<const tensor::Tensor &>(tensor);
    return my_tensor.getType().as_value_type();
}

bool
DefaultTensorEngine::equal(const Tensor &a, const Tensor &b) const
{
    assert(&a.engine() == this);
    assert(&b.engine() == this);
    const tensor::Tensor &my_a = static_cast<const tensor::Tensor &>(a);
    const tensor::Tensor &my_b = static_cast<const tensor::Tensor &>(b);
    if (my_a.getType().type() != my_b.getType().type()) {
        return false;
    }
    return my_a.equals(my_b);
}

vespalib::string
DefaultTensorEngine::to_string(const Tensor &tensor) const
{
    assert(&tensor.engine() == this);
    const tensor::Tensor &my_tensor = static_cast<const tensor::Tensor &>(tensor);
    return my_tensor.toString();
}

struct IsAddOperation : public eval::DefaultOperationVisitor {
    bool result = false;
    void visitDefault(const eval::Operation &) override {}
    void visit(const eval::operation::Add &) override { result = true; }
};

std::unique_ptr<eval::Tensor>
DefaultTensorEngine::create(const TensorSpec &spec) const
{
    ValueType type = ValueType::from_spec(spec.type());
    bool is_dense = false;
    bool is_sparse = false;
    for (const auto &dimension: type.dimensions()) {
        if (dimension.is_mapped()) {
            is_sparse = true;
        }
        if (dimension.is_indexed()) {
            is_dense = true;
        }
    }
    if (is_dense && is_sparse) {
        return DefaultTensor::builder().build();
    } else if (is_dense) {
        DenseTensorBuilder builder;
        std::map<vespalib::string,DenseTensorBuilder::Dimension> dimension_map;
        for (const auto &dimension: type.dimensions()) {
            dimension_map[dimension.name] = builder.defineDimension(dimension.name, dimension.size);
        }
        for (const auto &cell: spec.cells()) {
            const auto &address = cell.first;
            for (const auto &binding: address) {
                builder.addLabel(dimension_map[binding.first], binding.second.index);
            }
            builder.addCell(cell.second);
        }
        return builder.build();
    } else { // sparse
        DefaultTensor::builder builder;
        std::map<vespalib::string,DefaultTensor::builder::Dimension> dimension_map;
        for (const auto &dimension: type.dimensions()) {
            dimension_map[dimension.name] = builder.define_dimension(dimension.name);
        }
        for (const auto &cell: spec.cells()) {
            const auto &address = cell.first;
            for (const auto &binding: address) {
                builder.add_label(dimension_map[binding.first], binding.second.name);
            }
            builder.add_cell(cell.second);
        }
        return builder.build();
    }
}

const eval::Value &
DefaultTensorEngine::reduce(const Tensor &tensor, const BinaryOperation &op, const std::vector<vespalib::string> &dimensions, Stash &stash) const
{
    assert(&tensor.engine() == this);
    const tensor::Tensor &my_tensor = static_cast<const tensor::Tensor &>(tensor);
    IsAddOperation check;
    op.accept(check);
    if (check.result) {
        if (dimensions.empty()) { // sum
            return stash.create<eval::DoubleValue>(my_tensor.sum());
        } else { // dimension sum
            tensor::Tensor::UP result;
            for (const auto &dimension: dimensions) {
                if (result) {
                    result = result->sum(dimension);
                } else {
                    result = my_tensor.sum(dimension);
                }
            }
            return stash.create<TensorValue>(std::move(result));
        }
    }
    return stash.create<ErrorValue>();
}

struct CellFunctionAdapter : tensor::CellFunction {
    const eval::UnaryOperation &op;
    CellFunctionAdapter(const eval::UnaryOperation &op_in) : op(op_in) {}
    virtual double apply(double value) const override { return op.eval(value); }
};

const eval::Value &
DefaultTensorEngine::map(const UnaryOperation &op, const Tensor &a, Stash &stash) const
{
    assert(&a.engine() == this);
    const tensor::Tensor &my_a = static_cast<const tensor::Tensor &>(a);
    CellFunctionAdapter cell_function(op);
    return stash.create<TensorValue>(my_a.apply(cell_function));
}

struct TensorOperationOverride : eval::DefaultOperationVisitor {
    const tensor::Tensor &lhs;
    const tensor::Tensor &rhs;
    tensor::Tensor::UP result;
    TensorOperationOverride(const tensor::Tensor &lhs_in,
                            const tensor::Tensor &rhs_in)
        : lhs(lhs_in), rhs(rhs_in), result() {}
    virtual void visitDefault(const eval::Operation &) override {
        // empty result indicates error
    }
    virtual void visit(const eval::operation::Add &) override {
        result = lhs.add(rhs);
    }
    virtual void visit(const eval::operation::Sub &) override {
        result = lhs.subtract(rhs);
    }
    virtual void visit(const eval::operation::Mul &) override {
        if (lhs.getType() == rhs.getType()) {
            result = lhs.match(rhs);
        } else {
            result = lhs.multiply(rhs);
        }
    }
    virtual void visit(const eval::operation::Min &) override {
        result = lhs.min(rhs);
    }
    virtual void visit(const eval::operation::Max &) override {
        result = lhs.max(rhs);
    }
};

const eval::Value &
DefaultTensorEngine::apply(const BinaryOperation &op, const Tensor &a, const Tensor &b, Stash &stash) const
{
    assert(&a.engine() == this);
    assert(&b.engine() == this);
    const tensor::Tensor &my_a = static_cast<const tensor::Tensor &>(a);
    const tensor::Tensor &my_b = static_cast<const tensor::Tensor &>(b);
    if (my_a.getType().type() != my_b.getType().type()) {
        return stash.create<ErrorValue>();
    }
    TensorOperationOverride tensor_override(my_a, my_b);
    op.accept(tensor_override);
    if (tensor_override.result) {
        return stash.create<TensorValue>(std::move(tensor_override.result));
    } else {
        return stash.create<ErrorValue>();
    }
}

} // namespace vespalib::tensor
} // namespace vespalib
