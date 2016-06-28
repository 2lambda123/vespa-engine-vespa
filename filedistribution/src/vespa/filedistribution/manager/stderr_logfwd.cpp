// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/filedistribution/common/logfwd.h>

#include <stdarg.h>
#include <iostream>
#include <boost/scoped_array.hpp>
#include <stdio.h>



void filedistribution::logfwd::log_forward(LogLevel level, const char* file, int line, const char* fmt, ...)
{
    if (level == debug || level == info)
        return;

    const size_t maxSize(0x8000);
    boost::scoped_array<char> payload(new char[maxSize]);

    va_list args;
    va_start(args, fmt);
    vsnprintf(payload.get(), maxSize, fmt, args);
    va_end(args);

    std::cerr <<"Error: " <<payload.get() <<" File: " <<file <<" Line: " <<line <<std::endl;
}
