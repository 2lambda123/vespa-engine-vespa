// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "avx.h"

namespace vespalib {

namespace hwaccelrated {

/**
 * Avx-512 implementation.
 */
class Avx2Accelrator : public AvxAccelrator
{
public:
    float dotProduct(const float * a, const float * b, size_t sz) const override;
    double dotProduct(const double * a, const double * b, size_t sz) const override;
};

}
}
