// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/fnet/fnet.h>
#include <set>
#include <vespa/slobrok/sbmirror.h>
#include <string>
#include <vespa/vespalib/util/sync.h>
#include "oosclient.h"

namespace mbus {

class RPCNetwork;

/**
 * This class keeps track of OOS information. A set of servers having OOS information are identified by looking up a
 * service pattern in the slobrok. These servers are then polled for information. The information is compiled into a
 * local repository for fast lookup.
 */
class OOSManager : public FNET_Task {
public:
    /**
     * Convenience typedefs.
     */
    typedef slobrok::api::MirrorAPI      MirrorAPI;
    typedef MirrorAPI::SpecList          SpecList;
    typedef std::vector<OOSClient::SP>   ClientList;
    typedef std::set<string>             StringSet;
    typedef std::shared_ptr<StringSet> OOSSet;

private:
    FRT_Supervisor &_orb;
    MirrorAPI      &_mirror;
    bool            _disabled;
    bool            _ready;
    vespalib::Lock  _lock;
    string          _servicePattern;
    uint32_t        _slobrokGen;
    SpecList        _services;
    ClientList      _clients;
    OOSSet          _oosSet;

    /**
     * Reuse or create a client against the given server.
     *
     * @param spec The connection spec of the OOS server we want to talk to.
     * @return A shared oosclient object.
     */
    OOSClient::SP getClient(const string &spec);

    /**
     * Method invoked when this object is run as a task. This method will update the oos information held by
     * this object.
     */
    void PerformTask();

public:
    /**
     * Create a new OOSManager. The given service pattern will be looked up in the given slobrok mirror. The
     * resulting set of services will be polled for oos information.
     *
     * @param orb            The supervisor used for RPC operations.
     * @param mirror         The slobrok mirror.
     * @param servicePattern The service pattern for oos servers.
     */
    OOSManager(FRT_Supervisor &orb,
               slobrok::api::MirrorAPI &mirror,
               const string &servicePattern);

    /**
     * Destructor.
     */
    virtual ~OOSManager();

    /**
     * Returns whether or not some initial state has been returned.
     *
     * @return True, if initial state has been found.
     */
    bool isReady() const { return _ready; }

    /**
     * Returns whether or not the given service has been marked as out of service.
     *
     * @param service The service to check.
     * @return True if the service is out of service.
     */
    bool isOOS(const string &service);
};

} // namespace mbus

