#!/bin/sh
cd "$(dirname "$0")"
JAR=$(ls -1 ai-apm-web-*.jar 2>/dev/null | sort -V | tail -n1)
if [ -z "$JAR" ]; then
  echo "no ai-apm-web jar found in $(pwd)" >&2
  exit 1
fi
exec java ${JAVA_TOOL_OPTIONS} -jar "./$JAR" --spring.config.additional-location=file:./application.yml
