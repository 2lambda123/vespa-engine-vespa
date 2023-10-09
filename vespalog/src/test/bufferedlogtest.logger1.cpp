// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/log/bufferedlogger.h>
#include "bufferedlogtest.logger1.h"

LOG_SETUP(".logger1");

void logWithLogger1(const std::string& token, const std::string & message)
{
    LOGBT(info, token, "%s", message.c_str());
}
