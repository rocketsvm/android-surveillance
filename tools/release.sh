#!/bin/bash
# Build release APK and upload to GitHub release

set -e

cd "$(dirname "$0")/.."

# Build release APK
echo "Building release APK..."
JAVA_HOME=/home/rocket/.jdk/jdk-17.0.13+11 ANDROID_HOME=/home/rocket/.android tools/gw.sh :app:assembleRelease --no-daemon

# Get version from build.gradle.kts
VERSION=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)
APK_SRC=/home/rocket/.build/SurveillanceCamera/app/outputs/apk/release/app-release.apk
APK_DST="SurveillanceCamera-${VERSION}.apk"

# Rename APK
echo "Renaming APK to ${APK_DST}..."
cp "$APK_SRC" "$APK_DST"

# Create GitHub release
echo "Creating GitHub release v${VERSION}..."
if git tag -l "v${VERSION}" | grep -q "v${VERSION}"; then
    echo "Tag v${VERSION} already exists, deleting..."
    git push origin --delete "v${VERSION}"
    git tag -d "v${VERSION}"
    gh release delete "v${VERSION}" --yes
fi
git tag -a "v${VERSION}" -m "Release ${VERSION}"
git push origin "v${VERSION}"
gh release create "v${VERSION}" --title "Release ${VERSION}" --notes "Release ${VERSION}" "$APK_DST"

# Rename APK on GitHub
echo "Renaming APK on GitHub..."
ASSET_ID=$(gh api repos/rocketsvm/android-surveillance/releases/tags/"v${VERSION}" -q '.assets[].id')
gh api repos/rocketsvm/android-surveillance/releases/assets/$ASSET_ID -X PATCH -f name="$APK_DST"

# Clean up
rm "$APK_DST"

echo "Done! Release created at https://github.com/rocketsvm/android-surveillance/releases/tag/v${VERSION}"
