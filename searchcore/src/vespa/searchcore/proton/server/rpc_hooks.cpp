// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".proton.server.rtchooks");

#include "rpc_hooks.h"
#include "proton.h"
#include <vespa/messagebus/emptyreply.h>
#include <vespa/vespalib/util/stringfmt.h>
#include <vespa/vespalib/util/closuretask.h>
#include <vespa/searchcore/proton/common/statusreport.h>

using namespace vespalib;
using document::CompressionConfig;

namespace {

struct Pair {
    string key;
    string value;
    Pair(const string &k, const string &v)
        : key(k),
          value(v)
    {
    }
    ~Pair();
};

Pair::~Pair() {}

}

namespace proton {

void
RPCHooksBase::checkState(StateArg::UP arg)
{
    fastos::TimeStamp now(fastos::ClockSystem::now());
    if (now < arg->_dueTime) {
        MonitorGuard guard(_stateMonitor);
        if ( guard.wait(std::min(1000L, (arg->_dueTime - now)/fastos::TimeStamp::MS)) ) {
            LOG(debug, "state has changed");
            reportState(*arg->_session, arg->_req);
            arg->_req->Return();
        } else {
            LOG(debug, "Will reschedule");
            FRT_RPCRequest * req = arg->_req;
            Session & session = *arg->_session;
            Executor::Task::UP failedTask = _executor.execute(makeTask(
                    makeClosure(this, &RPCHooksBase::checkState, std::move(arg))));
            if (failedTask.get() != NULL) {
                reportState(session, req);
                req->Return();
            }
        }
    } else {
        reportState(*arg->_session, arg->_req);
        arg->_req->Return();
    }
}

void
RPCHooksBase::reportState(Session & session, FRT_RPCRequest * req)
{
    std::vector<Pair> res;
    int64_t numDocs(_proton.getNumDocs());
    std::string badConfigs = _proton.getBadConfigs();
    bool numDocsChanged = session.getNumDocs() != numDocs;
    bool badConfigsChanged = session.getBadConfigs() != badConfigs;
    bool changed = numDocsChanged || badConfigsChanged;

    if (_proton.getMatchEngine().isOnline()) {
        res.push_back(Pair("online", "true"));
        res.push_back(Pair("onlineState", "online"));
    } else {
        res.push_back(Pair("online", "false"));
        res.push_back(Pair("onlineState", "onlineSoon"));
    }
    if (session.getGen() < 0) {
        if (badConfigsChanged)
            res.push_back(Pair("badConfigs", badConfigs));
        res.push_back(Pair("onlineDocs", make_string("%lu", numDocs)));
        session.setGen(0);
    } else if (changed) {
        if (badConfigsChanged)
            res.push_back(Pair("badConfigs", badConfigs));
        res.push_back(Pair("onlineDocs", make_string("%lu", numDocs)));
        session.setGen(session.getGen() + 1);
    }
    if (numDocsChanged)
        session.setNumDocs(numDocs);
    if (badConfigsChanged)
        session.setBadConfigs(badConfigs);

    FRT_Values &ret = *req->GetReturn();
    FRT_StringValue *k = ret.AddStringArray(res.size());
    FRT_StringValue *v = ret.AddStringArray(res.size());
    for (uint32_t i = 0; i < res.size(); ++i) {
        ret.SetString(&k[i], res[i].key.c_str());
        ret.SetString(&v[i], res[i].value.c_str());
    }
    LOG(debug, "gen=%ld", session.getGen());
    for (uint32_t i = 0; i < res.size(); ++i) {
        LOG(debug,
            "key=%s, value=%s",
            res[i].key.c_str(), res[i].value.c_str());
    }
    ret.AddInt32(session.getGen());
}

RPCHooksBase::Session::Session()
    : _createTime(fastos::ClockSystem::now()),
      _numDocs(0u),
      _badConfigs(),
      _gen(-1),
      _down(false)
{
}

void
RPCHooksBase::initRPC()
{
    _orb.SetSessionInitHook(FRT_METHOD(RPCHooksBase::initSession), this);
    _orb.SetSessionFiniHook(FRT_METHOD(RPCHooksBase::finiSession), this);
    _orb.SetSessionDownHook(FRT_METHOD(RPCHooksBase::downSession), this);
    _orb.SetMethodMismatchHook(FRT_METHOD(RPCHooksBase::mismatch), this);

    FRT_ReflectionBuilder rb(&_orb);
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.enableSearching", "", "", true,
                    FRT_METHOD(RPCHooksBase::rpc_enableSearching), this);
    rb.MethodDesc("Tell the node to enable searching");
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.disableSearching", "", "", true,
                    FRT_METHOD(RPCHooksBase::rpc_disableSearching), this);
    rb.MethodDesc("Tell the node to disable searching");
    //-------------------------------------------------------------------------
    rb.DefineMethod("pandora.rtc.getState", "ii", "SSi", true,
                    FRT_METHOD(RPCHooksBase::rpc_GetState), this);
    rb.MethodDesc("Get the current state of node");
    rb.ParamDesc("gencnt", "old state generation held by the client");
    rb.ParamDesc("timeout", "How many milliseconds to wait for state update");
    rb.ReturnDesc("keys", "Array of state keys");
    rb.ReturnDesc("values", "Array of state values");
    rb.ReturnDesc("newgen", "New state generation count");
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.getStatus", "s", "SSSS", true,
                    FRT_METHOD(RPCHooksBase::rpc_GetProtonStatus), this);
    rb.MethodDesc("Get the current state of proton or a proton component");
    rb.ParamDesc("component", "Which component to check the status for");
    rb.ReturnDesc("components", "Array of component names");
    rb.ReturnDesc("states", "Array of states ");
    rb.ReturnDesc("internalStates", "Array of internal states ");
    rb.ReturnDesc("message", "Array of status messages");
    //-------------------------------------------------------------------------
    rb.DefineMethod("pandora.rtc.getIncrementalState", "i", "SSi", true,
                    FRT_METHOD(RPCHooksBase::rpc_getIncrementalState), this);
    rb.MethodDesc("Get node state changes since last invocation");
    rb.ParamDesc("timeout", "How many milliseconds to wait for state update");
    rb.ReturnDesc("keys", "Array of state keys");
    rb.ReturnDesc("values", "Array of state values");
    rb.ReturnDesc("dummy", "Dummy value to enable code reuse");
    //-------------------------------------------------------------------------
    rb.DefineMethod("pandora.rtc.shutdown", "", "", true,
                    FRT_METHOD(RPCHooksBase::rpc_Shutdown), this);
    rb.MethodDesc("Shut down the rtc application");
    //-------------------------------------------------------------------------
    rb.DefineMethod("pandora.rtc.die", "", "", true,
                    FRT_METHOD(RPCHooksBase::rpc_die), this);
    rb.MethodDesc("Exit the rtc application without cleanup");
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.triggerFlush", "", "b", true,
                    FRT_METHOD(RPCHooksBase::rpc_triggerFlush), this);
    rb.MethodDesc("Tell the node to trigger flush ASAP");
    rb.ReturnDesc("success", "Whether or not a flush was triggered.");
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.prepareRestart", "", "b", true,
                    FRT_METHOD(RPCHooksBase::rpc_prepareRestart), this);
    rb.MethodDesc("Tell the node to prepare for a restart by flushing components "
            "such that TLS replay time + time spent flushing components is as low as possible");
    rb.ReturnDesc("success", "Whether or not prepare for restart was triggered.");
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.wipeHistory", "", "", true,
                    FRT_METHOD(RPCHooksBase::rpc_wipeHistory), this);
    rb.MethodDesc("Tell the node to wipe history");
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.listDocTypes", "", "S", true,
                    FRT_METHOD(RPCHooksBase::rpc_listDocTypes), this);
    rb.MethodDesc("Get the current list of document types");
    rb.ReturnDesc("documentTypes", "Current list of document types");
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.listSchema", "s", "SSSS", true,
                    FRT_METHOD(RPCHooksBase::rpc_listSchema), this);
    rb.MethodDesc("Get the current schema for given document type");
    rb.ParamDesc("documentType", "Document type name");
    rb.ReturnDesc("fieldNames", "Schema field names");
    rb.ReturnDesc("fieldDataTypes", "Schema field data types");
    rb.ReturnDesc("fieldCollTypes", "Schema field collection types");
    rb.ReturnDesc("fieldLocation", "Schema field locations");
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.getConfigTime", "", "l", true,
                    FRT_METHOD(RPCHooksBase::rpc_getConfigGeneration), this);
    rb.MethodDesc("Get the oldest active config generation");
    rb.ReturnDesc("configTime", "Oldest active config generation");
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.getConfigGeneration", "", "l", true,
                    FRT_METHOD(RPCHooksBase::rpc_getConfigGeneration), this);
    rb.MethodDesc("Get the oldest active config generation");
    rb.ReturnDesc("configTime", "Oldest active config generation");
    //-------------------------------------------------------------------------
    rb.DefineMethod("proton.getDocsums", "bix", "bix", true, FRT_METHOD(RPCHooksBase::rpc_getDocSums), this);
    rb.MethodDesc("Get list of document summaries");
    rb.ParamDesc("encoding", "0=raw, 6=lz4");
    rb.ParamDesc("uncompressedBlobSize", "Uncompressed blob size");
    rb.ParamDesc("getDocsumX", "The request blob in slime");
    rb.ReturnDesc("encoding",  "0=raw, 6=lz4");
    rb.ReturnDesc("uncompressedBlobSize", "Uncompressed blob size");
    rb.ReturnDesc("docsums", "Blob with slime encoded summaries.");
    
}

