// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "tensor_nodes.h"
#include "node_visitor.h"

namespace vespalib {
namespace eval {
namespace nodes {

void TensorSum  ::accept(NodeVisitor &visitor) const { visitor.visit(*this); }

} // namespace vespalib::eval::nodes
} // namespace vespalib::eval
} // namespace vespalib
