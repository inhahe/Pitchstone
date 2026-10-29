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

## F-Droid

The app is prepared for F-Droid (not yet submitted):

- **Store listing:** `fastlane/metadata/android/en-US/` (title, short and full description, one
  changelog per `versionCode`). F-Droid reads it from this repository. When you bump the version,
  add `changelogs/<versionCode>.txt` (max 500 characters). When the README's features change
  noticeably, update `full_description.txt` to match (max 4000 characters; plain text).
- **Build recipe:** `metadata/app.pitchstone.yml` is the draft for F-Droid's fdroiddata
  repository, written for 1.0.2. Before submitting, point its `Builds` entry and
  `CurrentVersion` / `CurrentVersionCode` at the first release that carries these changes.
  It must stay exactly as `fdroid rewritemeta` writes it (no comments, categories sorted),
  or fdroiddata's CI rejects it; explain things here instead. It installs npm (`sudo`)
  because the Gradle build compiles the editor with `npm ci` and esbuild.
- **F-Droid cannot detect new releases by itself yet:** its update checker reads a version
  code only as a literal number from a file, and the versionCode here is computed from
  `appVersion` (its `VercodeOperation` only does arithmetic on a number it has read). Until
  a literal versionCode exists somewhere (for example in `gradle.properties`, with the build
  checking it matches `appVersion`), F-Droid's maintainers would update the recipe by hand.
- **Build settings F-Droid needs**, in `app/build.gradle.kts`: `dependenciesInfo` off (no
  Google-encrypted dependency block) and `vcsInfo.include = false` for release builds. Keep them.
- F-Droid builds the release APK unsigned and signs it with its own key, unless the build is
  made reproducible and the recipe names this app's signing key
  (https://f-droid.org/docs/Reproducible_Builds/).
