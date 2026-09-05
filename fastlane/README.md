# Fastlane metadata (F-Droid)

This directory contains **store metadata only** — the Fastlane-standard
`metadata/android/<locale>/` layout that the F-Droid build server reads
directly (`full_description`, `short_description`, app icon). There is no
`Fastfile`, `Appfile`, or CI wiring, and none is needed for F-Droid.

Keep this directory in sync with the app (name, short/long description,
icon); do not delete it — it is the F-Droid listing, not dead code.
