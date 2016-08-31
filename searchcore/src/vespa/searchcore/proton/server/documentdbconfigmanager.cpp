// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "documentdbconfigmanager.h"
#include <vespa/log/log.h>
#include <vespa/searchcommon/common/schemaconfigurer.h>
#include <vespa/searchlib/index/schemautil.h>
LOG_SETUP(".proton.server.documentdbconfigmanager");
#include <vespa/config/helper/legacy.h>
#include <vespa/config/file_acquirer/file_acquirer.h>

using namespace config;
using namespace vespa::config::search::core;
using namespace vespa::config::search::summary;
using namespace vespa::config::search;

using document::DocumentTypeRepo;
using search::TuneFileDocumentDB;
using search::index::Schema;
using search::index::SchemaBuilder;
using fastos::TimeStamp;

// RankingConstantsConfigBuilder
// RankingConstantsConfig


namespace proton {

const ConfigKeySet
DocumentDBConfigManager::createConfigKeySet() const
{
    ConfigKeySet set;
    set.add<RankProfilesConfig,
            RankingConstantsConfig,
            IndexschemaConfig,
            AttributesConfig,
            SummaryConfig,
            SummarymapConfig,
            JuniperrcConfig>(_configId);
    set.add(_extraConfigKeys);
    return set;
}

Schema::SP
DocumentDBConfigManager::
buildNewSchema(const AttributesConfig & newAttributesConfig,
               const SummaryConfig & newSummaryConfig,
               const IndexschemaConfig & newIndexschemaConfig)
{
    // Called with lock held
    Schema::SP schema(new Schema);
    SchemaBuilder::build(newAttributesConfig, *schema);
    SchemaBuilder::build(newSummaryConfig, *schema);
    SchemaBuilder::build(newIndexschemaConfig, *schema);
    return schema;
}


Schema::SP
DocumentDBConfigManager::
buildSchema(const AttributesConfig & newAttributesConfig,
            const SummaryConfig & newSummaryConfig,
            const IndexschemaConfig & newIndexschemaConfig)
{
    // Called with lock held
    Schema::SP oldSchema;
    if (_pendingConfigSnapshot.get() != NULL)
        oldSchema = _pendingConfigSnapshot->getSchemaSP();
    if (oldSchema.get() == NULL)
        return buildNewSchema(newAttributesConfig,
                              newSummaryConfig, newIndexschemaConfig);
    const DocumentDBConfig &old = *_pendingConfigSnapshot;
    if (old.getAttributesConfig() != newAttributesConfig ||
        old.getSummaryConfig() != newSummaryConfig ||
        old.getIndexschemaConfig() != newIndexschemaConfig) {
        Schema::SP schema(buildNewSchema(newAttributesConfig,
                                  newSummaryConfig, newIndexschemaConfig));
        return (*oldSchema == *schema) ? oldSchema : schema;
    }
    return oldSchema;
}


static DocumentDBMaintenanceConfig::SP
buildMaintenanceConfig(const BootstrapConfig::SP &bootstrapConfig,
                       const vespalib::string &docTypeName)
{
    typedef ProtonConfig::Documentdb DdbConfig;
    ProtonConfig &proton(bootstrapConfig->getProtonConfig());

    TimeStamp visibilityDelay;
    // Use document type to find document db config in proton config
    uint32_t index;
    for (index = 0; index < proton.documentdb.size(); ++index) {
        const DdbConfig &ddbConfig = proton.documentdb[index];
        if (docTypeName == ddbConfig.inputdoctypename)
            break;
    }
    double pruneRemovedDocumentsInterval = proton.pruneremoveddocumentsinterval;
    double pruneRemovedDocumentsAge = proton.pruneremoveddocumentsage;

    if (index < proton.documentdb.size()) {
        const DdbConfig &ddbConfig = proton.documentdb[index];
        visibilityDelay = TimeStamp::Seconds(std::min(proton.maxvisibilitydelay, ddbConfig.visibilitydelay));
    }
    return DocumentDBMaintenanceConfig::SP(
            new DocumentDBMaintenanceConfig(
                    DocumentDBPruneRemovedDocumentsConfig(
                            pruneRemovedDocumentsInterval,
                            pruneRemovedDocumentsAge),
                    DocumentDBHeartBeatConfig(),
                    DocumentDBWipeOldRemovedFieldsConfig(
                            proton.wipeoldremovedfieldsinterval,
                            proton.wipeoldremovedfieldsage),
                    proton.grouping.sessionmanager.pruning.interval,
                    visibilityDelay,
                    DocumentDBLidSpaceCompactionConfig(
                            proton.lidspacecompaction.interval,
                            proton.lidspacecompaction.allowedlidbloat,
                            proton.lidspacecompaction.allowedlidbloatfactor),
                    AttributeUsageFilterConfig(
                            proton.writefilter.attribute.enumstorelimit,
                            proton.writefilter.attribute.multivaluelimit),
                    proton.writefilter.sampleinterval));
}


void
DocumentDBConfigManager::update(const ConfigSnapshot & snapshot)
{
    typedef DocumentDBConfig::RankProfilesConfigSP RankProfilesConfigSP;
    typedef DocumentDBConfig::RankingConstantsConfigSP RankingConstantsConfigSP;
    typedef DocumentDBConfig::IndexschemaConfigSP IndexschemaConfigSP;
    typedef DocumentDBConfig::AttributesConfigSP AttributesConfigSP;
    typedef DocumentDBConfig::SummaryConfigSP SummaryConfigSP;
    typedef DocumentDBConfig::SummarymapConfigSP SummarymapConfigSP;
    typedef DocumentDBConfig::JuniperrcConfigSP JuniperrcConfigSP;
    typedef DocumentDBConfig::MaintenanceConfigSP MaintenanceConfigSP;

    DocumentDBConfig::SP current = _pendingConfigSnapshot;
    RankProfilesConfigSP newRankProfilesConfig;
    RankingConstantsConfigSP newRankingConstantsConfig;
    IndexschemaConfigSP newIndexschemaConfig;
    AttributesConfigSP newAttributesConfig;
    SummaryConfigSP newSummaryConfig;
    SummarymapConfigSP newSummarymapConfig;
    JuniperrcConfigSP newJuniperrcConfig;
    MaintenanceConfigSP oldMaintenanceConfig;
    MaintenanceConfigSP newMaintenanceConfig;

    if (!_ignoreForwardedConfig) {
        if (_bootstrapConfig->getDocumenttypesConfigSP().get() == NULL ||
            _bootstrapConfig->getDocumentTypeRepoSP().get() == NULL ||
            _bootstrapConfig->getProtonConfigSP().get() == NULL ||
            _bootstrapConfig->getTuneFileDocumentDBSP().get() == NULL)
            return;
    }


    int64_t generation = snapshot.getGeneration();
    LOG(debug,
        "Forwarded generation %" PRId64 ", generation %" PRId64,
        _bootstrapConfig->getGeneration(), generation);
    if (!_ignoreForwardedConfig &&
        _bootstrapConfig->getGeneration() != generation)
            return;

    int64_t currentGeneration = -1;
    if (current.get() != NULL) {
        newRankProfilesConfig = current->getRankProfilesConfigSP();
        newRankingConstantsConfig = current->getRankingConstantsConfigSP();
        newIndexschemaConfig = current->getIndexschemaConfigSP();
        newAttributesConfig = current->getAttributesConfigSP();
        newSummaryConfig = current->getSummaryConfigSP();
        newSummarymapConfig = current->getSummarymapConfigSP();
        newJuniperrcConfig = current->getJuniperrcConfigSP();
        oldMaintenanceConfig = current->getMaintenanceConfigSP();
        currentGeneration = current->getGeneration();
    }

    if (snapshot.isChanged<RankProfilesConfig>(_configId, currentGeneration)) {
        newRankProfilesConfig =
            RankProfilesConfigSP(
                    snapshot.getConfig<RankProfilesConfig>(_configId).
                    release());
    }
    if (snapshot.isChanged<RankingConstantsConfig>(_configId, currentGeneration)) {
        newRankingConstantsConfig = 
            RankingConstantsConfigSP(
                    snapshot.getConfig<RankingConstantsConfig>(_configId)
                    .release());
        const vespalib::string &spec = _bootstrapConfig->getFiledistributorrpcConfig().connectionspec;
        if (spec != "") {
            config::RpcFileAcquirer fileAcquirer(spec);
            for (const RankingConstantsConfig::Constant &rc : newRankingConstantsConfig->constant) {
                vespalib::string filePath = fileAcquirer.wait_for(rc.fileref, 5*60);
                fprintf(stderr, "GOT file-acq PATH is: %s (ref %s for name %s type %s)\n",
                        filePath.c_str(), rc.fileref.c_str(), rc.name.c_str(), rc.type.c_str());
            }
        }
    }
    if (snapshot.isChanged<IndexschemaConfig>(_configId, currentGeneration)) {
        std::unique_ptr<IndexschemaConfig> indexschemaConfig =
            snapshot.getConfig<IndexschemaConfig>(_configId);
        search::index::Schema schema;
        search::index::SchemaBuilder::build(*indexschemaConfig, schema);
        if (!search::index::SchemaUtil::validateSchema(schema)) {
            LOG(error,
                "Cannot use bad index schema, validation failed");
            abort();
        }
        newIndexschemaConfig =
            IndexschemaConfigSP(indexschemaConfig.release());
    }
    if (snapshot.isChanged<AttributesConfig>(_configId, currentGeneration))
        newAttributesConfig =
            AttributesConfigSP(snapshot.getConfig<AttributesConfig>(_configId).
                               release());
    if (snapshot.isChanged<SummaryConfig>(_configId, currentGeneration))
        newSummaryConfig =
            SummaryConfigSP(snapshot.getConfig<SummaryConfig>(_configId).
                            release());
    if (snapshot.isChanged<SummarymapConfig>(_configId, currentGeneration))
        newSummarymapConfig =
            SummarymapConfigSP(snapshot.getConfig<SummarymapConfig>(_configId).
                               release());
    if (snapshot.isChanged<JuniperrcConfig>(_configId, currentGeneration))
        newJuniperrcConfig =
            JuniperrcConfigSP(
                    snapshot.getConfig<JuniperrcConfig>(_configId).release());

    Schema::SP schema(buildSchema(*newAttributesConfig,
                                  *newSummaryConfig,
                                  *newIndexschemaConfig));
    newMaintenanceConfig = buildMaintenanceConfig(_bootstrapConfig,
                                                  _docTypeName);
    if (newMaintenanceConfig.get() != NULL &&
        oldMaintenanceConfig.get() != NULL &&
        *newMaintenanceConfig == *oldMaintenanceConfig) {
        newMaintenanceConfig = oldMaintenanceConfig;
    }
    ConfigSnapshot extraConfigs(snapshot.subset(_extraConfigKeys));
    DocumentDBConfig::SP newSnapshot(
            new DocumentDBConfig(generation,
                                 newRankProfilesConfig,
                                 newRankingConstantsConfig,
                                 newIndexschemaConfig,
                                 newAttributesConfig,
                                 newSummaryConfig,
                                 newSummarymapConfig,
                                 newJuniperrcConfig,
                                 _bootstrapConfig->getDocumenttypesConfigSP(),
                                 _bootstrapConfig->getDocumentTypeRepoSP(),
                                 _bootstrapConfig->getTuneFileDocumentDBSP(),
                                 schema,
                                 newMaintenanceConfig,
                                 _configId,
                                 _docTypeName,
                                 extraConfigs));
    assert(newSnapshot->valid());
    {
        vespalib::LockGuard lock(_pendingConfigLock);
        _pendingConfigSnapshot = newSnapshot;
    }
}


DocumentDBConfigManager::
DocumentDBConfigManager(const vespalib::string &configId,
                        const vespalib::string &docTypeName)
    : _configId(configId),
      _docTypeName(docTypeName),
      _bootstrapConfig(),
      _pendingConfigSnapshot(),
      _ignoreForwardedConfig(true),
      _pendingConfigLock(),
      _extraConfigKeys()
{
}

void
DocumentDBConfigManager::
forwardConfig(const BootstrapConfig::SP & config)
{
    {
        if (!_ignoreForwardedConfig &&
            config->getGeneration() < _bootstrapConfig->getGeneration())
            return;	// Enforce time direction
        _bootstrapConfig = config;
        _ignoreForwardedConfig = false;
    }
}

} // namespace proton
