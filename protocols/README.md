<!-- Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root. -->
# Cross-module protocol definitions

This module can be used as a place to put cross-module definitions of APIs
(e.g. JSON). For instance, put golden JSON files that are supposed to be
generated by the distributor (in some C++ tests), and read by Cluster
Controller (in some Java tests). This module is supposed to be light-weight.
