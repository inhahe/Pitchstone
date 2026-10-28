# Pitchstone

- Architecture and features: `design.md`. Read it before a change whose effects leave the file
  you are editing.
- User-facing document: `README.md`. Known bugs and limits: `known-issues.md`.

## Version

The version is `appVersion` in `gradle.properties`, major.minor.patch. `app/build.gradle.kts`
makes it the `versionName` and derives `versionCode` from it (major×10000 + minor×100 +
patch), so minor and patch stay below 100.

- **Bump it whenever the app is rebuilt to be installed or released** (`install-on-phone.bat`,
  `release.bat`), choosing the part by what actually changed since the last version:
  - **patch** (1.2.3 → 1.2.4): bug fixes, refactors, a plain rebuild;
  - **minor** (1.2.3 → 1.3.0): new features, or noticeable changes to existing ones;
  - **major** (1.2.3 → 2.0.0): breaking changes, or a milestone overhaul (a redesigned UI, a
    substantially reworked feature set), even when nothing is actually incompatible.

  Builds only for testing (debug APKs for the emulator, the editor's test bundle) do not count.
- Commit the bump with the changes it carries, or on its own just before building.

## Releases

`release.bat` publishes the signed release APK (and R8's mapping file, to read that build's
crash traces) at github.com/inhahe/Pitchstone/releases, tagged `v<appVersion>`, with notes
listing the commits since the previous release, once the editor's and the app's tests pass.
It publishes nothing for a version already released (or whose tag is left), or not above the
last release, and refuses a branch other than main, uncommitted changes, a build input not in
git, a commit GitHub does not have, and a missing `keystore.properties` (an unsigned APK).
`release.bat --dry-run` does all but publish. Both it and `install-on-phone.bat` build with
`build-release.bat`. Releasing is the user's call, as pushing is: run it only when asked.
