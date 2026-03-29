#!/bin/bash
set -e

echo "Building Go tunnel for Android with 16KB page size support..."

cd "$(dirname "$0")/../tunnel"

mkdir -p ../app/libs

gomobile bind -target=android -androidapi 24 -trimpath -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -o ../app/libs/tunnel.aar .

echo "Build complete! The AAR and JAR have been saved to app/libs/"