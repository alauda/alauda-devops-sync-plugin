#!/bin/sh
# current script needs env.sh to setup some environment variables

if [ ! -f "$(dirname "${BASH_SOURCE[0]}")/env.sh" ]; then
    echo 'we need the env.sh to setup vars'
    exit -1
fi

source $(dirname "${BASH_SOURCE[0]}")/env.sh
export issuer=$(curl -u admin:$JENKINS_TOKEN $JENKINS_URL/crumbIssuer/api/json -s)
issuer=$(python -c "import json;import os;issuer=os.getenv('issuer');issuer=json.loads(issuer);print issuer['crumb']")

curl -u admin:$JENKINS_TOKEN $JENKINS_URL/pluginManager/uploadPlugin -F "name=@$(dirname "${BASH_SOURCE[0]}")/../target/alauda-devops-sync.hpi" --header "Jenkins-Crumb: $issuer"
curl -u admin:$JENKINS_TOKEN $JENKINS_URL/restart -X POST --header "Jenkins-Crumb: $issuer"