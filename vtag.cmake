# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
# Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
if(NOT EXISTS "${CMAKE_CURRENT_LIST_DIR}/dist/vtag.map")
    message(FATAL_ERROR "dist/vtag.map does not exist, please run bootstrap.sh before configuring cmake" )
endif()

function(get_vtag_define KEY)
    file(STRINGS ${CMAKE_CURRENT_LIST_DIR}/dist/vtag.map VALUE REGEX "${KEY}")
    list(GET VALUE 0 LINE)
    separate_arguments(DATA UNIX_COMMAND "${LINE}")
    list(GET DATA 1 VALUE)
    set(DEFINE "-D${KEY}=\\\"${VALUE}\\\"")
    set(${KEY} "${DEFINE}" PARENT_SCOPE)
endfunction()

get_vtag_define(V_TAG)
get_vtag_define(V_TAG_DATE)
get_vtag_define(V_TAG_PKG)
get_vtag_define(V_TAG_ARCH)
get_vtag_define(V_TAG_SYSTEM)
get_vtag_define(V_TAG_SYSTEM_REV)
get_vtag_define(V_TAG_BUILDER)
get_vtag_define(V_TAG_COMPONENT)
get_vtag_define(V_TAG_COMMIT_SHA)
get_vtag_define(V_TAG_COMMIT_DATE)

set(VTAG_DEFINES "${V_TAG} ${V_TAG_DATE} ${V_TAG_PKG} ${V_TAG_ARCH} ${V_TAG_SYSTEM} ${V_TAG_SYSTEM_REV} ${V_TAG_BUILDER} ${V_TAG_COMPONENT} ${V_TAG_COMMIT_SHA} ${V_TAG_COMMIT_DATE}")
