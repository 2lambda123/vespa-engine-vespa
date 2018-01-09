// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "dense_tensor_apply.h"
#include "dense_tensor_address_combiner.h"
#include "direct_dense_tensor_builder.h"

namespace vespalib::tensor::dense {

template <typename Function>
std::unique_ptr<Tensor>
apply(DenseTensorAddressCombiner & combiner, DirectDenseTensorBuilder & builder,
      CommonDenseTensorCellsIterator & rhsIter, const DenseTensorView &lhs, Function &&func) __attribute__((noinline));

template <typename Function>
std::unique_ptr<Tensor>
apply(DenseTensorAddressCombiner & combiner, DirectDenseTensorBuilder & builder,
      CommonDenseTensorCellsIterator & rhsIter, const DenseTensorView &lhs, Function &&func)
{
    for (DenseTensorCellsIterator lhsItr = lhs.cellsIterator(); lhsItr.valid(); lhsItr.next()) {
        combiner.updateLeftAndCommon(lhsItr.address());
        if (rhsIter.updateCommon(combiner.address())) {
            rhsIter.for_each(combiner.address(), [&combiner, &func, &builder, &lhsItr](const DenseTensorCellsIterator::Address & combined, double rhsCell) {
                builder.insertCell(combined, func(lhsItr.cell(), rhsCell));
            });
        }
    }
    return builder.build();
}


template <typename Function>
std::unique_ptr<Tensor>
apply_no_rightonly_dimensions(DenseTensorAddressCombiner & combiner, DirectDenseTensorBuilder & builder,
                              CommonDenseTensorCellsIterator & rhsIter,
                              const DenseTensorView &lhs, Function &&func)  __attribute__((noinline));

template <typename Function>
std::unique_ptr<Tensor>
apply_no_rightonly_dimensions(DenseTensorAddressCombiner & combiner, DirectDenseTensorBuilder & builder,
                              CommonDenseTensorCellsIterator & rhsIter,
                              const DenseTensorView &lhs, Function &&func)
{
    for (DenseTensorCellsIterator lhsItr = lhs.cellsIterator(); lhsItr.valid(); lhsItr.next()) {
        combiner.updateLeftAndCommon(lhsItr.address());
        if (rhsIter.updateCommon(combiner.address())) {
            builder.insertCell(combiner.address(), func(lhsItr.cell(), rhsIter.cell()));
        }
    }
    return builder.build();
}

template <typename Function>
std::unique_ptr<Tensor>
apply(const DenseTensorView &lhs, const DenseTensorView &rhs, Function &&func)
{
    DenseTensorAddressCombiner combiner(lhs.fast_type(), rhs.fast_type());
    DirectDenseTensorBuilder builder(DenseTensorAddressCombiner::combineDimensions(lhs.fast_type(), rhs.fast_type()));
    CommonDenseTensorCellsIterator rhsIter(combiner.getCommonRight(), combiner.getRight(), rhs.fast_type(), rhs.cellsRef());
    if (combiner.hasAnyRightOnlyDimensions()) {
        return apply(combiner, builder, rhsIter, lhs, std::move(func));
    } else {
        return apply_no_rightonly_dimensions(combiner, builder, rhsIter, lhs, std::move(func));
    }
}

template <typename Function>
std::unique_ptr<Tensor>
apply(const DenseTensorView &lhs, const Tensor &rhs, Function &&func)
{
    const DenseTensorView *view = dynamic_cast<const DenseTensorView *>(&rhs);
    if (view) {
        return apply(lhs, *view, func);
    }
    const DenseTensor *dense = dynamic_cast<const DenseTensor *>(&rhs);
    if (dense) {
        return apply(lhs, DenseTensorView(*dense), func);
    }
    return Tensor::UP();
}

}
