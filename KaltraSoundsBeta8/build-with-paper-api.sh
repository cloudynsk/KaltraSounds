#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then
  echo "Usage: $0 /path/to/paper-api.jar" >&2
  exit 2
fi
API="$1"
rm -rf build/classes
mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
javac -Xlint:all --release 25 -cp "$API" -d build/classes @build/sources.txt
cp -a src/main/resources/. build/classes/
jar --create --file KaltraSounds.jar -C build/classes .
