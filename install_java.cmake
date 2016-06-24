function(install_java_artifact NAME)
    install(FILES "${NAME}/target/${NAME}.jar" DESTINATION lib/jars/)
endfunction()

function(install_java_artifact_dependencies NAME)
    install(DIRECTORY "${NAME}/target/dependency/" DESTINATION lib/jars FILES_MATCHING PATTERN "*.jar")
endfunction()

function(install_fat_java_artifact NAME)
    install(FILES "${NAME}/target/${NAME}-jar-with-dependencies.jar" DESTINATION lib/jars/)
endfunction()

install_java_artifact(config-model-fat)
install_java_artifact(document)
install_java_artifact(jdisc_jetty)
install_java_artifact_dependencies(jdisc_jetty)
install_java_artifact_dependencies(vespa_jersey2)
install_java_artifact(vespajlib)

install_fat_java_artifact(application-preprocessor)
install_fat_java_artifact(clustercontroller-apps)
install_fat_java_artifact(clustercontroller-apputil)
install_fat_java_artifact(clustercontroller-utils)
install_fat_java_artifact(clustercontroller-core)
install_fat_java_artifact(component)
install_fat_java_artifact(config-bundle)
install_fat_java_artifact(config-model-api)
install_fat_java_artifact(config-model)
install_fat_java_artifact(config-provisioning)
install_fat_java_artifact(config-proxy)
install_fat_java_artifact(configdefinitions)
install_fat_java_artifact(configserver)
install_fat_java_artifact(container-disc)
install_fat_java_artifact(container-jersey2)
install_fat_java_artifact(container-search-and-docproc)
install_fat_java_artifact(defaults)
install_fat_java_artifact(docprocs)
install_fat_java_artifact(jdisc_core)
install_fat_java_artifact(jdisc_http_service)
install_fat_java_artifact(logserver)
install_fat_java_artifact(node-repository)
install_fat_java_artifact(orchestrator)
install_fat_java_artifact(persistence)
install_fat_java_artifact(searchlib)
install_fat_java_artifact(simplemetrics)
install_fat_java_artifact(standalone-container)
install_fat_java_artifact(vespa-http-client)
install_fat_java_artifact(vespaclient-container-plugin)
install_fat_java_artifact(zkfacade)

vespa_install_script(application-preprocessor/src/main/sh/vespa-preprocess-application bin)
vespa_install_script(jdisc_core/src/main/perl/jdisc_logfmt bin)
vespa_install_script(config-proxy/src/main/sh/config-ctl bin)
vespa_install_script(config-model/src/main/perl/deploy bin)
vespa_install_script(config-model/src/main/perl/deploy-application bin)
vespa_install_script(config-model/src/main/perl/expand-config.pl bin)
vespa_install_script(config-model/src/main/perl/vespa-replicate-log-stream bin)
vespa_install_script(config-model/src/main/sh/validate-application bin)
vespa_install_script(container-disc/src/main/sh/vespa-start-container-daemon.sh vespa-start-container-daemon bin)

vespa_install_script(logserver/bin/logserver-start.sh logserver-start bin)

install(DIRECTORY config-model/src/main/resources/schema DESTINATION share/vespa PATTERN ".gitignore" EXCLUDE)
install(DIRECTORY config-model/src/main/resources/schema DESTINATION share/vespa/schema/version/6.x PATTERN ".gitignore" EXCLUDE)

install(FILES jdisc_core/src/main/perl/jdisc_logfmt.1 DESTINATION man/man1)

install(FILES
    config-model-fat/src/main/resources/config-models.xml
    node-repository/src/main/config/node-repository.xml
    DESTINATION conf/configserver-app)

