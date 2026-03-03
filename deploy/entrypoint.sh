#!/bin/sh
set -eu

# Now execute the main command of the container
exec java -jar "${APP_NAME}.jar" "$@"