// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.service.monitor.internal;

import com.google.inject.Inject;
import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.config.model.api.SuperModelProvider;
import com.yahoo.config.provision.Zone;
import com.yahoo.jdisc.Metric;
import com.yahoo.jdisc.Timer;
import com.yahoo.vespa.applicationmodel.ApplicationInstance;
import com.yahoo.vespa.applicationmodel.ApplicationInstanceReference;
import com.yahoo.vespa.service.monitor.ServiceModel;
import com.yahoo.vespa.service.monitor.ServiceMonitor;
import com.yahoo.vespa.service.monitor.internal.health.HealthMonitorManager;
import com.yahoo.vespa.service.monitor.internal.slobrok.SlobrokMonitorManagerImpl;

import java.util.Map;

public class ServiceMonitorImpl implements ServiceMonitor {
    private final ServiceModelCache serviceModelCache;

    @Inject
    public ServiceMonitorImpl(SuperModelProvider superModelProvider,
                              ConfigserverConfig configserverConfig,
                              SlobrokMonitorManagerImpl slobrokMonitorManager,
                              HealthMonitorManager healthMonitorManager,
                              Metric metric,
                              Timer timer) {
        Zone zone = superModelProvider.getZone();
        ServiceMonitorMetrics metrics = new ServiceMonitorMetrics(metric, timer);

        DuperModel duperModel = new DuperModel(configserverConfig);
        UnionMonitorManager monitorManager =
                new UnionMonitorManager(slobrokMonitorManager, healthMonitorManager);

        SuperModelListenerImpl superModelListener = new SuperModelListenerImpl(
                monitorManager,
                metrics,
                duperModel,
                new ModelGenerator(),
                zone);
        superModelListener.start(superModelProvider);
        serviceModelCache = new ServiceModelCache(superModelListener, timer);
    }

    @Override
    public Map<ApplicationInstanceReference, ApplicationInstance> getAllApplicationInstances() {
        return serviceModelCache.get().getAllApplicationInstances();
    }

    @Override
    public ServiceModel getServiceModelSnapshot() {
        return serviceModelCache.get();
    }
}
