# CI/CD (GitHub Actions)

| Workflow | When | What |
|---|---|---|
| `.github/workflows/android-ci.yml` | push to `main` or `feature/**`, every pull request, manual | Python script tests; Gradle unit tests, lint, debug APK (with the whisper.cpp NDK build); uploads the APK and test/lint reports as artifacts |
| `.github/workflows/release.yml` | push a tag `v*` | Tests, builds, and publishes a GitHub Release with `g2glasses-<tag>-debug.apk` attached |
| `.github/dependabot.yml` | monthly | Update PRs for the Actions used and the Gradle dependencies |

Both workflows share `.github/actions/android-setup`: JDK 25, the SDK packages the app needs
(`platforms;android-37.0`, `build-tools;36.0.0`, `ndk;28.2.13676358`, `cmake;3.31.0`), Gradle
caching, and `scripts/fetch_models.sh` (Vosk model and sherpa-onnx AAR, which are not in git;
cached between runs). When `app/build.gradle.kts` changes its NDK, CMake or compile SDK, change the
list in that action too.

## Using it

- **Pull requests:** the *Android CI* check must be green before merging. Open the failed job;
  test and lint reports are in the run's **reports** artifact.
- **Getting a build without Android Studio:** every CI run has an **app-debug-apk** artifact
  (Actions → the run → Artifacts). Install with `adb install -r app-debug.apk`.
- **Releasing:** `git tag v0.3.0 && git push origin v0.3.0`. The release has generated notes and
  the APK.

## Notes

- `gradlew` is not executable in git (it was committed from Windows), so the workflows run
  `sh ./gradlew`. Making it executable (`git update-index --chmod=+x G2glasses/gradlew`) is also fine.
- The APK is debug-signed: there is no release keystore in the repository. For a signed release,
  add the keystore as repository secrets and a `signingConfigs` block; don't commit the keystore.
- Speech models (Parakeet, Whisper) are pushed to phones by the fetch scripts and never needed by
  CI; device performance checks (`scripts/validate_perf.py`) run against metrics from a phone, see
  `docs/TESTING.md`.
