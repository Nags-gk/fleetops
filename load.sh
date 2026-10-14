#!/usr/bin/env bash
# Stream a simulated fleet (with injected faults) into the deployed API.  Usage: ./load.sh [robots] [minutes]
set -euo pipefail
cd "$(dirname "$0")"
API="$(python3 -c 'import json;print(json.load(open("cdk-outputs.json"))["FleetOps"]["ApiUrl"])')"
mvn -q -B compile exec:java -Dexec.args="load $API $(cat .ingest-key) ${1:-50} ${2:-15}"
