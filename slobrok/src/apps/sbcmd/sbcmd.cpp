// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fnet/frt/frt.h>
#include <vespa/fastos/app.h>
#include <string>
#include <sstream>

#include <vespa/log/log.h>
LOG_SETUP("sb-cmd");

class Slobrok_CMD : public FastOS_Application
{
private:
    FRT_Supervisor *_supervisor;
    FRT_Target     *_target;

    Slobrok_CMD(const Slobrok_CMD &);
    Slobrok_CMD &operator=(const Slobrok_CMD &);

public:
    Slobrok_CMD() : _supervisor(NULL), _target(NULL) {}
    virtual ~Slobrok_CMD();
    int usage();
    void initRPC(const char *spec);
    void finiRPC();
    int Main() override;
};

Slobrok_CMD::~Slobrok_CMD()
{
    LOG_ASSERT(_supervisor == NULL);
    LOG_ASSERT(_target == NULL);
}

int
Slobrok_CMD::usage()
{
    fprintf(stderr, "usage: %s <port|spec> <cmd> [args]\n", _argv[0]);
    fprintf(stderr, "with cmd one of:\n");
    fprintf(stderr, "  slobrok.callback.listNamesServed\n");
    fprintf(stderr, "  slobrok.internal.listManagedRpcServers\n");
    fprintf(stderr, "  slobrok.admin.listAllRpcServers\n");
    fprintf(stderr, "  slobrok.lookupRpcServer {pattern}\n");
    fprintf(stderr, "  slobrok.registerRpcServer name {spec}\n");
    fprintf(stderr, "  slobrok.unregisterRpcServer {name} {spec}\n");
    fprintf(stderr, "  slobrok.admin.addPeer {name} {spec}\n");
    fprintf(stderr, "  slobrok.admin.removePeer {name} {spec}\n");
    fprintf(stderr, "  slobrok.system.stop\n");
    fprintf(stderr, "  slobrok.system.version\n");
    fprintf(stderr, "  system.stop\n");
    return 1;
}


void
Slobrok_CMD::initRPC(const char *spec)
{
    _supervisor = new FRT_Supervisor();
    _target     = _supervisor->GetTarget(spec);
    _supervisor->Start();
}


void
Slobrok_CMD::finiRPC()
{
    if (_target != NULL) {
        _target->SubRef();
        _target = NULL;
    }
    if (_supervisor != NULL) {
        _supervisor->ShutDown(true);
        delete _supervisor;
        _supervisor = NULL;
    }
}


int
Slobrok_CMD::Main()
{
    if (_argc < 3) {
        return usage();
    }
    int port = atoi(_argv[1]);
    if (port == 0) {
        initRPC(_argv[1]);
    } else {
        std::ostringstream tmp;
        tmp << "tcp/localhost:";
        tmp << port;
        initRPC(tmp.str().c_str());
    }

    bool threeTables = false;
    bool twoTables = false;

    FRT_RPCRequest *req = _supervisor->AllocRPCRequest();

    req->SetMethodName(_argv[2]);
    if (strcmp(_argv[2], "slobrok.admin.listAllRpcServers") == 0) {
        threeTables = true;
        // no params
    } else if (strcmp(_argv[2],  "slobrok.internal.listManagedRpcServers") == 0) {
        twoTables = true;
        // no params
    } else if (strcmp(_argv[2], "slobrok.callback.listNamesServed") == 0
               || strcmp(_argv[2], "slobrok.internal.listManagedRpcServers") == 0
               || strcmp(_argv[2], "slobrok.admin.listAllRpcServers") == 0
               || strcmp(_argv[2], "slobrok.system.stop") == 0
               || strcmp(_argv[2], "slobrok.system.version") == 0
               || strcmp(_argv[2], "system.stop") == 0)
    {
        // no params
    } else if (strcmp(_argv[2], "slobrok.lookupRpcServer") == 0
               && _argc == 4)
    {
        twoTables = true;
        // one param
        req->GetParams()->AddString(_argv[3]);
    } else if ((strcmp(_argv[2], "slobrok.registerRpcServer") == 0
                || strcmp(_argv[2], "slobrok.unregisterRpcServer") == 0
                || strcmp(_argv[2], "slobrok.admin.addPeer") == 0
                || strcmp(_argv[2], "slobrok.admin.removePeer") == 0)
               && _argc == 5)
    {
        // two params
        req->GetParams()->AddString(_argv[3]);
        req->GetParams()->AddString(_argv[4]);
    } else {
        finiRPC();
        return usage();
    }
    _target->InvokeSync(req, 5.0);

    if (req->IsError()) {
        fprintf(stderr, "sb-cmd error %d: %s\n",
                req->GetErrorCode(), req->GetErrorMessage());
    } else {
        FRT_Values &answer = *(req->GetReturn());
        const char *atypes = answer.GetTypeString();
        if (threeTables
            && strcmp(atypes, "SSS") == 0
            && answer[0]._string_array._len > 0
            && answer[0]._string_array._len == answer[1]._string_array._len
            && answer[0]._string_array._len == answer[2]._string_array._len)
        {
            for (uint32_t j = 0; j < answer[0]._string_array._len; j++) {
                printf("%s\t%s\t%s\n",
                       answer[0]._string_array._pt[j]._str,
                       answer[1]._string_array._pt[j]._str,
                       answer[2]._string_array._pt[j]._str);
            }
        } else if (twoTables
                   && strcmp(atypes, "SS") == 0
                   && answer[0]._string_array._len > 0
                   && answer[0]._string_array._len == answer[1]._string_array._len)
        {
            for (uint32_t j = 0; j < answer[0]._string_array._len; j++) {
                printf("%s\t%s\n",
                       answer[0]._string_array._pt[j]._str,
                       answer[1]._string_array._pt[j]._str);
            }
        } else {
            fprintf(stderr, "sb-cmd OK, returntypes '%s'\n", atypes);
            uint32_t idx = 0;
            while (atypes != NULL && *atypes != '\0') {
                switch (*atypes) {
                case 's':
                    printf("    string = '%s'\n", answer[idx]._string._str);
                    break;
                case 'S':
                    printf("   strings [%d]\n", answer[idx]._string_array._len);
                    for (uint32_t j = 0; j < answer[idx]._string_array._len; j++) {
                        printf("\t'%s'\n",  answer[idx]._string_array._pt[j]._str);
                    }
                    break;
                default:
                    printf("   unknown type %c\n", *atypes);
                }
                ++atypes;
                ++idx;
            }
        }
    }
    req->SubRef();
    finiRPC();
    return 0;
}

int main(int argc, char **argv)
{
    Slobrok_CMD sb_cmd;
    return sb_cmd.Entry(argc, argv);
}
