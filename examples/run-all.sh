#!/bin/sh
# Runs every example in turn. From the examples directory:  ./run-all.sh
set -e
mvn -q generate-resources
CLASSPATH_FILE=target/classpath.txt
for example in src/main/java/*.java; do
    # Runs until stopped, so it is not part of a run-through.
    case "$example" in *DashboardDemo.java) continue ;; esac
    echo "================================================================"
    echo "$example"
    echo "================================================================"
    java --enable-native-access=ALL-UNNAMED -Xmx4g -cp "$(cat "$CLASSPATH_FILE")" "$example"
    echo
done
