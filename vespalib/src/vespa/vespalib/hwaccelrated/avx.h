// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "sse2.h"

namespace vespalib {

namespace hwaccelrated {

/**
 * Avx-256 implementation.
 */
class AvxAccelrator : public Sse2Accelrator
{
public:
    float dotProduct(const float * a, const float * b, size_t sz) const override;
    double dotProduct(const double * a, const double * b, size_t sz) const override;
};

}
}