RPCHooksBase::RPCHooksBase(Params &params)
    : _proton(params.proton),
      _docsumByRPC(new DocsumByRPC(_proton.getDocsumBySlime())),
      _orb(),
      _regAPI(_orb, params.slobrok_config),
      _executor(48, 128 * 1024),
      _ooscli(params, _orb)
{
}

void
RPCHooksBase::open(Params & params)
{
    initRPC();
    _regAPI.registerName((params.identity + "/realtimecontroller").c_str());
    _orb.Listen(params.rtcPort);
    _orb.Start();
    LOG(debug, "started monitoring interface");
}

RPCHooksBase::~RPCHooksBase()
{
}

void
RPCHooksBase::close()
{
    LOG(info, "shutting down monitoring interface");
    _orb.ShutDown(true);
    _executor.shutdown();
    {
        MonitorGuard guard(_stateMonitor);
        guard.broadcast();
    }
    _executor.sync();
}

void
RPCHooksBase::letProtonDo(Closure::UP closure)
{
    Executor::Task::UP task = makeTask(std::move(closure));
    _proton.getExecutor().execute(std::move(task));
}

void
RPCHooksBase::triggerFlush(FRT_RPCRequest *req)
{
    if (_proton.triggerFlush()) {
        req->GetReturn()->AddInt8(1);
        LOG(info, "RPCHooksBase::Flush finished successfully");
    } else {
        req->GetReturn()->AddInt8(0);
        LOG(warning, "RPCHooksBase::Flush failed");
    }
    req->Return();
}

