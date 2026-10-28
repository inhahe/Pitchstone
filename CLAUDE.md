# Pitchstone

- Architecture and features: `design.md`. Read it before a change whose effects leave the file
  you are editing.
- User-facing document: `README.md`. Known bugs and limits: `known-issues.md`.

## Version

The version is `versionName` in `app/build.gradle.kts`, with `versionCode` beside it.

- **Bump it whenever the app is rebuilt to be installed or released** (`install-on-phone.bat`,
  `release.bat`), choosing the part by what actually changed since the last version:
  - **patch** (1.2.3 → 1.2.4): bug fixes, refactors, a plain rebuild;
  - **minor** (1.2.3 → 1.3.0): new features, or noticeable changes to existing ones;
  - **major** (1.2.3 → 2.0.0): breaking changes, or a milestone overhaul (a redesigned UI, a
    substantially reworked feature set), even when nothing is actually incompatible.

  Builds only for testing (debug APKs for the emulator, the editor's test bundle) do not count.
- **Raise `versionCode` by 1 with every bump.** Android installs an update over an installed
  app only if its `versionCode` is not lower, and each released APK should be told apart.
- Commit the bump with the changes it carries, or on its own just before building.

## Releases

`release.bat` publishes the signed release APK (and R8's mapping file, to read that build's
crash traces) at github.com/inhahe/Pitchstone/releases, tagged `v<versionName>`, with notes
listing the commits since the previous release. It publishes nothing if that version is
already released, and refuses uncommitted changes, a commit GitHub does not have, and a
missing `keystore.properties` (an unsigned APK). `release.bat --dry-run` builds and shows the
notes without publishing. Releasing is the user's call, as pushing is: run it only when asked.
