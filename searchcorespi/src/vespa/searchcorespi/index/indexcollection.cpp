// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".searchcorespi.index.indexcollection");

#include "indexcollection.h"
#include <vespa/searchlib/queryeval/isourceselector.h>
#include <vespa/searchlib/queryeval/create_blueprint_visitor_helper.h>
#include <vespa/searchlib/queryeval/intermediate_blueprints.h>
#include <vespa/searchlib/queryeval/leaf_blueprints.h>
#include "indexsearchablevisitor.h"

using namespace search::queryeval;
using namespace search::query;
using search::attribute::IAttributeContext;

namespace searchcorespi {

IndexCollection::IndexCollection(const ISourceSelector::SP & selector)
    : _source_selector(selector),
      _sources()
{
}

IndexCollection::IndexCollection(const ISourceSelector::SP & selector,
                                 const ISearchableIndexCollection &sources)
    : _source_selector(selector),
      _sources()
{
    for (size_t i(0), m(sources.getSourceCount()); i < m; i++) {
        append(sources.getSourceId(i), sources.getSearchableSP(i));
    }
    setCurrentIndex(sources.getCurrentIndex());
}

void
IndexCollection::setSource(uint32_t docId)
{
    assert( valid() );
    _source_selector->setSource(docId, getCurrentIndex());
}

ISearchableIndexCollection::UP
IndexCollection::replaceAndRenumber(const ISourceSelector::SP & selector,
                                    const ISearchableIndexCollection &fsc,
                                    uint32_t id_diff,
                                    const IndexSearchable::SP &new_source)
{
    ISearchableIndexCollection::UP new_fsc(new IndexCollection(selector));
    new_fsc->append(0, new_source);
    for (size_t i = 0; i < fsc.getSourceCount(); ++i) {
        if (fsc.getSourceId(i) > id_diff) {
            new_fsc->append(fsc.getSourceId(i) - id_diff,
                            fsc.getSearchableSP(i));
        }
    }
    return new_fsc;
}

void
IndexCollection::append(uint32_t id, const IndexSearchable::SP &fs)
{
    _sources.push_back(SourceWithId(id, fs));
}

IndexSearchable::SP
IndexCollection::getSearchableSP(uint32_t i) const
{
    return _sources[i].source_wrapper;
}

void
IndexCollection::replace(uint32_t id, const IndexSearchable::SP &fs)
{
    for (size_t i = 0; i < _sources.size(); ++i) {
        if (_sources[i].id == id) {
            _sources[i].source_wrapper = fs;
            return;
        }
    }
    LOG(warning, "Tried to replace Searchable %d, but it wasn't there.", id);
    append(id, fs);
}

const ISourceSelector &
IndexCollection::getSourceSelector() const
{
    return *_source_selector;
}

size_t
IndexCollection::getSourceCount() const
{
    return _sources.size();
}

IndexSearchable &
IndexCollection::getSearchable(uint32_t i) const
{
    return *_sources[i].source_wrapper;
}

uint32_t
IndexCollection::getSourceId(uint32_t i) const
{
    return _sources[i].id;
}

search::SearchableStats
IndexCollection::getSearchableStats() const
{
    search::SearchableStats stats;
    for (size_t i = 0; i < _sources.size(); ++i) {
        stats.add(_sources[i].source_wrapper->getSearchableStats());
    }
    return stats;
}

search::SerialNum
IndexCollection::getSerialNum() const
{
    search::SerialNum serialNum = 0;
    for (auto &source : _sources) {
        serialNum = std::max(serialNum, source.source_wrapper->getSerialNum());
    }
    return serialNum;
}


void
IndexCollection::accept(IndexSearchableVisitor &visitor) const
{
    for (auto &source : _sources) {
        source.source_wrapper->accept(visitor);
    }
}

namespace {

struct Mixer {
    const ISourceSelector                &_selector;
    std::unique_ptr<SourceBlenderBlueprint> _blender;

    Mixer(const ISourceSelector &selector)
        : _selector(selector), _blender() {}

    void addIndex(Blueprint::UP index) {
        if (_blender.get() == NULL) {
            _blender.reset(new SourceBlenderBlueprint(_selector));
        }
        _blender->addChild(std::move(index));
    }

    Blueprint::UP mix() {
        if (_blender.get() == NULL) {
            return Blueprint::UP(new EmptyBlueprint());
        }
        return Blueprint::UP(_blender.release());
    }
};

class CreateBlueprintVisitor : public search::query::QueryVisitor {
private:
    const IIndexCollection  &_indexes;
    const FieldSpecList     &_fields;
    const IAttributeContext &_attrCtx;
    const IRequestContext   &_requestContext;
    Blueprint::UP            _result;

    template <typename NodeType>
    void visitTerm(NodeType &n) {
        Mixer mixer(_indexes.getSourceSelector());
        for (size_t i = 0; i < _indexes.getSourceCount(); ++i) {
            Blueprint::UP blueprint = _indexes.getSearchable(i).createBlueprint(_requestContext, _fields, n, _attrCtx);
            blueprint->setSourceId(_indexes.getSourceId(i));
            mixer.addIndex(std::move(blueprint));
        }
        _result = mixer.mix();
    }

    virtual void visit(And &)     { }
    virtual void visit(AndNot &)  { }
    virtual void visit(Or &)      { }
    virtual void visit(WeakAnd &) { }
    virtual void visit(Equiv &)   { }
    virtual void visit(Rank &)    { }
    virtual void visit(Near &)    { }
    virtual void visit(ONear &)   { }

    virtual void visit(WeightedSetTerm &n) { visitTerm(n); }
    virtual void visit(DotProduct &n)      { visitTerm(n); }
    virtual void visit(WandTerm &n)        { visitTerm(n); }
    virtual void visit(Phrase &n)          { visitTerm(n); }
    virtual void visit(NumberTerm &n)      { visitTerm(n); }
    virtual void visit(LocationTerm &n)    { visitTerm(n); }
    virtual void visit(PrefixTerm &n)      { visitTerm(n); }
    virtual void visit(RangeTerm &n)       { visitTerm(n); }
    virtual void visit(StringTerm &n)      { visitTerm(n); }
    virtual void visit(SubstringTerm &n)   { visitTerm(n); }
    virtual void visit(SuffixTerm &n)      { visitTerm(n); }
    virtual void visit(PredicateQuery &n)  { visitTerm(n); }
    virtual void visit(RegExpTerm &n)      { visitTerm(n); }

public:
    CreateBlueprintVisitor(const IIndexCollection &indexes,
                           const FieldSpecList &fields,
                           const IAttributeContext &attrCtx,
                           const IRequestContext & requestContext)
        : _indexes(indexes),
          _fields(fields),
          _attrCtx(attrCtx),
          _requestContext(requestContext),
          _result() {}

    Blueprint::UP getResult() { return std::move(_result); }
};

}

Blueprint::UP
IndexCollection::createBlueprint(const IRequestContext & requestContext,
                                 const FieldSpec &field,
                                 const Node &term,
                                 const IAttributeContext &attrCtx)
{
    FieldSpecList fields;
    fields.add(field);
    return createBlueprint(requestContext, fields, term, attrCtx);
}

Blueprint::UP
IndexCollection::createBlueprint(const IRequestContext & requestContext,
                                 const FieldSpecList &fields,
                                 const Node &term,
                                 const IAttributeContext &attrCtx)
{
    CreateBlueprintVisitor visitor(*this, fields, attrCtx, requestContext);
    const_cast<Node &>(term).accept(visitor);
    return visitor.getResult();
}

}  // namespace searchcorespi
