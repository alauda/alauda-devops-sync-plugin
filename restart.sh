#!/bin/bash

set -e

TOKEN_ADMIN="76f4ff3c7a5fe8fe8fcaf54fe17153d7"
ADDRESS="http://localhost:30427"
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