void
RPCHooksBase::prepareRestart(FRT_RPCRequest *req)
{
    if (_proton.prepareRestart()) {
        req->GetReturn()->AddInt8(1);
        LOG(info, "RPCHooksBase::prepareRestart finished successfully");
    } else {
        req->GetReturn()->AddInt8(0);
        LOG(warning, "RPCHooksBase::prepareRestart failed");
    }
    req->Return();
}

void
RPCHooksBase::enableSearching(FRT_RPCRequest *req)
{
    _proton.getMatchEngine().setOnline();
    LOG(info, "Searching enabled");
    req->Return();
}

void
RPCHooksBase::rpc_enableSearching(FRT_RPCRequest *req)
{
    LOG(info, "RPCHooksBase::rpc_enableSearching");
    req->Detach();
    letProtonDo(makeClosure(this, &RPCHooksBase::enableSearching, req));
}

void
RPCHooksBase::disableSearching(FRT_RPCRequest *req)
{
    _proton.getMatchEngine().setOffline();
    LOG(info, "Search disabled");
    req->Return();
}

void
RPCHooksBase::rpc_disableSearching(FRT_RPCRequest *req)
{
    LOG(info, "RPCHooksBase::rpc_disableSearching");
    req->Detach();
    letProtonDo(makeClosure(this, &RPCHooksBase::disableSearching, req));
}

