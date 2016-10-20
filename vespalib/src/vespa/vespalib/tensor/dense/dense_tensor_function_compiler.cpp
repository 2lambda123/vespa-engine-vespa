// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "dense_dot_product_function.h"
#include "dense_tensor_function_compiler.h"
#include <vespa/vespalib/eval/operation_visitor.h>
#include <vespa/vespalib/eval/operation_visitor.h>
#include <vespa/vespalib/test/insertion_operators.h>
#include <iostream>

using namespace vespalib::eval;
using namespace vespalib::eval::tensor_function;
using namespace vespalib::eval::operation;

namespace vespalib {
namespace tensor {

namespace {

template <typename T>
bool    
isType(const BinaryOperation &op)
{
    return (as<T>(op) != nullptr);
}

bool
willReduceAllDimensions(const std::vector<vespalib::string> &dimensions)
{
    return (dimensions.empty() || (dimensions.size() == 1));
}

bool
is1dBoundDenseTensor(const ValueType &type)
{
    return (type.is_dense() && (type.dimensions().size() == 1) && type.dimensions()[0].is_bound());
}

bool
isCompatibleTensorsForDotProduct(const ValueType &lhsType, const ValueType &rhsType)
{
    return (is1dBoundDenseTensor(lhsType) &&
            is1dBoundDenseTensor(rhsType) &&
            (lhsType.dimensions()[0].name == rhsType.dimensions()[0].name));
}

struct DotProductFunctionCompiler
{
    static TensorFunction::UP compile(Node_UP expr) {
        const Reduce *reduce = as<Reduce>(*expr);
        if (reduce && isType<Add>(*reduce->op) && willReduceAllDimensions(reduce->dimensions)) {
            const Apply *apply = as<Apply>(*reduce->tensor);
            if (apply && isType<Mul>(*apply->op)) {
                const Inject *lhsTensor = as<Inject>(*apply->lhs_tensor);
                const Inject *rhsTensor = as<Inject>(*apply->rhs_tensor);
                if (lhsTensor && rhsTensor &&
                    isCompatibleTensorsForDotProduct(lhsTensor->result_type, rhsTensor->result_type))
                {
                    return std::make_unique<DenseDotProductFunction>(std::make_unique<Inject>(lhsTensor->result_type, lhsTensor->tensor_id),
                                                                     std::make_unique<Inject>(rhsTensor->result_type, rhsTensor->tensor_id));
                }
            }
        }
        return std::move(expr);
    }
};

}

TensorFunction::UP
DenseTensorFunctionCompiler::compile(Node_UP expr)
{
    return DotProductFunctionCompiler::compile(std::move(expr));
}

} // namespace tensor
} // namespace vespalib