install(FILES
    chain/src/main/resources/configdefinitions/chains.def
    container-accesslogging/src/main/resources/configdefinitions/access-log.def
    container-core/src/main/resources/configdefinitions/application-metadata.def
    container-core/src/main/resources/configdefinitions/container-document.def
    container-core/src/main/resources/configdefinitions/container-http.def
    container-core/src/main/resources/configdefinitions/diagnostics.def
    container-core/src/main/resources/configdefinitions/health-monitor.def
    container-core/src/main/resources/configdefinitions/http-filter.def
    container-core/src/main/resources/configdefinitions/metrics-presentation.def
    container-core/src/main/resources/configdefinitions/mockservice.def
    container-core/src/main/resources/configdefinitions/qr.def
    container-core/src/main/resources/configdefinitions/qr-logging.def
    container-core/src/main/resources/configdefinitions/qr-searchers.def
    container-core/src/main/resources/configdefinitions/qr-templates.def
    container-core/src/main/resources/configdefinitions/servlet-config.def
    container-core/src/main/resources/configdefinitions/threadpool.def
    container-core/src/main/resources/configdefinitions/vip-status.def
    container-disc/src/main/resources/configdefinitions/container.jdisc.config.http-server.def
    container-disc/src/main/resources/configdefinitions/jdisc-bindings.def
    container-disc/src/main/resources/configdefinitions/jersey-connection.def
    container-disc/src/main/resources/configdefinitions/jersey-init.def
    container-disc/src/main/resources/configdefinitions/jersey-web-app-pool.def
    container-disc/src/main/resources/configdefinitions/metric-defaults.def
    container-disc/src/main/resources/configdefinitions/port-overrides.def
    container-disc/src/main/resources/configdefinitions/score-board.def
    container-di/src/main/resources/configdefinitions/bundles.def
    container-di/src/main/resources/configdefinitions/components.def
    container-di/src/main/resources/configdefinitions/jersey-bundles.def
    container-di/src/main/resources/configdefinitions/jersey-injection.def
    container-messagebus/src/main/resources/configdefinitions/container-mbus.def
    container-messagebus/src/main/resources/configdefinitions/session.def
    container-search-and-docproc/src/main/resources/configdefinitions/application-userdata.def
    container-search/src/main/resources/configdefinitions/cluster.def
    container-search/src/main/resources/configdefinitions/documentdb-info.def
    container-search/src/main/resources/configdefinitions/emulation.def
    container-search/src/main/resources/configdefinitions/federation.def
    container-search/src/main/resources/configdefinitions/fs4.def
    container-search/src/main/resources/configdefinitions/index-info.def
    container-search/src/main/resources/configdefinitions/keyvalue.def
    container-search/src/main/resources/configdefinitions/legacy-emulation.def
    container-search/src/main/resources/configdefinitions/lowercasing.def
    container-search/src/main/resources/configdefinitions/measure-qps.def
    container-search/src/main/resources/configdefinitions/page-templates.def
    container-search/src/main/resources/configdefinitions/provider.def
    container-search/src/main/resources/configdefinitions/qr-binary-cache.def
    container-search/src/main/resources/configdefinitions/qr-binary-cache-region.def
    container-search/src/main/resources/configdefinitions/qr-monitor.def
    container-search/src/main/resources/configdefinitions/qr-quotetable.def
    container-search/src/main/resources/configdefinitions/qr-start.def
    container-search/src/main/resources/configdefinitions/query-profiles.def
    container-search/src/main/resources/configdefinitions/rate-limiting.def
    container-search/src/main/resources/configdefinitions/resolvers.def
    container-search/src/main/resources/configdefinitions/rewrites.def
    container-search/src/main/resources/configdefinitions/searchchain-forward.def
    container-search/src/main/resources/configdefinitions/search-nodes.def
    container-search/src/main/resources/configdefinitions/search-with-renderer-handler.def
    container-search/src/main/resources/configdefinitions/semantic-rules.def
    container-search/src/main/resources/configdefinitions/strict-contracts.def
    container-search/src/main/resources/configdefinitions/timing-searcher.def
    docproc/src/main/resources/configdefinitions/docproc.def
    docproc/src/main/resources/configdefinitions/schemamapping.def
    docproc/src/main/resources/configdefinitions/splitter-joiner-document-processor.def
    jdisc_http_service/src/main/resources/configdefinitions/jdisc.http.client.http-client.def
    jdisc_http_service/src/main/resources/configdefinitions/jdisc.http.connector.def
    jdisc_http_service/src/main/resources/configdefinitions/jdisc.http.server.def
    jdisc_http_service/src/main/resources/configdefinitions/jdisc.http.servlet-paths.def
    jdisc_jmx_metrics/src/main/resources/configdefinitions/jmx-metric.def
    persistence/src/main/resources/configdefinitions/persistence-rpc.def
    simplemetrics/src/main/resources/configdefinitions/manager.def
    statistics/src/main/resources/configdefinitions/statistics.def
    vespaclient-core/src/main/resources/configdefinitions/feeder.def
    vespaclient-core/src/main/resources/configdefinitions/spooler.def
    DESTINATION var/db/vespa/config_server/serverdb/classes)