void
RPCHooksBase::rpc_GetState(FRT_RPCRequest *req)
{
    FRT_Values &arg = *req->GetParams();
    uint32_t gen = arg[0]._intval32;
    uint32_t timeoutMS = arg[1]._intval32;
    const Session::SP & sharedSession = getSession(req);
    LOG(debug, "RPCHooksBase::rpc_GetState(gen=%d, timeoutMS=%d) , but using gen=%ld instead", gen, timeoutMS, sharedSession->getGen());

    int64_t numDocs(_proton.getNumDocs());
    if (sharedSession->getGen() < 0 || sharedSession->getNumDocs() != numDocs) {  // NB Should use something else to define generation.
        reportState(*sharedSession, req);
    } else {
        fastos::TimeStamp dueTime(fastos::ClockSystem::now() + timeoutMS * fastos::TimeStamp::MS);
        StateArg::UP stateArg(new StateArg(sharedSession, req, dueTime));
        if (_executor.execute(makeTask(makeClosure(this, &RPCHooksBase::checkState, std::move(stateArg)))).get() != NULL) {
            reportState(*sharedSession, req);
            req->Return();
        } else {
            req->Detach();
        }
    }
}

void
RPCHooksBase::rpc_GetProtonStatus(FRT_RPCRequest *req)
{
    LOG(debug, "RPCHooksBase::rpc_GetProtonStatus started");
    req->Detach();
    _executor.execute(makeTask(makeClosure(this, &RPCHooksBase::getProtonStatus, req)));
}

void
RPCHooksBase::getProtonStatus(FRT_RPCRequest *req)
{
    StatusReport::List reports(_proton.getStatusReports());
    FRT_Values &ret = *req->GetReturn();
    FRT_StringValue *r = ret.AddStringArray(reports.size());
    FRT_StringValue *k = ret.AddStringArray(reports.size());
    FRT_StringValue *internalStates = ret.AddStringArray(reports.size());
    FRT_StringValue *v = ret.AddStringArray(reports.size());
    for (uint32_t i = 0; i < reports.size(); ++i) {
        ret.SetString(&r[i], reports[i]->getComponent().c_str());
        switch (reports[i]->getState()) {
        case StatusReport::UPOK:
            ret.SetString(&k[i], "OK");
            break;
        case StatusReport::PARTIAL:
            ret.SetString(&k[i], "WARNING");
            break;
        case StatusReport::DOWN:
            ret.SetString(&k[i], "CRITICAL");
            break;
        default:
            ret.SetString(&k[i], "UNKNOWN");
            break;
        }
        ret.SetString(&internalStates[i],
                      reports[i]->getInternalStatesStr().c_str());
        ret.SetString(&v[i], reports[i]->getMessage().c_str());
        LOG(debug,
            "component(%s), status(%s), internalState(%s), message(%s)",
            reports[i]->getComponent().c_str(),
            k[i]._str,
            internalStates[i]._str,
            reports[i]->getMessage().c_str());
    }
    req->Return();
}

