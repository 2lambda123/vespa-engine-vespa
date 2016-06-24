// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/searchlib/fef/blueprint.h>
#include <vespa/searchlib/fef/featureexecutor.h>

namespace search {
namespace features {

/**
 * Implements the executor for the matchCount feature for index and
 * attribute fields.
 */
class MatchCountExecutor : public fef::FeatureExecutor
{
private:
    std::vector<fef::TermFieldHandle> _handles;

public:
    MatchCountExecutor(uint32_t fieldId, const fef::IQueryEnvironment &env);
    void execute(fef::MatchData & data) override;
};

/**
 * Implements the blueprint for the matchCount executor.
 *
 * matchCount(name)
 *  - returns number of matches of the query in the particular field.
 */
class MatchCountBlueprint : public fef::Blueprint
{
private:
    const fef::FieldInfo *_field;

public:
    MatchCountBlueprint();

    void visitDumpFeatures(const fef::IIndexEnvironment & env,
                           fef::IDumpFeatureVisitor & visitor) const override;

    fef::Blueprint::UP createInstance() const override;

    fef::ParameterDescriptions getDescriptions() const override {
        return fef::ParameterDescriptions().desc().field();
    }

    bool setup(const fef::IIndexEnvironment & env,
               const fef::ParameterList & params) override;

    fef::FeatureExecutor::LP createExecutor(const fef::IQueryEnvironment & env) const override;
};

} // namespace features
} // namespace search

