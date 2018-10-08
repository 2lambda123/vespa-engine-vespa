// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "managed_rpc_server.h"
#include "i_rpc_server_manager.h"
#include <vespa/fnet/frt/supervisor.h>
#include <vespa/fnet/frt/target.h>

#include <vespa/log/log.h>
LOG_SETUP(".rpcserver");

namespace slobrok {

//-----------------------------------------------------------------------------

ManagedRpcServer::ManagedRpcServer(const std::string & name,
                                   const std::string & spec,
                                   IRpcServerManager &manager)
    : NamedService(name, spec),
      FNET_Task(manager.getSupervisor()->GetScheduler()),
      _mmanager(manager),
      _monitor(*this, *manager.getSupervisor()),
      _monitoredServer(nullptr),
      _checkServerReq(nullptr)
{
}


void
ManagedRpcServer::healthCheck()
{
    if (_monitoredServer == nullptr) {
        _monitoredServer = _mmanager.getSupervisor()->GetTarget(_spec.c_str());
    }
    if (_checkServerReq == nullptr) {
        _checkServerReq = _mmanager.getSupervisor()->AllocRPCRequest();
        _checkServerReq->SetMethodName("slobrok.callback.listNamesServed");
        _monitoredServer->InvokeAsync(_checkServerReq, 25.0, this);
    }
}


ManagedRpcServer::~ManagedRpcServer()
{
    LOG(debug, "(role[%s].~ManagedRpcServer)", _name.c_str());
    cleanupMonitor();
}


void
ManagedRpcServer::cleanupMonitor()
{
    _monitor.disable();
    if (_monitoredServer != nullptr) {
        _monitoredServer->SubRef();
        _monitoredServer = nullptr;
    }
    if (_checkServerReq != nullptr) {
        _checkServerReq->Abort();
        // _checkServerReq cleared by RequestDone Method
        LOG_ASSERT(_checkServerReq == nullptr);
    }
}

void
ManagedRpcServer::notifyDisconnected()
{
    cleanupMonitor();
    _mmanager.notifyFailedRpcSrv(this, "disconnected");
}


bool
ManagedRpcServer::validateRpcServer(uint32_t numstrings,
                                    FRT_StringValue *strings)
{
    for (uint32_t i = 0; i < numstrings; ++i) {
        if (strcmp(strings[i]._str, _name.c_str()) == 0) {
            return true;
        }
    }
    LOG(info, "REMOVE: server at %s did not have %s in listNamesServed values",
        _spec.c_str(), _name.c_str());
    return false;
}


void
ManagedRpcServer::RequestDone(FRT_RPCRequest *req)
{
    LOG_ASSERT(req == _checkServerReq);
    if (req->GetErrorCode() == FRTE_RPC_ABORT) {
        LOG(debug, "rpcserver[%s].check aborted", getName().c_str());
        req->SubRef();
        _checkServerReq = nullptr;
    } else {
        ScheduleNow();
    }
}

void
ManagedRpcServer::PerformTask()
{
    FRT_RPCRequest *req = _checkServerReq;
    FRT_Values &answer = *(req->GetReturn());

    if (req->IsError()
        || strcmp(answer.GetTypeString(), "S") != 0
        || ! validateRpcServer(answer[0]._string_array._len,
                               answer[0]._string_array._pt))
    {
        std::string errmsg;
        if (req->IsError()) {
            errmsg = req->GetErrorMessage();
        } else if (strcmp(answer.GetTypeString(), "S") != 0) {
            errmsg = "checkServer wrong return: ";
            errmsg += answer.GetTypeString();
        } else {
            errmsg = "checkServer failed validation";
        }
        req->SubRef();
        _checkServerReq = nullptr;
        cleanupMonitor();
        _mmanager.notifyFailedRpcSrv(this, errmsg);
        return;
    }

    // start monitoring connection to server
    LOG_ASSERT(_monitoredServer != nullptr);
    _monitor.enable(_monitoredServer);

    req->SubRef();
    _checkServerReq = nullptr;
    _mmanager.notifyOkRpcSrv(this);
}


//-----------------------------------------------------------------------------

} // namespace slobrok
