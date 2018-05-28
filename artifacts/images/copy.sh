#!/bin/sh
if [ "$JENKINS_HOME" == "" ]; then
  JENKINS_HOME="/var/jenkins_home"  
fi;

if [ ! -d $JENKINS_HOME/plugins ]; then
  mkdir -p $JENKINS_HOME/plugins/
  echo "create plugin dir"
fi;

echo "copying plugins..."
cp /plugin/*.hpi $JENKINS_HOME/plugins/

ls -ahl $JENKINS_HOME/plugins/