#!/bin/bash
set -e

echo "Building Go tunnel for Android with 16KB page size support..."

cd "$(dirname "$0")/.."
OUT="$PWD/build/tunnel/tunnel.aar"
mkdir -p "$(dirname "$OUT")"
cd tunnel

export PATH="$PATH:$HOME/go/bin:$(go env GOPATH)/bin"
export GOFLAGS="-buildvcs=false"
gomobile bind -target=android -androidapi 24 -trimpath -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -o "$OUT" github.com/nqmgaming/blockads-tunnel

echo "Build complete! The AAR and JAR have been saved to build/tunnel/"
echo "Build with it using: ./gradlew -Ptunnel.source=local assembleDebug"
