// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Author: arnej

package envvars

// well-known environment variable names

const (
	ADDR_CONFIGSERVER                      = "addr_configserver"
	CONFIGPROXY_RPC_PORT                   = "port_configproxy_rpc"
	CONFIGSERVER_RPC_PORT                  = "port_configserver_rpc"
	DEBUG_JVM_STARTUP                      = "DEBUG_JVM_STARTUP"
	DEBUG_STARTUP                          = "DEBUG_STARTUP"
	FILE_DESCRIPTOR_LIMIT                  = "file_descriptor_limit"
	GLIBCXX_FORCE_NEW                      = "GLIBCXX_FORCE_NEW"
	HUGEPAGES_LIST                         = "HUGEPAGES_LIST"
	JAVA_HOME                              = "JAVA_HOME"
	JAVAVM_LD_PRELOAD                      = "JAVAVM_LD_PRELOAD"
	LD_LIBRARY_PATH                        = "LD_LIBRARY_PATH"
	LD_PRELOAD                             = "LD_PRELOAD"
	MADVISE_LIST                           = "MADVISE_LIST"
	MALLOC_ARENA_MAX                       = "MALLOC_ARENA_MAX"
	NO_VESPAMALLOC_LIST                    = "NO_VESPAMALLOC_LIST"
	NUM_PROCESSES_LIMIT                    = "num_processes_limit"
	PATH                                   = "PATH"
	PRELOAD                                = "PRELOAD"
	ROOT                                   = "ROOT"
	STANDALONE_JDISC_APP_LOCATION          = "standalone_jdisc_container__app_location"
	STANDALONE_JDISC_DEPLOYMENT_PROFILE    = "standalone_jdisc_container__deployment_profile"
	STD_THREAD_PREVENT_TRY_CATCH           = "STD_THREAD_PREVENT_TRY_CATCH"
	TERM                                   = "TERM"
	TRACE_JVM_STARTUP                      = "TRACE_JVM_STARTUP"
	TRACE_STARTUP                          = "TRACE_STARTUP"
	VESPA_AFFINITY_CPU_SOCKET              = "VESPA_AFFINITY_CPU_SOCKET"
	VESPA_ALREADY_SWITCHED_USER_TO         = "VESPA_ALREADY_SWITCHED_USER_TO"
	VESPA_CLI_API_KEY_FILE                 = "VESPA_CLI_API_KEY_FILE"
	VESPA_CLI_API_KEY                      = "VESPA_CLI_API_KEY"
	VESPA_CLI_CACHE_DIR                    = "VESPA_CLI_CACHE_DIR"
	VESPA_CLI_CLOUD_CI                     = "VESPA_CLI_CLOUD_CI"
	VESPA_CLI_CLOUD_SYSTEM                 = "VESPA_CLI_CLOUD_SYSTEM"
	VESPA_CLI_DATA_PLANE_CERT_FILE         = "VESPA_CLI_DATA_PLANE_CERT_FILE"
	VESPA_CLI_DATA_PLANE_CERT              = "VESPA_CLI_DATA_PLANE_CERT"
	VESPA_CLI_DATA_PLANE_KEY_FILE          = "VESPA_CLI_DATA_PLANE_KEY_FILE"
	VESPA_CLI_DATA_PLANE_KEY               = "VESPA_CLI_DATA_PLANE_KEY"
	VESPA_CLI_ENDPOINTS                    = "VESPA_CLI_ENDPOINTS"
	VESPA_CLI_HOME                         = "VESPA_CLI_HOME"
	VESPA_CONFIG_ID                        = "VESPA_CONFIG_ID"
	VESPA_CONFIGPROXY_JVMARGS              = "VESPA_CONFIGPROXY_JVMARGS"
	VESPA_CONFIGSERVER_JVMARGS             = "VESPA_CONFIGSERVER_JVMARGS"
	VESPA_CONFIGSERVER_MULTITENANT         = "VESPA_CONFIGSERVER_MULTITENANT"
	VESPA_CONFIGSERVERS                    = "VESPA_CONFIGSERVERS"
	VESPA_CONTAINER_JVMARGS                = "VESPA_CONTAINER_JVMARGS"
	VESPA_GROUP                            = "VESPA_GROUP"
	VESPA_HOME                             = "VESPA_HOME"
	VESPA_HOSTNAME                         = "VESPA_HOSTNAME"
	VESPA_LOAD_CODE_AS_HUGEPAGES           = "VESPA_LOAD_CODE_AS_HUGEPAGES"
	VESPA_LOG_CONTROL_DIR                  = "VESPA_LOG_CONTROL_DIR"
	VESPA_LOG_CONTROL_FILE                 = "VESPA_LOG_CONTROL_FILE"
	VESPA_LOG_TARGET                       = "VESPA_LOG_TARGET"
	VESPAMALLOCD_LIST                      = "VESPAMALLOCD_LIST"
	VESPAMALLOCDST_LIST                    = "VESPAMALLOCDST_LIST"
	VESPA_MALLOC_HUGEPAGES                 = "VESPA_MALLOC_HUGEPAGES"
	VESPAMALLOC_LIST                       = "VESPAMALLOCD_LIST"
	VESPA_MALLOC_MADVISE_LIMIT             = "VESPA_MALLOC_MADVISE_LIMIT"
	VESPA_NO_NUMACTL                       = "VESPA_NO_NUMACTL"
	VESPA_ONLY_IP_V6_NETWORKING            = "VESPA_ONLY_IP_V6_NETWORKING"
	VESPA_PORT_BASE                        = "VESPA_PORT_BASE"
	VESPA_SERVICE_NAME                     = "VESPA_SERVICE_NAME"
	VESPA_TIMER_HZ                         = "VESPA_TIMER_HZ"
	VESPA_TLS_CA_CERT                      = "VESPA_TLS_CA_CERT"
	VESPA_TLS_CERT                         = "VESPA_TLS_CERT"
	VESPA_TLS_CONFIG_FILE                  = "VESPA_TLS_CONFIG_FILE"
	VESPA_TLS_ENABLED                      = "VESPA_TLS_ENABLED"
	VESPA_TLS_HOSTNAME_VALIDATION_DISABLED = "VESPA_TLS_HOSTNAME_VALIDATION_DISABLED"
	VESPA_TLS_INSECURE_MIXED_MODE          = "VESPA_TLS_INSECURE_MIXED_MODE"
	VESPA_TLS_PRIVATE_KEY                  = "VESPA_TLS_PRIVATE_KEY"
	VESPA_USE_HUGEPAGES_LIST               = "VESPA_USE_HUGEPAGES_LIST"
	VESPA_USE_HUGEPAGES                    = "VESPA_USE_HUGEPAGES"
	VESPA_USE_MADVISE_LIST                 = "VESPA_USE_MADVISE_LIST"
	VESPA_USE_NO_VESPAMALLOC               = "VESPA_USE_NO_VESPAMALLOC"
	VESPA_USER                             = "VESPA_USER"
	VESPA_USE_VALGRIND                     = "VESPA_USE_VALGRIND"
	VESPA_USE_VESPAMALLOC_DST              = "VESPA_USE_VESPAMALLOC_DST"
	VESPA_USE_VESPAMALLOC_D                = "VESPA_USE_VESPAMALLOC_D"
	VESPA_USE_VESPAMALLOC                  = "VESPA_USE_VESPAMALLOC"
	VESPA_VALGRIND_OPT                     = "VESPA_VALGRIND_OPT"
	VESPA_WEB_SERVICE_PORT                 = "VESPA_WEB_SERVICE_PORT"
)