void
RPCHooksBase::rpc_getIncrementalState(FRT_RPCRequest *req)
{
    FRT_Values &arg = *req->GetParams();
    uint32_t timeoutMS = arg[0]._intval32;
    const Session::SP & sharedSession = getSession(req);
    LOG(debug, "RPCHooksBase::rpc_getIncrementalState(timeoutMS=%d)", timeoutMS);

    int64_t numDocs(_proton.getNumDocs());
    if (sharedSession->getGen() < 0 || sharedSession->getNumDocs() != numDocs) {  // NB Should use something else to define generation.
        reportState(*sharedSession, req);
    } else {
        fastos::TimeStamp dueTime(fastos::ClockSystem::now() + timeoutMS * fastos::TimeStamp::MS);
        StateArg::UP stateArg(new StateArg(sharedSession, req, dueTime));
        if (_executor.execute(makeTask(makeClosure(this, &RPCHooksBase::checkState, std::move(stateArg)))).get() != NULL) {
            reportState(*sharedSession, req);
            req->Return();
        } else {
            req->Detach();
        }
    }
}

void
RPCHooksBase::rpc_Shutdown(FRT_RPCRequest *req)
{
    (void) req;
    LOG(debug, "RPCHooksBase::rpc_Shutdown");
}

void
RPCHooksBase::rpc_die(FRT_RPCRequest *req)
{
    (void) req;
    LOG(debug, "RPCHooksBase::rpc_die");
    _exit(0);
}

void
RPCHooksBase::rpc_triggerFlush(FRT_RPCRequest *req)
{
    LOG(info, "RPCHooksBase::rpc_triggerFlush started");
    req->Detach();
    letProtonDo(makeClosure(this, &RPCHooksBase::triggerFlush, req));
}

void
RPCHooksBase::rpc_prepareRestart(FRT_RPCRequest *req)
{
    LOG(info, "RPCHooksBase::rpc_prepareRestart started");
    req->Detach();
    letProtonDo(makeClosure(this, &RPCHooksBase::prepareRestart, req));
}

void
RPCHooksBase::wipeHistory(FRT_RPCRequest *req)
{
    _proton.wipeHistory();
    LOG(info, "RPCHooksBase::wipeHistory finished successfully");
    req->Return();
}

void
RPCHooksBase::rpc_wipeHistory(FRT_RPCRequest *req)
{
    LOG(info, "RPCHooksBase::rpc_wipeHistory started");
    req->Detach();
    letProtonDo(makeClosure(this, &RPCHooksBase::wipeHistory, req));
}

void
RPCHooksBase::listDocTypes(FRT_RPCRequest *req)
{
    std::vector<string> documentTypes;

    _proton.listDocTypes(documentTypes);

    FRT_Values &ret = *req->GetReturn();
    FRT_StringValue *dt = ret.AddStringArray(documentTypes.size());
    for (uint32_t i = 0; i < documentTypes.size(); ++i)
        ret.SetString(&dt[i], documentTypes[i].c_str());

    LOG(info,
        "RPCHooksBase::listDocTypes finished successfully");
    req->Return();
}


void
RPCHooksBase::rpc_listDocTypes(FRT_RPCRequest *req)
{
    LOG(info,
        "RPCHooksBase::rpc_listDocTypes started");
    req->Detach();
    letProtonDo(makeClosure(this, &RPCHooksBase::listDocTypes, req));
}


void
RPCHooksBase::listSchema(FRT_RPCRequest *req)
{
    std::vector<string> fieldNames;
    std::vector<string> fieldDataTypes;
    std::vector<string> fieldCollectionTypes;
    std::vector<string> fieldLocations;

    FRT_Values &arg = *req->GetParams();
    string documentType(arg[0]._string._str, arg[0]._string._len);

    _proton.listSchema(documentType,
                       fieldNames, fieldDataTypes, fieldCollectionTypes,
                       fieldLocations);

    FRT_Values &ret = *req->GetReturn();
    FRT_StringValue *fn = ret.AddStringArray(fieldNames.size());
    for (uint32_t i = 0; i < fieldNames.size(); ++i)
        ret.SetString(&fn[i], fieldNames[i].c_str());

    FRT_StringValue *fdt = ret.AddStringArray(fieldDataTypes.size());
    for (uint32_t i = 0; i < fieldDataTypes.size(); ++i)
        ret.SetString(&fdt[i], fieldDataTypes[i].c_str());

    FRT_StringValue *fct = ret.AddStringArray(fieldCollectionTypes.size());
    for (uint32_t i = 0; i < fieldCollectionTypes.size(); ++i)
        ret.SetString(&fct[i], fieldCollectionTypes[i].c_str());

    FRT_StringValue *fl = ret.AddStringArray(fieldLocations.size());
    for (uint32_t i = 0; i < fieldLocations.size(); ++i)
        ret.SetString(&fl[i], fieldLocations[i].c_str());

    LOG(info,
        "RPCHooksBase::listSchema finished successfully");
    req->Return();
}


