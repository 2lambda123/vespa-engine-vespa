// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "document_db_maintenance_config.h"
#include <vespa/document/config/config-documenttypes.h>
#include <vespa/document/repo/documenttyperepo.h>
#include <vespa/searchlib/common/tunefileinfo.h>
#include <vespa/searchsummary/config/config-juniperrc.h>
#include <vespa/searchcommon/common/schema.h>
#include <vespa/config-attributes.h>
#include <vespa/config-indexschema.h>
#include <vespa/config-rank-profiles.h>
#include <vespa/config-summary.h>
#include <vespa/config-summarymap.h>
#include <vespa/config/retriever/configkeyset.h>
#include <vespa/config/retriever/configsnapshot.h>
#include <vespa/searchcore/config/config-ranking-constants.h>

namespace proton {

class DocumentDBConfig
{
public:
    class ComparisonResult
    {
    public:
        bool rankProfilesChanged;
        bool rankingConstantsChanged;
        bool indexschemaChanged;
        bool attributesChanged;
        bool summaryChanged;
        bool summarymapChanged;
        bool juniperrcChanged;
        bool _documenttypesChanged;
        bool _documentTypeRepoChanged;
        bool _tuneFileDocumentDBChanged;
        bool _schemaChanged;
        bool _maintenanceChanged;

        ComparisonResult();
    };

    typedef std::shared_ptr<DocumentDBConfig> SP;
    typedef std::shared_ptr<vespa::config::search::IndexschemaConfig> IndexschemaConfigSP;
    typedef std::shared_ptr<vespa::config::search::AttributesConfig> AttributesConfigSP;
    typedef std::shared_ptr<vespa::config::search::RankProfilesConfig> RankProfilesConfigSP;
    typedef std::shared_ptr<vespa::config::search::core::RankingConstantsConfig> RankingConstantsConfigSP;
    typedef std::shared_ptr<vespa::config::search::SummaryConfig> SummaryConfigSP;
    typedef std::shared_ptr<vespa::config::search::SummarymapConfig> SummarymapConfigSP;
    typedef std::shared_ptr<vespa::config::search::summary::JuniperrcConfig> JuniperrcConfigSP;
    typedef std::shared_ptr<document::DocumenttypesConfig> DocumenttypesConfigSP;
    typedef DocumentDBMaintenanceConfig::SP MaintenanceConfigSP;

private:
    vespalib::string               _configId;
    vespalib::string               _docTypeName;
    int64_t                        _generation;
    RankProfilesConfigSP           _rankProfiles;
    RankingConstantsConfigSP       _rankingConstants;
    IndexschemaConfigSP            _indexschema;
    AttributesConfigSP             _attributes;
    SummaryConfigSP                _summary;
    SummarymapConfigSP             _summarymap;
    JuniperrcConfigSP              _juniperrc;
    DocumenttypesConfigSP          _documenttypes;
    document::DocumentTypeRepo::SP _repo;
    search::TuneFileDocumentDB::SP _tuneFileDocumentDB;
    search::index::Schema::SP      _schema;
    MaintenanceConfigSP            _maintenance;
    config::ConfigSnapshot         _extraConfigs;
    SP _orig;


    template <typename T>
    bool equals(const T * lhs, const T * rhs) const
    {
        if (lhs == NULL)
            return rhs == NULL;
        return rhs != NULL && *lhs == *rhs;
    }
public:
    DocumentDBConfig(int64_t generation,
                     const RankProfilesConfigSP &rankProfiles,
                     const RankingConstantsConfigSP &rankingConstants,
                     const IndexschemaConfigSP &indexschema,
                     const AttributesConfigSP &attributes,
                     const SummaryConfigSP &summary,
                     const SummarymapConfigSP &summarymap,
                     const JuniperrcConfigSP &juniperrc,
                     const DocumenttypesConfigSP &documenttypesConfig,
                     const document::DocumentTypeRepo::SP &repo,
                     const search::TuneFileDocumentDB::SP & tuneFileDocumentDB,
                     const search::index::Schema::SP &schema,
                     const DocumentDBMaintenanceConfig::SP &maintenance,
                     const vespalib::string &configId,
                     const vespalib::string &docTypeName,
                     const config::ConfigSnapshot & extraConfig = config::ConfigSnapshot());

