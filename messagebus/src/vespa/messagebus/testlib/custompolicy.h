// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/messagebus/routing/iroutingpolicy.h>
#include "simpleprotocol.h"

namespace mbus {

class CustomPolicy : public IRoutingPolicy {
private:
    bool                  _selectOnRetry;
    std::vector<uint32_t> _consumableErrors;
    std::vector<Route>    _routes;

public:
    CustomPolicy(bool selectOnRetry,
                 const std::vector<uint32_t> consumableErrors,
                 const std::vector<Route> &routes);

    virtual void select(RoutingContext &context);
    virtual void merge(RoutingContext &context);
};

class CustomPolicyFactory : public SimpleProtocol::IPolicyFactory {
private:
    bool                  _selectOnRetry;
    std::vector<uint32_t> _consumableErrors;

public:
    CustomPolicyFactory();
    CustomPolicyFactory(bool selectOnRetry);
    CustomPolicyFactory(bool selectOnRetry, uint32_t consumableError);
    CustomPolicyFactory(bool selectOnRetry, const std::vector<uint32_t> consumableErrors);

    IRoutingPolicy::UP create(const string &param);
    static void parseRoutes(const string &str, std::vector<Route> &routes);
};

} // namespace mbus

