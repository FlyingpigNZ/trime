# Automated Application Testing (outside unit tests)

Unit tests cover the pure JVM seams. For the Android application itself we need
a second layer that runs on an emulator/device.

## Recommended layers

1. **CLI definition validation (already in repo)**
   - `script/validate-definitions.py --check-shipped`
   - Run in CI on every PR; catches invalid YAML/references without an emulator.

2. **Instrumentation smoke tests** (`androidTest`)
   - Launch the main activity and verify it does not crash.
   - Start the IME service and verify the input view is created.
   - Load each shipped theme and verify no exception is thrown.
   - Install a minimal schema-layout package and verify it appears in the
     schema list / active theme.
   - Switch day/night and verify `ColorManager` resolves colors.

3. **Emulator/device runner**
   - Use Gradle Managed Devices or a GitHub Action such as
     `reactivecircus/android-emulator-runner`.
   - Command: `./gradlew :app:connectedDebugAndroidTest`
   - Requires a system image with the same API level as `compileSdk` (36).

4. **Optional UI/screenshot tests**
   - Compose or Robolectric screenshots are not currently in the project.
   - If visual regression matters later, add Paparazzi (JVM screenshots) or
     `androidx.test` screenshots on an emulator.

## Current instrumentation test

`app/src/androidTest/java/com/osfans/trime/SmokeTest.kt`:

- launches `MainActivity` with `ActivityScenario`
- asserts the activity is non-null
- asserts the default theme loads with keyboards and color schemes

Run with:

```bash
./gradlew :app:connectedDebugAndroidTest
```

For the IME, use `UiAutomation` or a test-only `InputMethodService` binding to
verify `TrimeInputMethodService.onCreateInputView()` returns non-null.

## CI sketch

```yaml
jobs:
  unit-and-validator:
    runs-on: ubuntu-latest
    steps:
      - run: ./gradlew :app:testDebugUnitTest
      - run: python3 script/validate-definitions.py --check-shipped

  instrumentation:
    runs-on: ubuntu-latest
    steps:
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 36
          script: ./gradlew :app:connectedDebugAndroidTest
```

## Open decisions

- Whether to add `androidx.test.ext:junit` and `androidx.test:runner` as
  `androidTestImplementation` dependencies.
- Which emulator API levels to cover (36 only vs 26+ matrix).
- Whether to run the native librime build in CI or use prebuilt artifacts.
