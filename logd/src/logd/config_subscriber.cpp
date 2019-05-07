// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "config_subscriber.h"
#include "conn.h"
#include "legacy_forwarder.h"
#include "rpc_forwarder.h"
#include <fcntl.h>
#include <unistd.h>

#include <vespa/log/log.h>
LOG_SETUP("");

using cloud::config::log::LogdConfig;
using ns_log::Logger;

namespace logdemon {

void
ConfigSubscriber::configure(std::unique_ptr<LogdConfig> cfg)
{
    const LogdConfig &newconf(*cfg);
    if (newconf.logserver.host != _logserver_host) {
        _logserver_host = newconf.logserver.host;
        _need_new_forwarder = true;
    }
    if (newconf.logserver.use != _use_logserver) {
        _use_logserver = newconf.logserver.use;
        _need_new_forwarder = true;
    }
    _state_port = newconf.stateport;

    ForwardMap forwardMap;
    forwardMap[Logger::fatal] = newconf.loglevel.fatal.forward;
    forwardMap[Logger::error] = newconf.loglevel.error.forward;
    forwardMap[Logger::warning] = newconf.loglevel.warning.forward;
    forwardMap[Logger::config] = newconf.loglevel.config.forward;
    forwardMap[Logger::info] = newconf.loglevel.info.forward;
    forwardMap[Logger::event] = newconf.loglevel.event.forward;
    forwardMap[Logger::debug] = newconf.loglevel.debug.forward;
    forwardMap[Logger::spam] = newconf.loglevel.spam.forward;
    if (forwardMap != _forward_filter) {
        _forward_filter = forwardMap;
        _need_new_forwarder = true;
    }

    if (newconf.logserver.port != _logserver_port) {
        _logserver_port = newconf.logserver.port;
        _need_new_forwarder = true;
    }
    if (newconf.logserver.rpcport != _logserver_rpc_port) {
        _logserver_rpc_port = newconf.logserver.rpcport;
        _need_new_forwarder = true;
    }
    if (newconf.logserver.userpc != _logserver_use_rpc) {
        _logserver_use_rpc = newconf.logserver.userpc;
        _need_new_forwarder = true;
    }
    if (newconf.rotate.size > 0) {
        _rotate_size = newconf.rotate.size;
    } else {
        LOG(config, "bad rotate.size=%d must be positive", newconf.rotate.size);
    }
    if (newconf.rotate.age > 0) {
        _rotate_age = newconf.rotate.age;
    } else {
        LOG(config, "bad rotate.age=%d must be positive", newconf.rotate.age);
    }
    if (newconf.remove.totalmegabytes > 0) {
        _remove_meg = newconf.remove.totalmegabytes;
    } else {
        LOG(config, "bad remove.totalmegabytes=%d must be positive", newconf.remove.totalmegabytes);
    }
    if (newconf.remove.age > 0) {
        _remove_age = newconf.remove.age;
    } else {
        LOG(config, "bad remove.age=%d must be positive", newconf.remove.age);
    }
}

bool
ConfigSubscriber::checkAvailable()
{
    if (_subscriber.nextGeneration(0)) {
        _has_available = true;
    }
    return _has_available;
}

void
ConfigSubscriber::latch()
{
    if (checkAvailable()) {
        configure(_handle->getConfig());
        _has_available = false;
    }
}

ConfigSubscriber::ConfigSubscriber(const config::ConfigUri& configUri)
    : _logserver_host(),
      _logserver_port(0),
      _logserver_rpc_port(0),
      _logserver_use_rpc(false),
      _state_port(0),
      _forward_filter(),
      _rotate_size(INT_MAX),
      _rotate_age(INT_MAX),
      _remove_meg(INT_MAX),
      _remove_age(3650),
      _use_logserver(true),
      _subscriber(configUri.getContext()),
      _handle(),
      _has_available(false),
      _need_new_forwarder(true),
      _supervisor()
{
    _handle = _subscriber.subscribe<LogdConfig>(configUri.getConfigId());
    _subscriber.nextConfig(0);
    configure(_handle->getConfig());
    _supervisor.Start();

    LOG(debug, "got logServer %s", _logserver_host.c_str());
    LOG(debug, "got handle %p", _handle.get());
}

ConfigSubscriber::~ConfigSubscriber()
{
    _supervisor.ShutDown(true);
    LOG(debug, "forget logServer %s", _logserver_host.c_str());
    LOG(debug, "done ~ConfSub()");
}

std::unique_ptr<Forwarder>
ConfigSubscriber::make_forwarder(Metrics& metrics)
{
    std::unique_ptr<Forwarder> result;
    if (_use_logserver) {
        if (_logserver_use_rpc) {
            result = std::make_unique<RpcForwarder>(metrics, _forward_filter, _supervisor, _logserver_host,
                                                    _logserver_rpc_port, 60.0, 100);
        } else {
            result = LegacyForwarder::to_logserver(metrics, _forward_filter, _logserver_host, _logserver_port);
        }
    } else {
        result = LegacyForwarder::to_dev_null(metrics);
    }
    _need_new_forwarder = false;
    return result;
}

}
