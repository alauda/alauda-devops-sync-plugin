#!/bin/bash

set -e

TOKEN_ADMIN="76f4ff3c7a5fe8fe8fcaf54fe17153d7"
ADDRESS="http://localhost:30427"
export JENKINS_CONTAINER=$(docker ps | grep jenkins |  head -n1 | awk '{print $1;}')
echo $JENKINS_CONTAINER
if [ "$JENKINS_CONTAINER" == "" ]; then
  echo "Jenkins container not found"
  exit 1;
fi
mvn clean install -DskipTests
docker cp target/alauda-devops-sync.hpi $JENKINS_CONTAINER:/var/jenkins_home/plugins
./restart.sh
