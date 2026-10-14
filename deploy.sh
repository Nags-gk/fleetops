#!/usr/bin/env bash
# Deploy FleetOps to your AWS account.  Usage: ./deploy.sh [on-call-email] [gemini-api-key]
# Needs: AWS credentials (aws configure / SSO), Java 21, Maven, Node 20+.
set -euo pipefail
cd "$(dirname "$0")"
EMAIL="${1:-}"
GEMINI="${2:-${GEMINI_API_KEY:-}}"
[[ -f .ingest-key ]] || openssl rand -hex 24 > .ingest-key
KEY="$(cat .ingest-key)"

echo "==> building Lambda jar"
mvn -q -B package -DskipTests

echo "==> deploying CDK stack"
cd infra
npm ci --no-audit --no-fund
npx tsc
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
REGION="${AWS_REGION:-$(aws configure get region || echo us-east-1)}"
npx cdk bootstrap "aws://$ACCOUNT/$REGION"
ARGS=(-c "ingestKey=$KEY")
[[ -n "$EMAIL" ]] && ARGS+=(-c "alertEmail=$EMAIL")
[[ -n "$GEMINI" ]] && ARGS+=(-c "geminiKey=$GEMINI")
npx cdk deploy FleetOps --require-approval never --outputs-file ../cdk-outputs.json "${ARGS[@]}"
cd ..
API="$(python3 -c 'import json;print(json.load(open("cdk-outputs.json"))["FleetOps"]["ApiUrl"])')"
echo
echo "API:        $API"
echo "Ingest key: stored in .ingest-key (send as header x-fleet-key)"
echo "Dashboard:  https://nags-gk.github.io/fleetops/?api=$API"
[[ -n "$EMAIL" ]] && echo "Confirm the SNS subscription email sent to $EMAIL to receive pages."
echo "Next:       ./load.sh   (streams a simulated fleet for 15 minutes)"
echo "Tear down:  (cd infra && npx cdk destroy FleetOps -c ingestKey=x)"
