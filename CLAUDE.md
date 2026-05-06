# Haval Climate Control

## Versioning rule
Before every commit+push, increment the version in `app/build.gradle.kts`:
- `versionCode` → +1 (integer, always increments by 1)
- `versionName` → semver: patch for fixes, minor for new features, major for breaking changes

The `versionName` is what the update button in `MainActivity.kt` compares against GitHub Releases tags to decide whether to offer a download. Tags in GitHub releases must match `versionName` exactly (e.g. tag `v1.0.1` for `versionName = "1.0.1"`).
