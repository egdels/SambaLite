# Fastlane Setup for SambaLite

This directory contains the Fastlane configuration for automating the build and release process of SambaLite.

## Prerequisites

To use Fastlane, you need to have the following installed:

- Ruby (version 2.5 or higher)
- Bundler (`gem install bundler`)
- Fastlane (`gem install fastlane` or via Bundler)

## Available Lanes

The following lanes are available:

- `fastlane android test`: Runs all the tests
- `fastlane android build_debug`: Builds a debug APK
- `fastlane android build_release`: Builds a release APK
- `fastlane android beta`: Submits a new Beta Build to Play Store Beta
- `fastlane android deploy`: Deploys a new version to the Google Play Store

## Setup for Google Play Store Deployment

To set up deployment to the Google Play Store:

1. Create a service account in the Google Play Console
2. Download the JSON key file
3. Update the `json_key_file` path in the Appfile to point to your JSON key file
4. Make sure your app is properly set up in the Google Play Console

## Metadata

The `metadata` directory contains the app store metadata in different languages:

- `de-DE`: German metadata
- `en-US`: English metadata

Each language directory contains:

- `title.txt`: The app's title
- `short_description.txt`: A short description of the app (up to 80 characters)
- `full_description.txt`: A full description of the app (up to 4000 characters)
- `changelogs/`: Directory for version-specific changelogs

## Screenshots

The `screenshots` directory contains placeholder README files for app store screenshots. The directory structure is organized by locale and device type:

```
screenshots/
├── de-DE/                 # German screenshots
│   ├── phone/             # Phone screenshots (min 320px wide)
│   ├── sevenInch/         # 7-inch tablet screenshots (min 600px wide)
│   └── tenInch/           # 10-inch tablet screenshots (min 1280px wide)
└── en-US/                 # English screenshots
    ├── phone/             # Phone screenshots (min 320px wide)
    ├── sevenInch/         # 7-inch tablet screenshots (min 600px wide)
    └── tenInch/           # 10-inch tablet screenshots (min 1280px wide)
```

Each directory contains a README.txt file with specific requirements for that device type and locale, including:
- Minimum and recommended resolutions
- File format requirements
- Naming conventions
- Suggested screenshot content
- Guidelines for ensuring screenshots are appropriate and effective

To add screenshots:
1. Navigate to the appropriate directory based on locale and device type
2. Read the README.txt file for specific requirements
3. Add your screenshots following the naming convention (screenshot_1.png, screenshot_2.png, etc.)

You can also use Fastlane's screenshot tools to automate screenshot capture. See the [Fastlane screenshots documentation](https://docs.fastlane.tools/getting-started/android/screenshots/) for more information.

## Further Documentation

For more information about Fastlane, visit the [Fastlane documentation](https://docs.fastlane.tools/).
