// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "wand_parts.h"
#include <vespa/vespalib/objects/visit.hpp>

namespace search {
namespace queryeval {
namespace wand {

void
VectorizedIteratorTerms::visit_members(vespalib::ObjectVisitor &visitor) const {
    visit(visitor, "children", _terms);
}

VectorizedIteratorTerms::VectorizedIteratorTerms(VectorizedIteratorTerms &&) = default;
VectorizedIteratorTerms & VectorizedIteratorTerms::operator=(VectorizedIteratorTerms &&) = default;
VectorizedIteratorTerms::~VectorizedIteratorTerms() { }

} // namespace wand
} // namespace queryeval
} // namespace search

void visit(vespalib::ObjectVisitor &self, const vespalib::string &name,
           const search::queryeval::wand::Term &obj)
{
    self.openStruct(name, "search::queryeval::wand::Term");
    visit(self, "weight",  obj.weight);
    visit(self, "estHits", obj.estHits);
    visit(self, "search",  obj.search);
    self.closeStruct();
}
