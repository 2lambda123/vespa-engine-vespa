// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/messagebus/blob.h>
#include <vespa/messagebus/blobref.h>
#include <vespa/messagebus/message.h>
#include <vespa/messagebus/reply.h>
#include <vespa/slobrok/sbmirror.h>
#include <vespa/slobrok/sbregister.h>
#include <vespa/vespalib/component/versionspecification.h>
#include "inetwork.h"
#include "oosmanager.h"
#include "rpcnetworkparams.h"
#include "rpcsendv1.h"
#include "rpcservicepool.h"
#include "rpctargetpool.h"

namespace mbus {

/**
 * Network implementation based on RPC. This class is responsible for
 * keeping track of services and for sending messages to services.
 **/
class RPCNetwork : public INetwork,
                   public FRT_Invokable {
private:
    struct SendContext : public RPCTarget::IVersionHandler {
        vespalib::Lock            _lock;
        RPCNetwork               &_net;
        const Message            &_msg;
        uint32_t                  _traceLevel;
        std::vector<RoutingNode*> _recipients;
        bool                      _hasError;
        uint32_t                  _pending;
        vespalib::Version         _version;

        SendContext(RPCNetwork &net, const Message &msg, const std::vector<RoutingNode*> &recipients);
        void handleVersion(const vespalib::Version *version);
    };

    struct TargetPoolTask : public FNET_Task {
        RPCTargetPool &_pool;

        TargetPoolTask(FNET_Scheduler &scheduler, RPCTargetPool &pool);
        void PerformTask();
    };

    typedef std::map<vespalib::VersionSpecification, RPCSendAdapter*> SendAdapterMap;

    INetworkOwner            *_owner;
    Identity                  _ident;
    FastOS_ThreadPool         _threadPool;
    FNET_Transport            _transport;
    FRT_Supervisor            _orb;
    FNET_Scheduler           &_scheduler;
    RPCTargetPool             _targetPool;
    TargetPoolTask            _targetPoolTask;
    RPCServicePool            _servicePool;
    slobrok::api::MirrorAPI   _mirror;
    slobrok::api::RegisterAPI _regAPI;
    OOSManager                _oosManager;
    int                       _requestedPort;
    RPCSendV1                 _sendV1;
    SendAdapterMap            _sendAdapters;

    /**
     * Resolves and assigns a service address for the given recipient using the
     * given address. This is called by the {@link
     * #allocServiceAddress(RoutingNode)} method. The target allocated here is
     * released when the routing node calls {@link
     * #freeServiceAddress(RoutingNode)}.
     *
     * @param recipient   The recipient to assign the service address to.
     * @param serviceName The name of the service to resolve.
     * @return Any error encountered, or ErrorCode::NONE.
     */
    Error resolveServiceAddress(RoutingNode &recipient, const string &serviceName);

    /**
     * Determines and returns the send adapter that is compatible with the given
     * version. If no adapter can be found, this method returns null.
     *
     * @param version The version for which to return an adapter.
     * @return The compatible adapter.
     */
    RPCSendAdapter *getSendAdapter(const vespalib::Version &version);

    /**
     * This method is a callback invoked after {@link #send(Message, List)} once
     * the version of all recipients have been resolved. If all versions were
     * resolved ahead of time, this method is invoked by the same thread as the
     * former.  If not, this method is invoked by the network thread during the
     * version callback.
     *
     * @param ctx All the required send-data.
     */
    void send(SendContext &ctx);

protected:
    /**
     * Returns the version of this network. This gets called when the
     * "mbus.getVersion" method is invoked on this network, and is separated
     * into its own function so that unit tests can override it to simulate
     * other versions than current.
     *
     * @return The version to claim to be.
     */
    virtual const vespalib::Version &getVersion() const;

    /**
     * The network uses a cache of RPC targets (see {@link RPCTargetPool}) that
     * allows it to save time by reusing open connections. It works by keeping a
     * set of the most recently used targets open. Calling this method forces
     * all unused connections to close immediately.
     */
    void flushTargetPool();

public:
    /**
     * Create an RPCNetwork. The servicePrefix is combined with session names to
     * create service names. If the service prefix is 'a/b' and the session name
     * is 'c', the resulting service name that identifies the session on the
     * message bus will be 'a/b/c'
     *
     * @param params A complete set of parameters.
     */
    RPCNetwork(const RPCNetworkParams &params);

    /**
     * Destruct
     **/
    virtual ~RPCNetwork();

    /**
     * Obtain the owner of this network. This method may only be invoked after
     * the network has been attached to its owner.
     *
     * @return network owner
     **/
    INetworkOwner &getOwner() { return *_owner; }

    /**
     * Returns the identity of this network.
     *
     * @return The identity.
     */
    const Identity &getIdentity() const { return _ident; }

    /**
     * Obtain the port number this network is listening to. This method will
     * return 0 until the start method has been invoked.
     *
     * @return port number
     **/
    int getPort() const { return _orb.GetListenPort(); }

    /**
     * Allocate a new rpc request object. The caller of this method gets the
     * ownership of the returned request.
     *
     * @return a new rpc request
     **/
    FRT_RPCRequest *allocRequest();

    /**
     * Returns an RPC target for the given service address.
     *
     * @param address The address for which to return a target.
     * @return The target to send to.
     */
    RPCTarget::SP getTarget(const RPCServiceAddress &address);

    /**
     * Obtain a reference to the internal scheduler. This will be mostly used
     * for testing.
     *
     * @return internal scheduler
     **/
    FNET_Scheduler &getScheduler() { return _scheduler; }

    /**
     * Obtain a reference to the internal OOS manager object. This will be
     * mostly used for testing.
     *
     * @return internal OOS manager
     **/
    OOSManager &getOOSManager() { return _oosManager; }

    /**
     * Obtain a reference to the internal supervisor. This is used by
     * the request adapters to register FRT methods.
     *
     * @return The supervisor.
     */
    FRT_Supervisor &getSupervisor() { return _orb; }

    /**
     * Deliver an error reply to the recipients of a {@link SendContext} in a
     * way that avoids entanglement.
     *
     * @param ctx     The send context that contains the recipient data.
     * @param errCode The error code to return.
     * @param errMsg  The error string to return.
     */
    void replyError(const SendContext &ctx, uint32_t errCode,
                    const string &errMsg);

    // Implements INetwork.
    void attach(INetworkOwner &owner);

    // Implements INetwork.
    const string getConnectionSpec() const;

    // Implements INetwork.
    bool start();

    // Implements INetwork.
    bool waitUntilReady(double seconds) const;

    // Implements INetwork.
    void registerSession(const string &session);

    // Implements INetwork.
    void unregisterSession(const string &session);

    // Implements INetwork.
    bool allocServiceAddress(RoutingNode &recipient);

    // Implements INetwork.
    void freeServiceAddress(RoutingNode &recipient);

    // Implements INetwork.
    void send(const Message &msg, const std::vector<RoutingNode*> &recipients);

    // Implements INetwork.
    void sync();

    // Implements INetwork.
    void shutdown();

    // Implements INetwork.
    void postShutdownHook();

    // Implements INetwork.
    const slobrok::api::IMirrorAPI &getMirror() const;

    // Implements FRT_Invokable.
    void invoke(FRT_RPCRequest *req);
};

} // namespace mbus

