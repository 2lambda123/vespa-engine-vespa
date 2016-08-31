// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "documentdbconfig.h"

using namespace config;
using namespace vespa::config::search::summary;
using namespace vespa::config::search;

using document::DocumentTypeRepo;
using document::DocumenttypesConfig;
using search::TuneFileDocumentDB;
using search::index::Schema;
using vespa::config::search::SummarymapConfig;
using vespa::config::search::core::RankingConstantsConfig;

namespace proton {

DocumentDBConfig::ComparisonResult::ComparisonResult()
    : rankProfilesChanged(false),
      rankingConstantsChanged(false),
      indexschemaChanged(false),
      attributesChanged(false),
      summaryChanged(false),
      summarymapChanged(false),
      juniperrcChanged(false),
      _documenttypesChanged(false),
      _documentTypeRepoChanged(false),
      _tuneFileDocumentDBChanged(false),
      _schemaChanged(false),
      _maintenanceChanged(false)
{
}

DocumentDBConfig::DocumentDBConfig(
               int64_t generation,
               const RankProfilesConfigSP &rankProfiles,
               const RankingConstantsConfigSP &rankingConstants,
               const IndexschemaConfigSP &indexschema,
               const AttributesConfigSP &attributes,
               const SummaryConfigSP &summary,
               const SummarymapConfigSP &summarymap,
               const JuniperrcConfigSP &juniperrc,
               const DocumenttypesConfigSP &documenttypes,
               const DocumentTypeRepo::SP &repo,
               const search::TuneFileDocumentDB::SP &tuneFileDocumentDB,
               const Schema::SP &schema,
               const DocumentDBMaintenanceConfig::SP &maintenance,
               const vespalib::string &configId,
               const vespalib::string &docTypeName,
               const config::ConfigSnapshot & extraConfigs)
    : _configId(configId),
      _docTypeName(docTypeName),
      _generation(generation),
      _rankProfiles(rankProfiles),
      _rankingConstants(rankingConstants),
      _indexschema(indexschema),
      _attributes(attributes),
      _summary(summary),
      _summarymap(summarymap),
      _juniperrc(juniperrc),
      _documenttypes(documenttypes),
      _repo(repo),
      _tuneFileDocumentDB(tuneFileDocumentDB),
      _schema(schema),
      _maintenance(maintenance),
      _extraConfigs(extraConfigs),
      _orig()
{
}


DocumentDBConfig::
DocumentDBConfig(const DocumentDBConfig &cfg)
    : _configId(cfg._configId),
      _docTypeName(cfg._docTypeName),
      _generation(cfg._generation),
      _rankProfiles(cfg._rankProfiles),
      _rankingConstants(cfg._rankingConstants),
      _indexschema(cfg._indexschema),
      _attributes(cfg._attributes),
      _summary(cfg._summary),
      _summarymap(cfg._summarymap),
      _juniperrc(cfg._juniperrc),
      _documenttypes(cfg._documenttypes),
      _repo(cfg._repo),
      _tuneFileDocumentDB(cfg._tuneFileDocumentDB),
      _schema(cfg._schema),
      _maintenance(cfg._maintenance),
      _extraConfigs(cfg._extraConfigs),
      _orig(cfg._orig)
{
}

bool
DocumentDBConfig::operator==(const DocumentDBConfig & rhs) const
{
    return equals<RankProfilesConfig>(_rankProfiles.get(),
                                      rhs._rankProfiles.get()) &&
           equals<RankingConstantsConfig>(_rankingConstants.get(),
                                          rhs._rankingConstants.get()) &&
           equals<IndexschemaConfig>(_indexschema.get(),
                                     rhs._indexschema.get()) &&
           equals<AttributesConfig>(_attributes.get(),
                                    rhs._attributes.get()) &&
           equals<SummaryConfig>(_summary.get(),
                                 rhs._summary.get()) &&
           equals<SummarymapConfig>(_summarymap.get(),
                                    rhs._summarymap.get()) &&
           equals<JuniperrcConfig>(_juniperrc.get(),
                                   rhs._juniperrc.get()) &&
           equals<DocumenttypesConfig>(_documenttypes.get(),
                                       rhs._documenttypes.get()) &&
           _repo.get() == rhs._repo.get() &&
           equals<TuneFileDocumentDB>(_tuneFileDocumentDB.get(),
                                      rhs._tuneFileDocumentDB.get()) &&
           equals<Schema>(_schema.get(),
                          rhs._schema.get()) &&
        equals<DocumentDBMaintenanceConfig>(_maintenance.get(),
                rhs._maintenance.get());
}


DocumentDBConfig::ComparisonResult
DocumentDBConfig::compare(const DocumentDBConfig &rhs) const
{
    ComparisonResult retval;
    retval.rankProfilesChanged =
        !equals<RankProfilesConfig>(_rankProfiles.get(), rhs._rankProfiles.get());
    retval.rankingConstantsChanged =
        !equals<RankingConstantsConfig>(_rankingConstants.get(), rhs._rankingConstants.get());
    retval.indexschemaChanged =
        !equals<IndexschemaConfig>(_indexschema.get(), rhs._indexschema.get());
    retval.attributesChanged =
        !equals<AttributesConfig>(_attributes.get(), rhs._attributes.get());
    retval.summaryChanged =
        !equals<SummaryConfig>(_summary.get(), rhs._summary.get());
    retval.summarymapChanged =
        !equals<SummarymapConfig>(_summarymap.get(), rhs._summarymap.get());
    retval.juniperrcChanged =
        !equals<JuniperrcConfig>(_juniperrc.get(), rhs._juniperrc.get());
    retval._documenttypesChanged =
        !equals<DocumenttypesConfig>(_documenttypes.get(),
                rhs._documenttypes.get());
    retval._documentTypeRepoChanged = _repo.get() != rhs._repo.get();
    retval._tuneFileDocumentDBChanged =
        !equals<TuneFileDocumentDB>(_tuneFileDocumentDB.get(),
                rhs._tuneFileDocumentDB.get());
    retval._schemaChanged =
        !equals<Schema>(_schema.get(), rhs._schema.get());
    retval._maintenanceChanged =
        !equals<DocumentDBMaintenanceConfig>(_maintenance.get(),
                rhs._maintenance.get());
    return retval;
}


bool
DocumentDBConfig::valid(void) const
{
    return _rankProfiles.get() != NULL &&
       _rankingConstants.get() != NULL &&
            _indexschema.get() != NULL &&
             _attributes.get() != NULL &&
                _summary.get() != NULL &&
             _summarymap.get() != NULL &&
              _juniperrc.get() != NULL &&
          _documenttypes.get() != NULL &&
                   _repo.get() != NULL &&
     _tuneFileDocumentDB.get() != NULL &&
                 _schema.get() != NULL &&
            _maintenance.get() != NULL;
}

namespace
{

template <class Config>
std::shared_ptr<Config>
emptyConfig(std::shared_ptr<Config> config)
{
    std::shared_ptr<Config> empty(std::make_shared<Config>());
    
    if (!config || *config != *empty) {
        return empty;
    }
    return config;
}

}


DocumentDBConfig::SP
DocumentDBConfig::makeReplayConfig(const SP & orig)
{
    const DocumentDBConfig &o = *orig;
    
    SP ret = std::make_shared<DocumentDBConfig>(
                o._generation,
                emptyConfig(o._rankProfiles),
                emptyConfig(o._rankingConstants),
                o._indexschema,
                o._attributes,
                o._summary,
                o._summarymap,
                o._juniperrc,
                o._documenttypes,
                o._repo,
                o._tuneFileDocumentDB,
                o._schema,
                o._maintenance,
                o._configId,
                o._docTypeName,
                o._extraConfigs);
    ret->_orig = orig;
    return ret;
}


DocumentDBConfig::SP
DocumentDBConfig::getOriginalConfig() const
{
    return _orig;
}


DocumentDBConfig::SP
DocumentDBConfig::preferOriginalConfig(const SP & self)
{
    return (self && self->_orig) ? self->_orig : self;
}


DocumentDBConfig::SP
DocumentDBConfig::newFromAttributesConfig(const AttributesConfigSP &attributes) const
{
    return std::make_shared<DocumentDBConfig>(
            _generation,
            _rankProfiles,
            _rankingConstants,
            _indexschema,
            attributes,
            _summary,
            _summarymap,
            _juniperrc,
            _documenttypes,
            _repo,
            _tuneFileDocumentDB,
            _schema,
            _maintenance,
            _configId,
            _docTypeName,
            _extraConfigs);
}

} // namespace proton
