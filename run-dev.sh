#!/usr/bin/env bash
# Launch RuneLite in developer mode with Dink Bingo side-loaded, without Gradle in the loop.
# Regenerate the cached classpath after changing dependencies:  ./gradlew -q printClasspath
set -euo pipefail
cd "$(dirname "$0")"

./gradlew -q classes testClasses

CP_FILE=build/dev-classpath.txt
if [[ ! -f $CP_FILE || build.gradle.kts -nt $CP_FILE ]]; then
  ./gradlew -q printClasspath | tail -1 > "$CP_FILE"
fi

exec java \
  -ea \
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED \
  -cp "$(cat "$CP_FILE")" \
  dinkbingo.BingoPluginTest \
  --developer-mode --debug "$@"
