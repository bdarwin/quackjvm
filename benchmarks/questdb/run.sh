#!/bin/bash
# The time-series comparison behind docs/questdb.md: quackjvm (DuckDB), QuestDB, CQEngine on the
# heap, and hand-written arrays, on the same seeded data. One engine at a time; QuestDB runs only
# while its own figures are taken. Writes results/<version>-<date>.txt (every line printed) and
# results/<version>-<date>.md (the tables for the docs); a local build is <version>-local-<commit>.
#
#   benchmarks/questdb/run.sh [-v VERSION] [-d "10 1000 100000"] [-e "duck java cq quest"]
#                             [-r RUNS] [-n READINGS] [-m PROBES] [-l MAXLOAD]
#
#   -v  a released quackjvm version to measure, fetched from Maven Central with its checksum
#       checked, so the figures are for the jar users get. Without it: quackjvm-core as this
#       checkout builds it (the root pom's version), packaged but not installed.
#   -d  device counts (default "10 1000 100000")
#   -e  engines (default "duck java cq quest")
#   -r  timed runs per question, after one warm-up (default 9; QuestDB gets 5, CQEngine 3)
#   -n  readings (default 10000000)   -m  probes (default 1000000)
#   -l  wait until the 1-minute load average is below this before each engine (default 5)
#
# Needs: Java 17+, Maven, and QuestDB (brew install questdb) when "quest" is among the engines.
# Takes about 45 minutes with the defaults, most of it QuestDB's unhinted as-of join and CQEngine.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LOCAL_VERSION=$(sed -n 's:^    <version>\(.*\)</version>:\1:p' "$ROOT/pom.xml" | head -1)

VERSION=""
DEVICES="10 1000 100000"
ENGINES="duck java cq quest"
RUNS=9
N=10000000
M=1000000
MAXLOAD=5
while getopts "v:d:e:r:n:m:l:" opt; do
  case $opt in
    v) VERSION=$OPTARG ;;
    d) DEVICES=$OPTARG ;;
    e) ENGINES=$OPTARG ;;
    r) RUNS=$OPTARG ;;
    n) N=$OPTARG ;;
    m) M=$OPTARG ;;
    l) MAXLOAD=$OPTARG ;;
    *) sed -n '2,20p' "$0"; exit 2 ;;
  esac
done
mkdir -p "$HERE/results" "$HERE/target"
QDB_ROOT="$HERE/target/qdb"

log() { echo "$@" | tee -a "$OUT"; }

# ---- the quackjvm jar under test, and the harness's classpath with it
if [ -z "$VERSION" ]; then
  VERSION=$LOCAL_VERSION
  LABEL="$VERSION-local-$(cd "$ROOT" && git rev-parse --short HEAD)"
  echo "Building quackjvm-core $VERSION from $ROOT (not installed)"
  (cd "$ROOT" && mvn -o -q -pl quackjvm-core package -DskipTests)
  (cd "$ROOT" && mvn -o -q -pl quackjvm-core dependency:build-classpath -Dmdep.includeScope=runtime \
      -Dmdep.outputFile="$HERE/target/core-deps.txt")
  CORE_JAR="$ROOT/quackjvm-core/target/quackjvm-core-$VERSION.jar"
  SOURCE="built from $(cd "$ROOT" && git rev-parse --short HEAD)$(cd "$ROOT" && git diff --quiet HEAD -- . ':!benchmarks' || echo ' with uncommitted changes')"
  (cd "$HERE" && mvn -o -q dependency:build-classpath -Dlocal -Dmdep.outputFile=target/deps.txt)
  DEPS="$(cat "$HERE/target/deps.txt"):$(cat "$HERE/target/core-deps.txt")"
else
  BASE="https://repo1.maven.org/maven2/io/github/bdarwin/quackjvm-core/$VERSION"
  CORE_JAR="$HERE/target/central/quackjvm-core-$VERSION.jar"
  mkdir -p "$HERE/target/central"
  [ -f "$CORE_JAR" ] || curl -sSf -o "$CORE_JAR" "$BASE/quackjvm-core-$VERSION.jar"
  WANT=$(curl -sSf "$BASE/quackjvm-core-$VERSION.jar.sha1" | cut -c1-40)
  GOT=$(shasum "$CORE_JAR" | cut -c1-40)
  if [ "$WANT" != "$GOT" ]; then echo "checksum mismatch for $CORE_JAR"; rm -f "$CORE_JAR"; exit 1; fi
  SOURCE="from Maven Central, sha1 $GOT"
  LABEL=$VERSION
  # quackjvm-core's own dependencies come from its pom; the jar itself is the one just checked,
  # whatever ~/.m2 holds under that version.
  (cd "$HERE" && mvn -q dependency:build-classpath -Dquackjvm.version="$VERSION" -Dmdep.outputFile=target/deps.txt)
  DEPS=$(tr ':' '\n' < "$HERE/target/deps.txt" | grep -v '/quackjvm-core-' | paste -sd: -)
