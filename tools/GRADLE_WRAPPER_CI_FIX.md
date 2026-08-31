# Transiva Gradle Wrapper CI Fix

This project previously contained a custom/non-official `gradle-wrapper.jar` whose SHA-256 was not recognized by GitHub `setup-gradle`. A later CI workaround attempted to download `gradle-8.11.1-wrapper.jar` from an invalid URL and returned HTTP 404.

The new CI flow does not download a wrapper JAR manually:

1. Remove the legacy wrapper JAR before `setup-gradle` validation.
2. Install official Gradle 8.11.1 via `gradle/actions/setup-gradle`.
3. Run `gradle wrapper --gradle-version 8.11.1 --distribution-type bin`.
4. Verify generated `gradle-wrapper.jar` SHA-256:
   `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`
5. Pin Gradle 8.11.1 binary distribution SHA-256:
   `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`
6. Continue unit tests, lint, signed APK/AAB build, and AAB validation.

No Android Java/Kotlin source needs modification for this CI error.
