// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/eval/eval/tensor_function.h>

namespace vespalib::tensor {

/**
 * Tensor function for inplace map operation on mutable dense tensors.
 **/
class DenseInplaceMapFunction : public eval::tensor_function::Map
{
public:
    using map_fun_t = ::vespalib::eval::tensor_function::map_fun_t;
    DenseInplaceMapFunction(const eval::ValueType &result_type,
                            const eval::TensorFunction &child,
                            map_fun_t function_in);
    ~DenseInplaceMapFunction();
    bool result_is_mutable() const override { return true; }
    eval::InterpretedFunction::Instruction compile_self(const eval::TensorEngine &engine, Stash &stash) const override;
    static const eval::TensorFunction &optimize(const eval::TensorFunction &expr, Stash &stash);
};

} // namespace vespalib::tensor
