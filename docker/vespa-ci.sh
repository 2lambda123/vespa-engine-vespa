#!/bin/bash
set -e

if [ $# -ne 1 ]; then
  echo "Usage: $0 <git commit>"
  exit 1
fi

DIR=$(cd $(dirname "${BASH_SOURCE[0]}") && pwd)
cd $DIR

GIT_COMMIT=$1
BUILD_DOCKER_IMAGE="vespabuild"
CI_DOCKER_IMAGE="vespaci"

docker build -t "$BUILD_DOCKER_IMAGE" -f Dockerfile.build .
docker build -t "$CI_DOCKER_IMAGE" -f Dockerfile.ci .
docker run -v $(pwd)/..:/vespa --entrypoint /vespa-ci-internal.sh "$CI_DOCKER_IMAGE" "$GIT_COMMIT"
