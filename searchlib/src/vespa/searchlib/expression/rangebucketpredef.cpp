// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include "rangebucketpredef.h"
#include "integerresultnode.h"
#include "floatresultnode.h"
#include "integerbucketresultnode.h"
#include "floatbucketresultnode.h"
#include <vespa/vespalib/util/stringfmt.h>
#include <stdexcept>
#include <limits>

namespace search {
namespace expression {

IMPLEMENT_EXPRESSIONNODE(RangeBucketPreDefFunctionNode, UnaryFunctionNode);

RangeBucketPreDefFunctionNode::RangeBucketPreDefFunctionNode(const RangeBucketPreDefFunctionNode & rhs) :
    UnaryFunctionNode(rhs),
    _predef(rhs._predef),
    _result(NULL),
    _nullResult(rhs._nullResult),
    _handler()
{
}

RangeBucketPreDefFunctionNode & RangeBucketPreDefFunctionNode::operator = (const RangeBucketPreDefFunctionNode & rhs)
{
    if (this != & rhs) {
        UnaryFunctionNode::operator = (rhs);
        _predef = rhs._predef;
        _result = NULL;
        _nullResult = rhs._nullResult;
        _handler.reset();
    }
    return *this;
}

void
RangeBucketPreDefFunctionNode::onPrepareResult()
{
    const vespalib::Identifiable::RuntimeClass & cInfo(getArg().getResult().getClass());
    if (cInfo.inherits(ResultNodeVector::classId)) {
        if (cInfo.inherits(IntegerResultNodeVector::classId)) {
            _nullResult = & IntegerBucketResultNode::getNull();
        } else if (cInfo.inherits(FloatResultNodeVector::classId)) {
            _nullResult = & FloatBucketResultNode::getNull();
        } else if (cInfo.inherits(StringResultNodeVector::classId)) {
            _nullResult = & StringBucketResultNode::getNull();
        } else if (cInfo.inherits(RawResultNodeVector::classId)) {
            _nullResult = & RawBucketResultNode::getNull();
        } else {
            throw std::runtime_error(vespalib::make_string("cannot create appropriate bucket for type '%s'", cInfo.name()));
        }
        setResultType(ResultNode::UP(_predef->clone()));
        static_cast<ResultNodeVector &>(updateResult()).clear();
        _handler.reset(new MultiValueHandler(*this));
        _result = & updateResult();
    } else {
        if (cInfo.inherits(IntegerResultNode::classId)) {
            _nullResult = & IntegerBucketResultNode::getNull();
        } else if (cInfo.inherits(FloatResultNode::classId)) {
            _nullResult = & FloatBucketResultNode::getNull();
        } else if (cInfo.inherits(StringResultNode::classId)) {
            _nullResult = & StringBucketResultNode::getNull();
        } else if (cInfo.inherits(RawResultNode::classId)) {
            _nullResult = & RawBucketResultNode::getNull();
        } else {
            throw std::runtime_error(vespalib::make_string("cannot create appropriate bucket for type '%s'", cInfo.name()));
        }
        _result = _nullResult;
        if ( ! _predef->empty()) {
            _result = & _predef->get(0);
        }
        _handler.reset(new SingleValueHandler(*this));
    }
}

bool
RangeBucketPreDefFunctionNode::onExecute() const
{
    getArg().execute();
    const ResultNode * result = _handler->handle(getArg().getResult());
    _result = result ? result : _nullResult;
    return true;
}

const ResultNode * RangeBucketPreDefFunctionNode::SingleValueHandler::handle(const ResultNode & arg)
{
    return _predef.find(arg);
}

const ResultNode * RangeBucketPreDefFunctionNode::MultiValueHandler::handle(const ResultNode & arg)
{
    const ResultNodeVector & v = static_cast<const ResultNodeVector &>(arg);
    _result.clear();
    for(size_t i(0), m(v.size()); i < m; i++) {
        const ResultNode * bucket = _predef.find(v.get(i));
        if (bucket != NULL) {
            _result.push_back(*bucket);
        } else {
            _result.push_back(*_nullResult);
        }
    }
    return &_result;
}

vespalib::Serializer &
RangeBucketPreDefFunctionNode::onSerialize(vespalib::Serializer &os) const
{
    UnaryFunctionNode::onSerialize(os);
    return os << _predef;
}

vespalib::Deserializer &
RangeBucketPreDefFunctionNode::onDeserialize(vespalib::Deserializer &is)
{
    UnaryFunctionNode::onDeserialize(is);
    return is >> _predef;
}

void
RangeBucketPreDefFunctionNode::visitMembers(vespalib::ObjectVisitor &visitor) const
{
    UnaryFunctionNode::visitMembers(visitor);
    visit(visitor, "predefined", _predef);
}

}
}

// this function was added by ../../forcelink.sh
void forcelink_file_searchlib_expression_rangebucketpredef() {}
