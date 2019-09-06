// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration;

import com.yahoo.vespa.hosted.controller.api.integration.certificates.ApplicationCertificateProvider;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.ConfigServer;
import com.yahoo.vespa.hosted.controller.api.integration.dns.NameService;
import com.yahoo.vespa.hosted.controller.api.integration.organization.Mailer;
import com.yahoo.vespa.hosted.controller.api.integration.routing.GlobalRoutingService;
import com.yahoo.vespa.hosted.controller.api.integration.routing.RoutingGenerator;

/**
 * This provides access to all service dependencies of the controller. Implementations of this are responsible for
 * constructing and configuring service implementations suitable for use by the controller.
 *
 * @author mpolden
 */
// TODO(mpolden): Access all services through this
public interface ServiceRegistry {

    ConfigServer configServer();

    NameService nameService();

    GlobalRoutingService globalRoutingService();

    RoutingGenerator routingGenerator();

    Mailer mailer();

    ApplicationCertificateProvider applicationCertificateProvider();

}
