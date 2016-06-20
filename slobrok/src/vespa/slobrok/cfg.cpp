// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include "cfg.h"
#include <vespa/log/log.h>
#include <vespa/vespalib/util/sync.h>

LOG_SETUP(".slobrok.configurator");

namespace slobrok {

namespace {

std::vector<std::string>
extract(const cloud::config::SlobroksConfig &cfg)
{
    std::vector<std::string> r;
    for (size_t i = 0; i < cfg.slobrok.size(); ++i) {
        std::string spec(cfg.slobrok[i].connectionspec);
        r.push_back(spec);
    }
    return r;
}

} // namespace <unnamed>

bool
Configurator::poll()
{
    bool retval = _subscriber.nextGeneration(0);
    if (retval) {
        std::unique_ptr<cloud::config::SlobroksConfig> cfg = _handle->getConfig();
        _target.setup(extract(*cfg));
    }
    return retval;
}


Configurator::Configurator(Configurable& target, const config::ConfigUri & uri)
    : _subscriber(uri.getContext()),
      _handle(_subscriber.subscribe<cloud::config::SlobroksConfig>(uri.getConfigId())),
      _target(target)

{
}

Configurator::Configurator(Configurable &target, const config::ConfigUri & uri, std::chrono::milliseconds timeout)
    : _subscriber(uri.getContext()),
      _handle(_subscriber.subscribe<cloud::config::SlobroksConfig>(uri.getConfigId(), timeout.count())),
      _target(target)
{
}

ConfiguratorFactory::ConfiguratorFactory(const config::ConfigUri& uri)
    : _uri(uri)
{
}

ConfiguratorFactory::ConfiguratorFactory(const std::vector<std::string> & spec)
    : _uri(config::ConfigUri::createEmpty())
{
    cloud::config::SlobroksConfigBuilder builder;
    for (size_t i = 0; i < spec.size(); i++) {
        cloud::config::SlobroksConfig::Slobrok sb;
        sb.connectionspec = spec[i];
        builder.slobrok.push_back(sb);
    }
    _uri = config::ConfigUri::createFromInstance(builder);
}

Configurator::UP
ConfiguratorFactory::create(Configurable& target) const
{
    if (_timeout != std::chrono::milliseconds()) {
        return std::make_unique<Configurator>(target, _uri, _timeout);
    } else {
        return std::make_unique<Configurator>(target, _uri);
    }
}

} // namespace slobrok
