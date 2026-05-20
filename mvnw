#!/usr/bin/env sh
set -eu

BASEDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MAVEN_VERSION="3.9.9"
WRAPPER_DIR="$BASEDIR/.mvn/wrapper"
DIST_DIR="$WRAPPER_DIR/apache-maven-$MAVEN_VERSION"
MVN="$DIST_DIR/bin/mvn"
ZIP="$WRAPPER_DIR/apache-maven-$MAVEN_VERSION-bin.zip"
URL="https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.zip"

if [ ! -x "$MVN" ]; then
  mkdir -p "$WRAPPER_DIR"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "$URL" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget -q "$URL" -O "$ZIP"
    else
      echo "curl or wget is required to bootstrap Maven $MAVEN_VERSION" >&2
      exit 1
    fi
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -oq "$ZIP" -d "$WRAPPER_DIR"
  else
    echo "unzip is required to bootstrap Maven $MAVEN_VERSION" >&2
    exit 1
  fi
fi

exec "$MVN" -Dmaven.repo.local="$BASEDIR/.mvn/repository" "$@"
