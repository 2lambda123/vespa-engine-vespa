#!/bin/bash
# Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

if [ $# -ne 1 ]; then
  echo "Usage: $0 <install prefix>"
  exit 1
fi

declare -r PREFIX="$1"
declare -r INSTALLPATH="$DESTDIR/$PREFIX"

# Rewrite config def file names
for path in $INSTALLPATH/var/db/vespa/config_server/serverdb/classes/*.def; do
    dir=$(dirname $path)
    filename=$(basename $path)
    namespace=$(grep '^ *namespace *=' $path | sed 's/ *namespace *= *//')
    if [ "$namespace" ]; then
        case $filename in
            $namespace.*)
                ;;
            *)
                mv $path $dir/$namespace.$filename ;;
        esac
    fi
done

mkdir -p $INSTALLPATH/conf/configserver/
mkdir -p $INSTALLPATH/conf/configserver-app/
mkdir -p $INSTALLPATH/conf/configserver-app/config-models/
mkdir -p $INSTALLPATH/conf/configserver-app/components/
mkdir -p $INSTALLPATH/conf/filedistributor/
mkdir -p $INSTALLPATH/conf/node-admin-app/
mkdir -p $INSTALLPATH/conf/node-admin-app/components/
mkdir -p $INSTALLPATH/conf/zookeeper/
mkdir -p $INSTALLPATH/libexec/jdisc_core/
mkdir -p $INSTALLPATH/libexec/vespa/modelplugins/
mkdir -p $INSTALLPATH/libexec/vespa/plugins/qrs/
mkdir -p $INSTALLPATH/libexec/yjava_daemon/bin/
mkdir -p $INSTALLPATH/logs/jdisc_core/
mkdir -p $INSTALLPATH/logs/vespa/
mkdir -p $INSTALLPATH/logs/vespa/
mkdir -p $INSTALLPATH/logs/vespa/configserver/
mkdir -p $INSTALLPATH/logs/vespa/search/
mkdir -p $INSTALLPATH/logs/vespa/qrs/
mkdir -p $INSTALLPATH/share/vespa/
mkdir -p $INSTALLPATH/share/vespa/schema/version/6.x/schema/
mkdir -p $INSTALLPATH/tmp/vespa/
mkdir -p $INSTALLPATH/var/db/jdisc/logcontrol/
mkdir -p $INSTALLPATH/var/db/vespa/
mkdir -p $INSTALLPATH/var/db/vespa/config_server/serverdb/configs/
mkdir -p $INSTALLPATH/var/db/vespa/config_server/serverdb/configs/application/
mkdir -p $INSTALLPATH/var/db/vespa/config_server/serverdb/applications/
mkdir -p $INSTALLPATH/var/db/vespa/logcontrol/
mkdir -p $INSTALLPATH/var/jdisc_container/
mkdir -p $INSTALLPATH/var/jdisc_core/
mkdir -p $INSTALLPATH/var/run/
mkdir -p $INSTALLPATH/var/spool/vespa/
mkdir -p $INSTALLPATH/var/spool/master/inbox/
mkdir -p $INSTALLPATH/var/vespa/bundlecache/
mkdir -p $INSTALLPATH/var/vespa/cache/config/
mkdir -p $INSTALLPATH/var/vespa/cmdlines/
mkdir -p $INSTALLPATH/var/zookeeper/version-2/
mkdir -p $INSTALLPATH/sbin

ln -s $PREFIX/lib/jars/config-model-fat.jar $INSTALLPATH/conf/configserver-app/components/config-model-fat.jar
ln -s $PREFIX/lib/jars/configserver-jar-with-dependencies.jar $INSTALLPATH/conf/configserver-app/components/configserver.jar
ln -s $PREFIX/lib/jars/orchestrator-jar-with-dependencies.jar $INSTALLPATH/conf/configserver-app/components/orchestrator.jar
ln -s $PREFIX/lib/jars/node-repository-jar-with-dependencies.jar $INSTALLPATH/conf/configserver-app/components/node-repository.jar
ln -s $PREFIX/lib/jars/zkfacade-jar-with-dependencies.jar $INSTALLPATH/conf/configserver-app/components/zkfacade.jar
ln -s $PREFIX/conf/configserver-app/components $INSTALLPATH/lib/jars/config-models
ln -s vespa-storaged-bin $INSTALLPATH/sbin/vespa-distributord-bin

# Setup default environment
mkdir -p $INSTALLPATH/conf/vespa
cat > $INSTALLPATH/conf/vespa/default-env.txt <<EOF
fallback VESPA_HOME $PREFIX
override VESPA_USER vespa
EOF

