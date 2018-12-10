package com.yahoo.vespa.hosted.controller.restapi.cost;

import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.LoggingRequestHandler;
import com.yahoo.restapi.Path;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.noderepository.NodeRepositoryClientInterface;
import com.yahoo.vespa.hosted.controller.restapi.ErrorResponse;
import com.yahoo.vespa.hosted.controller.restapi.StringResponse;

import java.time.Clock;

import static com.yahoo.jdisc.http.HttpRequest.Method.GET;

public class CostApiHandler extends LoggingRequestHandler {

    private final Controller controller;
    private final NodeRepositoryClientInterface nodeRepository;

    public CostApiHandler(Context ctx, Controller controller, NodeRepositoryClientInterface nodeRepository) {
        super(ctx);
        this.controller = controller;
        this.nodeRepository = nodeRepository;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        if (request.getMethod() != GET) {
            return ErrorResponse.methodNotAllowed("Method '" + request.getMethod() + "' is not supported");
        }

        Path path = new Path(request.getUri().getPath());

        if (path.matches("/cost/v1/csv")) {
            return new StringResponse(CostCalculator.toCsv(CostCalculator.calculateCost(nodeRepository, controller, Clock.systemUTC())));
        }

        return ErrorResponse.notFoundError("Nothing at " + path);
    }
}
