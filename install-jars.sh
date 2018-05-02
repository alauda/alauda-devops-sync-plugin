#!/bin/bash
if [ -d ../kubernetes-model ]; then
  cp ../kubernetes-model/kubernetes-model/target/kubernetes-model-0.2.jar ./lib/
fi
#mvn install:install-file -Dfile=./lib/kubernetes-model-0.2.jar -DgroupId=io.fabric8 -DartifactId=kubernetes-model -Dversion=0.2 -DlocalRepositoryPath=./lib -DcreateChecksum=true -Dpackaging=jar
mvn install:install-file -Dfile=./lib/kubernetes-model-0.2.jar -DgroupId=io.fabric8 -DartifactId=kubernetes-model-alauda -Dversion=0.2 -DlocalRepositoryPath=./lib -DcreateChecksum=true -Dpackaging=jar
rm -rf ./lib/kubernetes-model-0.2.jar


if [ -d ../kubernetes-client ]; then
  cp ../kubernetes-client/uberjar/target/kubernetes-openshift-uberjar-3.1-SNAPSHOT.jar ./lib/
fi
#mvn install:install-file -Dfile=./lib/kubernetes-openshift-uberjar-3.1-SNAPSHOT.jar -DgroupId=io.fabric8 -DartifactId=kubernetes-client -Dversion=0.2 -DlocalRepositoryPath=./lib -DcreateChecksum=true -Dpackaging=jar
mvn install:install-file -Dfile=./lib/kubernetes-openshift-uberjar-3.1-SNAPSHOT.jar -DgroupId=io.fabric8 -DartifactId=kubernetes-client-alauda -Dversion=0.2 -DlocalRepositoryPath=./lib -DcreateChecksum=true -Dpackaging=jar
rm -rf ./lib/kubernetes-openshift-uberjar-3.1-SNAPSHOT.jar
