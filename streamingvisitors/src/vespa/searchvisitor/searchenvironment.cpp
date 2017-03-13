// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "searchenvironment.h"
#include <vespa/vespalib/stllike/hash_map.hpp>

#include <vespa/log/log.h>
LOG_SETUP(".visitor.instance.searchenvironment");

using search::docsummary::JuniperProperties;
using vsm::VSMAdapter;

namespace storage {

__thread SearchEnvironment::EnvMap * SearchEnvironment::_localEnvMap=0;

SearchEnvironment::Env::Env(const vespalib::string & muffens, const config::ConfigUri & configUri, Fast_NormalizeWordFolder & wf) :
    _configId(configUri.getConfigId()),
    _configurer(config::SimpleConfigRetriever::UP(
                    new config::SimpleConfigRetriever(createKeySet(configUri.getConfigId()), configUri.getContext())),
                this),
    _vsmAdapter(new VSMAdapter(muffens, _configId, wf)),
    _rankManager(new RankManager(_vsmAdapter.get()))
{
    
    _configurer.start();
}

config::ConfigKeySet
SearchEnvironment::Env::createKeySet(const vespalib::string & configId)
{
    config::ConfigKeySet set;
    set.add<vespa::config::search::vsm::VsmfieldsConfig,
            vespa::config::search::SummaryConfig,
            vespa::config::search::SummarymapConfig,
            vespa::config::search::vsm::VsmsummaryConfig,
            vespa::config::search::summary::JuniperrcConfig,
            vespa::config::search::RankProfilesConfig>(configId);
    return set;
}

void
SearchEnvironment::Env::configure(const config::ConfigSnapshot & snapshot)
{
    vsm::VSMConfigSnapshot snap(_configId, snapshot);
    _vsmAdapter->configure(snap);
    _rankManager->configure(snap);
}

SearchEnvironment::Env::~Env()
{
    _configurer.close();
}

SearchEnvironment::SearchEnvironment(const config::ConfigUri & configUri) :
    VisitorEnvironment(),
    _envMap(),
    _configUri(configUri)
{ }

SearchEnvironment::~SearchEnvironment()
{
    vespalib::LockGuard guard(_lock);
    _threadLocals.clear();
}

SearchEnvironment::Env & SearchEnvironment::getEnv(const vespalib::string & searchCluster)
{
    config::ConfigUri searchClusterUri(_configUri.createWithNewId(searchCluster));
    if (_localEnvMap == NULL) {
        _localEnvMap = new EnvMap;
        vespalib::LockGuard guard(_lock);
        _threadLocals.push_back(EnvMapLP(_localEnvMap));
    }
    EnvMap::iterator localFound = _localEnvMap->find(searchCluster);
    if (localFound == _localEnvMap->end()) {
        vespalib::LockGuard guard(_lock);
        EnvMap::iterator found = _envMap.find(searchCluster);
        if (found == _envMap.end()) {
            LOG(debug, "Init VSMAdapter in map %p for %p with config id = '%s'",
                &_envMap, this, searchCluster.c_str());
            _envMap[searchCluster].reset(new Env("*", searchClusterUri, _wordFolder));
            found = _envMap.find(searchCluster);
            Env *ptr = found->second.get();
            LOG(debug, "_envMap[%s] = %p", searchCluster.c_str(), ptr);
        }
        _localEnvMap->insert(*found);
        localFound = _localEnvMap->find(searchCluster);
    }
    return *localFound->second;
}

}
