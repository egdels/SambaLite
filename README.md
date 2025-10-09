# SambaLite

*Dedicated to my wife Eva, who inspired this project with her need to organize her files.*

SambaLite is a lightweight, modern, and open-source Android client for SMB/CIFS shares (Samba). It provides a minimalistic, reliable, and secure tool for accessing SMB shares on local networks without unnecessary features, ads, or bloat.

**Note:** SambaLite is an independent open-source project and is not affiliated with the official [Samba Project](https://www.samba.org/) or SerNet.  
The name refers solely to the supported SMB/CIFS network protocols.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/de.schliweb.sambalite/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=de.schliweb.sambalite)

Or download the latest APK from the [Releases Section](https://github.com/egdels/SambaLite/releases/latest).

## Features

| Feature                | Status | Description                                     |
| ---------------------- | ------ | ----------------------------------------------- |
| SMB/Share Connection   | ✅     | Connect with username/password and domain       |
| File Browsing          | ✅     | Navigate through folders and files              |
| Download/Upload        | ✅     | Transfer files between device and share         |
| Delete/Rename          | ✅     | Basic file operations with confirmation         |
| Search with Wildcards  | ✅     | Find files using * and ? wildcards              |
| Modern UI              | ✅     | Material Design with Dark Mode support          |
| Multiple Connections   | ✅     | Manage multiple shares with custom names        |
| Security/Privacy       | ✅     | Encrypted credential storage, no telemetry      |

## Project Goals

- **Minimalism:** Only essential features, no unnecessary configurations
- **User-Friendly:** Clean, intuitive interface with minimal UI
- **Modern:** Using current Android libraries and best practices
- **Privacy-Focused:** No telemetry, tracking, or unnecessary permissions
- **Maintainable:** Clean, documented code that's easy to extend

## Technical Details

- **Language:** Java 11+
- **Architecture:** MVVM with Repository pattern
- **Dependencies:**
  - AndroidX and Material Design components
  - Dagger 2 for dependency injection
  - SMBJ for SMB client functionality
  - EncryptedSharedPreferences for secure credential storage

## Building the Project

1. Clone the repository
2. Open the project in Android Studio
3. Build and run the app on your device or emulator

## Security Notes

- Credentials are stored securely using Android's Keystore system
- No unencrypted storage of sensitive data
- Minimal permissions required (only network and user-selected storage access)
- Be cautious when using SMB in public or untrusted networks

## ❤️ Support this project
SambaLite is free and open source.
If you find it useful, please consider supporting development:

[![Ko-fi](https://img.shields.io/badge/Buy%20me%20a%20coffee-Ko--fi-orange)](https://ko-fi.com/egdels)
[![PayPal](https://img.shields.io/badge/Donate-PayPal-blue)](https://www.paypal.com/paypalme/egdels)

## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

## Third-Party Libraries

This project uses the SMBJ library (com.hierynomus:smbj), version 0.14.0, for SMB/CIFS client functionality.

SMBJ is licensed under the Apache License, Version 2.0.
For more information, see: https://github.com/hierynomus/smbj

Copyright (c) 2016-2024 Michael N. Heronimus

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## Disclaimer / Limitation of Liability

This software is provided "as is", without warranty of any kind, express or implied, including but not limited to the warranties of merchantability, fitness for a particular purpose, and noninfringement. In no event shall the authors or copyright holders be liable for any claim, damages, or other liability, whether in an action of contract, tort, or otherwise, arising from, out of, or in connection with the software or the use or other dealings in the software.

Where not permitted by applicable law (e.g. in cases of gross negligence or intent), this limitation of liability may not apply. Users utilize this software at their own risk.

## Privacy Policy

Our privacy policy is available on our [GitHub Pages site](https://egdels.github.io/SambaLite/privacy_policy.html).

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request
