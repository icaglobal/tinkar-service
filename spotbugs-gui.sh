#!/bin/bash
# Opens the SpotBugs GUI for the service module.
# Generates the XML report first if it doesn't exist.
# Uses spotbugs-pom.xml to resolve the SpotBugs classpath (cached in target/).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
XML="$SCRIPT_DIR/target/spotbugsXml.xml"
CP_FILE="$SCRIPT_DIR/target/spotbugs-classpath.txt"
MVN="$SCRIPT_DIR/mvnw"

if [ ! -f "$XML" ]; then
    echo "No spotbugsXml.xml found — generating report..."
    (cd "$SCRIPT_DIR" && "$MVN" compile spotbugs:spotbugs)
fi

if [ ! -f "$CP_FILE" ]; then
    echo "Resolving SpotBugs classpath (one-time)..."
    (cd "$SCRIPT_DIR" && "$MVN" -f spotbugs-pom.xml -q \
        dependency:build-classpath -Dmdep.outputFile="$CP_FILE")
fi

echo "Opening SpotBugs GUI..."
java -cp "$(cat "$CP_FILE")" edu.umd.cs.findbugs.LaunchAppropriateUI \
    -gui "$XML" &
