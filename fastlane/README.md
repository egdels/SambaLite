fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android test

```sh
[bundle exec] fastlane android test
```

Runs all the tests

### android clean

```sh
[bundle exec] fastlane android clean
```

Clean

### android deploy

```sh
[bundle exec] fastlane android deploy
```

Deploy a new version to the Google Play

### android build_signed_bundle_apk

```sh
[bundle exec] fastlane android build_signed_bundle_apk
```

Builds a signed Android App Bundle (.aab) and APK for release

### android tag_and_push_bundle

```sh
[bundle exec] fastlane android tag_and_push_bundle
```

Tags the Android release and pushes the tag to the origin

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
