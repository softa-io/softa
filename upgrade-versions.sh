#!/bin/bash

# Upgrade the version of all maven modules to the specified target version.
# Usage: ./upgrade-versions.sh <target-version>
# Example: ./upgrade-versions.sh 1.0.3

# Check if a new version number is provided as an argument
if [ -z "$1" ]; then
  read -p "Please enter the new version number: " NEW_VERSION
else
  NEW_VERSION=$1
fi

# Ensure the new version number is not empty
if [ -z "$NEW_VERSION" ]; then
  echo "No version number provided. Exiting."
  exit 1
fi

# Use regular expressions to check if the version number is valid
if [[ ! "$NEW_VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
  echo "Invalid version number. It should only contain numbers and dots. Such as 1.0.0"
  echo "Exiting."
  exit 1
fi

# versions:set walks the whole reactor: it sets the root version and the <parent>
# version of every module under framework, starters and apps, so no per-module pass is needed.
echo "Updating all module versions to $NEW_VERSION"
mvn versions:set -DnewVersion="$NEW_VERSION" -DgenerateBackupPoms=false
