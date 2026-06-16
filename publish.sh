#!/bin/bash

# ─────────────────────────────────────────────
#  AdsManager — One-Command Publisher
#  Usage: ./publish.sh
#  Optional: ./publish.sh "Your commit message"
# ─────────────────────────────────────────────

set -e

GRADLE_FILE="adsManager/build.gradle.kts"
JAVA_HOME_PATH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# ── Step 1: Read current version ──────────────
CURRENT_VERSION=$(grep -oE 'version = "[0-9]+\.[0-9]+\.[0-9]+"' "$GRADLE_FILE" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')

if [ -z "$CURRENT_VERSION" ]; then
  echo "❌ Could not find version in $GRADLE_FILE"
  exit 1
fi

echo "📦 Current version: $CURRENT_VERSION"

# ── Step 2: Bump patch version ─────────────────
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"
PATCH=$((PATCH + 1))
NEW_VERSION="$MAJOR.$MINOR.$PATCH"

echo "🔼 New version:     $NEW_VERSION"

# ── Step 3: Update version in build.gradle.kts ─
sed -i '' "s/version = \"$CURRENT_VERSION\"/version = \"$NEW_VERSION\"/" "$GRADLE_FILE"

echo "✅ Version updated in $GRADLE_FILE"

# ── Step 4: Git commit & push ──────────────────
COMMIT_MSG="${1:-"Release version $NEW_VERSION"}"

git add "$GRADLE_FILE"
git commit -m "$COMMIT_MSG"
git push origin master

echo "✅ Pushed to GitHub (master)"

# ── Step 5: Publish to GitHub Packages ─────────
echo "🚀 Publishing to GitHub Packages..."

JAVA_HOME="$JAVA_HOME_PATH" ./gradlew :adsManager:publishReleasePublicationToMavenRepository --no-daemon

echo ""
echo "────────────────────────────────────────────"
echo "🎉 Successfully published version $NEW_VERSION"
echo "   com.umer_tf.ads:ads:$NEW_VERSION"
echo "   https://github.com/umerrjaved1/AdsManager/packages"
echo "────────────────────────────────────────────"
