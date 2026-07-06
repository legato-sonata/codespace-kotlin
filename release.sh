#!/usr/bin/env sh

set -e

# Build release APK
./gradlew assembleRelease

# Create releases folder
mkdir -p releases

# Copy APK
cp app/build/outputs/apk/release/*.apk releases/

echo "✅ Release APK copied to releases/"
