#!/bin/bash

set -e

TOKEN_ADMIN="2af7edcf31aff73037d58174c4cfcfb7"
ADDRESS="http://localhost:30696"
NAMESPACE=default-1
DEPLOY=jenkins

echo "Restarting jenkins..."
curl -v -XPOST $ADDRESS/safeRestart -u 'admin:'$TOKEN_ADMIN
kubectl logs -f --tail=50 -n $NAMESPACE deploy/$DEPLOY
echo "===================================================="
echo "Jenkins restarted... tailling logs again..."
echo "===================================================="
sleep 2
kubectl logs -f --tail=100 -n $NAMESPACE deploy/$DEPLOY
