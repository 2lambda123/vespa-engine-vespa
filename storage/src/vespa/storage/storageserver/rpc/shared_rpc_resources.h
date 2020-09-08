// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/config/subscription/configuri.h>
#include <vespa/vespalib/stllike/string.h>
#include <memory>

class FastOS_ThreadPool;
class FNET_Transport;
class FRT_Supervisor;

namespace slobrok::api {
class RegisterAPI;
class MirrorAPI;
}

namespace storage::rpc {

class SharedRpcResources {
    std::unique_ptr<FastOS_ThreadPool>         _thread_pool;
    std::unique_ptr<FNET_Transport>            _transport;
    std::unique_ptr<FRT_Supervisor>            _orb;
    std::unique_ptr<slobrok::api::RegisterAPI> _slobrok_register;
    std::unique_ptr<slobrok::api::MirrorAPI>   _slobrok_mirror;
    vespalib::string                           _handle;
    int                                        _rpc_server_port;
    bool                                       _shutdown;
public:
    SharedRpcResources(const config::ConfigUri& config_uri, int rpc_server_port, size_t rpc_thread_pool_size);
    ~SharedRpcResources();

    FRT_Supervisor& supervisor() noexcept { return *_orb; }
    const FRT_Supervisor& supervisor() const noexcept { return *_orb; }

    slobrok::api::RegisterAPI& slobrok_register() noexcept { return *_slobrok_register; }
    const slobrok::api::RegisterAPI& slobrok_register() const noexcept { return *_slobrok_register; }
    slobrok::api::MirrorAPI& slobrok_mirror() noexcept { return *_slobrok_mirror; }
    const slobrok::api::MirrorAPI& slobrok_mirror() const noexcept { return *_slobrok_mirror; }
    // To be called after all RPC handlers have been registered.
    void start_server_and_register_slobrok(vespalib::stringref my_handle);

    void shutdown();
    [[nodiscard]] int listen_port() const noexcept; // Only valid if server has been started
private:
    void wait_until_slobrok_is_ready();
};


}
