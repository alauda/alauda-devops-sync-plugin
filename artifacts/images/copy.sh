#!/bin/sh
if [ "$JENKINS_HOME" == "" ]; then
  JENKINS_HOME="/var/jenkins_home"  
fi;

if [ -d $JENKINS_HOME ]; then
  echo "copying plugins..."
  mkdir -p $JENKINS_HOME/plugins/
  cp /plugin/*.hpi  $JENKINS_HOME/plugins/
else
  echo "$JENKINS_HOME folder not found...."
fi;