fi
CP="$HERE/target/classes:$CORE_JAR:$DEPS"
mkdir -p "$HERE/target/classes"
javac --release 17 -d "$HERE/target/classes" -cp "$CORE_JAR:$DEPS" "$HERE"/src/main/java/bench/*.java
DUCKDB_JDBC=$(echo "$DEPS" | tr ':' '\n' | sed -n 's:.*/duckdb_jdbc-\(.*\)\.jar:\1:p' | head -1)

STAMP=$(date +%Y-%m-%d)
OUT="$HERE/results/$LABEL-$STAMP.txt"
MD="$HERE/results/$LABEL-$STAMP.md"
: > "$OUT"
log "# quackjvm $VERSION ($SOURCE), duckdb_jdbc $DUCKDB_JDBC"
log "# $(date '+%Y-%m-%d %H:%M %Z'); $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m), $(sysctl -n hw.ncpu) cores, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB; $(java -version 2>&1 | head -1)"
log "# readings $N, probes $M, devices $DEVICES, runs $RUNS"

wait_quiet() {
  while true; do
    L=$(sysctl -n vm.loadavg | awk '{print $2}')
    if awk "BEGIN{exit !($L < $MAXLOAD)}"; then break; fi
    echo "  waiting: load average $L, want below $MAXLOAD"
    sleep 30
  done
  log "LOADAVG	$1	$(sysctl -n vm.loadavg | tr -d '{}' | awk '{print $1}')"
}

run_engine() {   # engine devices runs [JVM flags...]
  local engine=$1 d=$2 runs=$3; shift 3
  wait_quiet "$engine $d"
  java -Xmx12g --add-opens=java.base/java.nio=ALL-UNNAMED "$@" -cp "$CP" bench.Compare "$engine" "$N" "$M" "$d" "$runs" "$HERE/target" 2>&1 \
    | grep -v '^SLF4J\|^WARNING' | tee -a "$OUT"
}

qdb_pid() { lsof -tiTCP:9000 -sTCP:LISTEN 2>/dev/null || true; }

stop_questdb() {
  if [ -n "$(qdb_pid)" ]; then
    questdb stop -d "$QDB_ROOT" >/dev/null 2>&1 || true
    for _ in $(seq 60); do [ -z "$(qdb_pid)" ] && break; sleep 1; done
  fi
}
trap stop_questdb EXIT

for d in $DEVICES; do
  for engine in $ENGINES; do
    case $engine in
      duck|java) run_engine "$engine" "$d" "$RUNS" ;;
      cq)
        # CQEngine answers one probe at a time; a sample keeps the run to minutes. Its "latest" at
        # 100,000 devices had not finished after ten minutes, so it is skipped there.
        if [ "$d" -ge 100000 ]; then
          run_engine cq "$d" 3 -Dcq.probes=1000 -Dcq.skip=latest
        else
          run_engine cq "$d" 3 -Dcq.probes=10000
        fi ;;
      quest)
        if [ -n "$(qdb_pid)" ]; then echo "Something is already listening on port 9000; stop it first."; exit 1; fi
        rm -rf "$QDB_ROOT"; mkdir -p "$QDB_ROOT"
        questdb start -d "$QDB_ROOT" >/dev/null
        for _ in $(seq 60); do
          curl -sf "http://localhost:9000/exec?query=SELECT%201" >/dev/null 2>&1 && break; sleep 1
        done
        run_engine quest "$d" 5 -Dqdb.pid="$(qdb_pid)" -Dqdb.root="$QDB_ROOT"
        stop_questdb ;;
      *) echo "unknown engine $engine"; exit 2 ;;
    esac
  done
done

java -cp "$HERE/target/classes" bench.Report "$OUT" > "$MD"
echo
echo "Results: $OUT"
echo "Tables:  $MD"
