#!/bin/sh
# Runs every example in turn. From the examples directory:  ./run-all.sh
set -e
mvn -q generate-resources
CLASSPATH_FILE=target/classpath.txt
for example in src/main/java/*.java; do
    echo "================================================================"
    echo "$example"
    echo "================================================================"
    java --enable-native-access=ALL-UNNAMED -Xmx2g -cp "$(cat "$CLASSPATH_FILE")" "$example"
    echo
done
