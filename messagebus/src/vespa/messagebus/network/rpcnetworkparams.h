// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "identity.h"
#include <vespa/slobrok/cfg.h>
#include <vespa/vespalib/util/compressionconfig.h>
#include <vespa/vespalib/util/executor.h>

namespace mbus {

/**
 * To facilitate several configuration parameters to the {@link RPCNetwork} constructor, all parameters are
 * held by this class. This class has reasonable default values for each parameter.
 */
class RPCNetworkParams {
public:
    using OptimizeFor = vespalib::Executor::OptimizeFor;
    using CompressionConfig = vespalib::compression::CompressionConfig;

    RPCNetworkParams();
    RPCNetworkParams(config::ConfigUri configUri);
    ~RPCNetworkParams();

    /**
     * Returns the identity to use for the network.
     *
     * @return The identity.
     */
    const Identity &getIdentity() const {
        return _identity;
    }

    /**
     * Sets the identity to use for the network.
     *
     * @param identity The new identity.
     * @return This, to allow chaining.
     */
    RPCNetworkParams &setIdentity(const Identity &identity) {
        _identity = identity;
        return *this;
    }

    /**
     * Sets the identity to use for the network.
     *
     * @param identity A string representation of the identity.
     * @return This, to allow chaining.
     */
    RPCNetworkParams &setIdentity(const string &identity) {
        return setIdentity(Identity(identity));
    }

    /**
     * Returns the config id of the slobrok config.
     *
     * @return The config id.
     */
    const config::ConfigUri & getSlobrokConfig() const {
        return _slobrokConfig;
    }

    /**
     *
     */

    /**
     * Returns the port to listen to.
     *
     * @return The port.
     */
    int getListenPort() const {
        return _listenPort;
    }

    /**
     * Sets the port to listen to.
     *
     * @param listenPort The new port.
     * @return This, to allow chaining.
     */
    RPCNetworkParams &setListenPort(int listenPort) {
        _listenPort = listenPort;
        return *this;
    }

    /**
     * Sets number of threads for the thread pool.
     *
     * @param numThreads number of threads for thread pool
     * @return This, to allow chaining.
     */
    RPCNetworkParams &setNumThreads(uint32_t numThreads) {
        _numThreads = numThreads;
        return *this;
    }

    uint32_t getNumThreads() const { return _numThreads; }

    /**
     * Sets number of threads for the network.
     *
     * @param numNetworkThreads number of threads for the network
     * @return This, to allow chaining.
     */
    RPCNetworkParams &setNumNetworkThreads(uint32_t numNetworkThreads) {
        _numNetworkThreads = numNetworkThreads;
        return *this;
    }

    uint32_t getNumNetworkThreads() const { return _numNetworkThreads; }

    RPCNetworkParams &setOptimizeFor(OptimizeFor tcpNoDelay) {
        _optimizeFor = tcpNoDelay;
        return *this;
    }

    OptimizeFor getOptimizeFor() const { return _optimizeFor; }

    /**
     * Returns the number of seconds before an idle network connection expires.
     *
     * @return The number of seconds.
     */
    double getConnectionExpireSecs() const{
        return _connectionExpireSecs;
    }

    /**
     * Sets the number of seconds before an idle network connection expires.
     *
     * @param secs The number of seconds.
     * @return This, to allow chaining.
     */
    RPCNetworkParams &setConnectionExpireSecs(double secs) {
        _connectionExpireSecs = secs;
        return *this;
    }

    /**
     * Returns the maximum input buffer size allowed for the underlying FNET connection.
     *
     * @return The maximum number of bytes.
     */
    uint32_t getMaxInputBufferSize() const {
        return _maxInputBufferSize;
    }

    /**
     * Sets the maximum input buffer size allowed for the underlying FNET connection. Using the value 0 means that there
     * is no limit; the connection will not free any allocated memory until it is cleaned up. This might potentially
     * save alot of allocation time.
     *
     * @param maxInputBufferSize The maximum number of bytes.
     * @return This, to allow chaining.
     */
    RPCNetworkParams &setMaxInputBufferSize(uint32_t maxInputBufferSize) {
        _maxInputBufferSize = maxInputBufferSize;
        return *this;
    }

    /**
     * Returns the maximum output buffer size allowed for the underlying FNET connection.
     *
     * @return The maximum number of bytes.
     */
    uint32_t getMaxOutputBufferSize() const {
        return _maxOutputBufferSize;
    }

    /**
     * Sets the maximum output buffer size allowed for the underlying FNET connection. Using the value 0 means that there
     * is no limit; the connection will not free any allocated memory until it is cleaned up. This might potentially
     * save alot of allocation time.
     *
     * @param maxOutputBufferSize The maximum number of bytes.
     * @return This, to allow chaining.
     */
    RPCNetworkParams &setMaxOutputBufferSize(uint32_t maxOutputBufferSize) {
        _maxOutputBufferSize = maxOutputBufferSize;
        return *this;
    }

    RPCNetworkParams &setCompressionConfig(CompressionConfig compressionConfig) {
        _compressionConfig = compressionConfig;
        return *this;
    }
    CompressionConfig getCompressionConfig() const { return _compressionConfig; }


    RPCNetworkParams &setDispatchOnDecode(bool dispatchOnDecode) {
        _dispatchOnDecode = dispatchOnDecode;
        return *this;
    }

    uint32_t getDispatchOnDecode() const { return _dispatchOnDecode; }

    RPCNetworkParams &setDispatchOnEncode(bool dispatchOnEncode) {
        _dispatchOnEncode = dispatchOnEncode;
        return *this;
    }

    uint32_t getDispatchOnEncode() const { return _dispatchOnEncode; }
private:
    Identity          _identity;
    config::ConfigUri _slobrokConfig;
    int               _listenPort;
    uint32_t          _maxInputBufferSize;
    uint32_t          _maxOutputBufferSize;
    uint32_t          _numThreads;
    uint32_t          _numNetworkThreads;
    OptimizeFor       _optimizeFor;
    bool              _dispatchOnEncode;
    bool              _dispatchOnDecode;
    double            _connectionExpireSecs;
    CompressionConfig _compressionConfig;
};

}

