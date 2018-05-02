#!/bin/bash

if [ -d .tmp ]; then
  rm -rf .tmp
fi;

mkdir .tmp
cp artifacts/images/* .tmp
cp target/*.hpi .tmp

if [ "$@" != "" ]; then
  docker build -t jenkins-plugin-injector .tmp
  docker tag jenkins-plugin-injector index.alauda.cn/alaudak8s/jenkins-plugin-injector:dev
  docker push index.alauda.cn/alaudak8s/jenkins-plugin-injector:dev
fi;