void
RPCHooksBase::rpc_listSchema(FRT_RPCRequest *req)
{
    LOG(info,
        "RPCHooksBase::rpc_listSchema started");
    req->Detach();
    letProtonDo(makeClosure(this, &RPCHooksBase::listSchema, req));
}


void
RPCHooksBase::getConfigGeneration(FRT_RPCRequest *req)
{
    int64_t configGeneration = _proton.getConfigGeneration();
    FRT_Values &ret = *req->GetReturn();
    ret.AddInt64(configGeneration);

    LOG(info,
        "RPCHooksBase::getConfigGeneration finished successfully, "
        "configGeneration=%" PRId64,
        configGeneration);
    req->Return();
}

void
RPCHooksBase::rpc_getDocSums(FRT_RPCRequest *req)
{
    LOG(debug, "proton.getDocsums()");
    req->Detach();
    _executor.execute(makeTask(makeClosure(this, &RPCHooksBase::getDocsums, req)));
}

void
RPCHooksBase::getDocsums(FRT_RPCRequest *req)
{
    _docsumByRPC->getDocsums(*req);
    req->Return();
}

void
RPCHooksBase::rpc_getConfigGeneration(FRT_RPCRequest *req)
{
    LOG(info,
        "RPCHooksBase::rpc_getConfigGeneration started");
    req->Detach();
    letProtonDo(makeClosure(this, &RPCHooksBase::getConfigGeneration, req));
}


const RPCHooksBase::Session::SP &
RPCHooksBase::getSession(FRT_RPCRequest *req)
{
    FNET_Connection *conn = req->GetConnection();
    void *vctx = conn->GetContext()._value.VOIDP;
    Session::SP *sessionspp = static_cast<Session::SP *>(vctx);
    return *sessionspp;
}


void
RPCHooksBase::initSession(FRT_RPCRequest *req)
{
    FNET_Connection *conn = req->GetConnection();
    const void * voidp(conn->GetContext()._value.VOIDP);
    req->GetConnection()->SetContext(new Session::SP(new Session()));
    void *vctx = conn->GetContext()._value.VOIDP;
    LOG(debug,
        "RPCHooksBase::iniSession req=%p connection=%p session=%p oldSession=%p",
        req,
        conn,
        vctx,
        voidp);
}

void
RPCHooksBase::finiSession(FRT_RPCRequest *req)
{
    FNET_Connection *conn = req->GetConnection();
    void *vctx = conn->GetContext()._value.VOIDP;
    conn->GetContextPT()->_value.VOIDP = NULL;
    LOG(debug,
        "RPCHooksBase::finiSession req=%p connection=%p session=%p",
        req,
        conn,
        vctx);

    Session::SP *sessionspp = static_cast<Session::SP *>(vctx);
    delete sessionspp;
}

void
RPCHooksBase::downSession(FRT_RPCRequest *req)
{
    LOG(debug,
        "RPCHooksBase::downSession req=%p connection=%p session=%p",
        req,
        req->GetConnection(),
        req->GetConnection()->GetContext()._value.VOIDP);
    getSession(req)->setDown();
}

void
RPCHooksBase::mismatch(FRT_RPCRequest *req)
{
    LOG(warning, "RPC Mismatch: %s, (%d): %s",
        req->GetMethodName(),
        req->GetErrorCode(), req->GetErrorMessage());
}

RPCHooks::RPCHooks(Params &params) :
    RPCHooksBase(params)
{
    open(params);
}

}