    DocumentDBConfig(const DocumentDBConfig &cfg);

    const vespalib::string & getConfigId() const { return _configId; }
    void setConfigId(const vespalib::string &configId) { _configId = configId; }

    const vespalib::string &getDocTypeName() const { return _docTypeName; }

    int64_t getGeneration(void) const { return _generation; }

    const vespa::config::search::RankProfilesConfig &
    getRankProfilesConfig() const { return *_rankProfiles; }

    const vespa::config::search::core::RankingConstantsConfig &
    getRankingConstantsConfig() const { return *_rankingConstants; }

    const vespa::config::search::IndexschemaConfig &
    getIndexschemaConfig() const { return *_indexschema; }

    const vespa::config::search::AttributesConfig &
    getAttributesConfig() const { return *_attributes; }

    const vespa::config::search::SummaryConfig &
    getSummaryConfig() const { return *_summary; }

    const vespa::config::search::SummarymapConfig &
    getSummarymapConfig() const { return *_summarymap; }

    const vespa::config::search::summary::JuniperrcConfig &
    getJuniperrcConfig() const { return *_juniperrc; }

    const document::DocumenttypesConfig &
    getDocumenttypesConfig(void) const { return *_documenttypes; }

    const RankProfilesConfigSP &
    getRankProfilesConfigSP(void) const { return _rankProfiles; }

    const RankingConstantsConfigSP &
    getRankingConstantsConfigSP() const { return _rankingConstants; }

    const IndexschemaConfigSP &
    getIndexschemaConfigSP(void) const { return _indexschema; }

    const AttributesConfigSP &
    getAttributesConfigSP(void) const { return _attributes; }

    const SummaryConfigSP &
    getSummaryConfigSP(void) const { return _summary; }

    const SummarymapConfigSP &
    getSummarymapConfigSP(void) const { return _summarymap; }

    const JuniperrcConfigSP &
    getJuniperrcConfigSP(void) const { return _juniperrc; }

    const DocumenttypesConfigSP &
    getDocumenttypesConfigSP(void) const { return _documenttypes; }

    const document::DocumentTypeRepo::SP &
    getDocumentTypeRepoSP() const { return _repo; }

    const document::DocumentType *
    getDocumentType() const { return _repo->getDocumentType(getDocTypeName()); }

    const search::index::Schema::SP &
    getSchemaSP(void) const { return _schema; }

    const MaintenanceConfigSP &
    getMaintenanceConfigSP(void) const { return _maintenance; }

    const search::TuneFileDocumentDB::SP &
    getTuneFileDocumentDBSP(void) const { return _tuneFileDocumentDB; }

    bool
    operator==(const DocumentDBConfig &rhs) const;

     /**
      * Compare this snapshot with the given one.
      */
    ComparisonResult
    compare(const DocumentDBConfig &rhs) const;

    bool valid(void) const;

    const config::ConfigSnapshot & getExtraConfigs() const { return _extraConfigs; }
    void setExtraConfigs(const config::ConfigSnapshot &extraConfigs) { _extraConfigs = extraConfigs; }

    /**
     * Only keep configs needed for replay of transaction log.
     */
    static SP makeReplayConfig(const SP & orig);

    /**
     * Return original config if this is a replay config, otherwise return
     * empty shared pointer.
     */
    SP getOriginalConfig() const;

    /**
     * Return original config if cfg is a replay config, otherwise return
     * cfg.
     */
    static SP preferOriginalConfig(const SP & cfg);

    /**
     * Create modified attributes config.
     */
    SP newFromAttributesConfig(const AttributesConfigSP &attributes) const;
};

} // namespace proton

