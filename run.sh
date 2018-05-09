#!/bin/bash

set -e

TOKEN_ADMIN="2af7edcf31aff73037d58174c4cfcfb7"
ADDRESS="http://localhost:30696"
export JENKINS_CONTAINER=$(docker ps | grep jenkins |  head -n1 | awk '{print $1;}')
echo $JENKINS_CONTAINER
if [ "$JENKINS_CONTAINER" == "" ]; then
  echo "Jenkins container not found"
  exit 1;
fi
mvn clean install -DskipTests
docker cp target/alauda-devops-sync.hpi $JENKINS_CONTAINER:/var/jenkins_home/plugins
./restart.sh
