#!/usr/bin/env bash
# Deploy FleetOps to your AWS account.  Usage: ./deploy.sh [on-call-email]
# Needs: AWS credentials (aws configure / SSO), Java 21, Maven, Node 20+.
set -euo pipefail
cd "$(dirname "$0")"
EMAIL="${1:-}"

echo "==> building Lambda jar"
mvn -q -B package -DskipTests

echo "==> deploying CDK stack"
cd infra
npm ci --no-audit --no-fund
npx tsc
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
REGION="${AWS_REGION:-$(aws configure get region || echo us-east-1)}"
ARGS=()
[[ -n "$EMAIL" ]] && ARGS+=(-c "alertEmail=$EMAIL")
npx cdk bootstrap "aws://$ACCOUNT/$REGION" ${ARGS[@]+"${ARGS[@]}"}
npx cdk deploy FleetOps --require-approval never --outputs-file ../cdk-outputs.json ${ARGS[@]+"${ARGS[@]}"}
cd ..
out() { python3 -c "import json;print(json.load(open('cdk-outputs.json'))['FleetOps']['$1'])"; }
API="$(out ApiUrl)"
# The simulator needs the ingest key locally; keep it out of git (.gitignore) and readable only by you.
(umask 077; aws secretsmanager get-secret-value --secret-id "$(out IngestKeySecretArn)" --query SecretString --output text > .ingest-key)
echo
echo "API:        $API"
echo "Ingest key: copied to .ingest-key (send as header x-fleet-key)"
echo "Operator key (x-operator-key, for triage/resolve):"
echo "            aws secretsmanager get-secret-value --secret-id $(out OperatorKeySecretArn) --query SecretString --output text"
echo "Dashboard:  https://nags-gk.github.io/fleetops/?api=$API"
[[ -n "$EMAIL" ]] && echo "Confirm the SNS subscription email sent to $EMAIL to receive pages."
echo "Next:       ./load.sh   (streams a simulated fleet for 15 minutes)"
echo "Tear down:  (cd infra && npx cdk destroy FleetOps)"
