
# Concept for the Android App "SambaLite"

## 1. Overview

**SambaLite** is a lightweight, modern, and open-source Android client for SMB/CIFS shares (Samba). The goal is to provide a minimalist, reliable, and secure tool that allows users to access SMB shares on their local network—without any frills, ads, or unnecessary features.  
The app is aimed at tech-savvy users seeking a slim, transparent solution who value privacy and open source.

---

## 2. Project Goals and Philosophy

- **Minimalism:** Only essential features, no unnecessary options or configuration.
- **User-friendliness:** Clear, tidy interface, intuitive to use, minimalist UI.
- **Modernity:** Use of up-to-date libraries and Android development best practices (Java, Gradle, Dagger, Material Design).
- **Privacy:** No telemetry, no tracking, secure storage of sensitive data.
- **Maintainability:** Clean, well-documented codebase that is easy to extend and maintain.

---

## 3. Target Audience

- Tech-savvy private users who want to access Samba shares at home or in a small network.
- IT admins and power users who need a lightweight solution without cloud dependency.
- Open-source enthusiasts.

---

## 4. Feature Set (MVP)

### 4.1. Manage Connections

- Enter SMB server (hostname or IP) and share/path
- Username, password, optional domain (workgroup)
- Manage multiple connections, each with a customizable alias
- Connection test (with clear error output)
- Automatically and securely store credentials (Android Keystore)

### 4.2. File Browser

- Navigate the share directory tree (folders, subfolders)
- Display: name, type (icon), size, modification date, optionally a file symbol (depending on type)
- Refresh view ("pull-to-refresh")
- Sorting options (name, date, size)

### 4.3. File Operations

- Download files (storage via Storage Access Framework)
- Upload files (file picker via Storage Access Framework)
- Delete files/folders (with confirmation dialog)
- Rename files/folders
- Open files with external app (ACTION_VIEW intent)
- Show file properties (name, size, type, path, modification date)

### 4.4. Settings

- Language: system-dependent (no internal language management)
- Dark/Light mode (system default)
- Option to delete all saved connections and credentials

### 4.5. Security

- No telemetry or third-party services
- No unencrypted storage of sensitive data
- Minimal permissions (only network and access to user-selected storage locations)
- Warning about public/sensitive networks on first launch

---

## 5. Technical Architecture

### 5.1. Language & Build System

- **Java 11+** (for language features, streams, better maintainability)
- **Gradle** (latest Android plugin)

### 5.2. Architecture

- **MVVM** pattern (Model-View-ViewModel), ViewModel possible in Java
- **Dagger 2** for dependency injection
- **Repository pattern** to separate UI, logic, and networking
- **SMBJ** as SMB client library (Apache 2.0, SMB2/3 support)

### 5.3. Main Components

- **Activities/Fragments:** Login, connection overview, file browser
- **ViewModels:** Connection management, file operations
- **Repository:** Abstraction for SMB operations (connect, list, file ops)
- **Utils:** Helper methods, error handling, storage management
- **Dagger modules/components:** Providing dependencies

### 5.4. Data Storage

- Credentials encrypted (Android Keystore + EncryptedSharedPreferences)
- Local storage of recent connections (only metadata, no plaintext passwords)
- No local file database, instead on-demand listing from server

---

## 6. User Interface (UI/UX)

- Material Design (modern, clean)
- **Start Activity:** List of saved connections, button to add new
- **Login/Connection dialog:** Server, share, user, password, optional domain, "Test" button, save option
- **File browser:** Navigable like a classic file explorer, icons for folders/file types, context menu for operations
- **Operations:** Confirmation dialogs for critical actions, snackbar for success/error
- **Feedback:** Errors are displayed clearly and understandably (network, authentication, etc.)
- **Settings:** Minimal, reset option

---

## 7. Security & Privacy

- Store passwords **only** encrypted (Keystore, no plaintext files!)
- No telemetry or analytics
- All network operations in the background (AsyncTask/ExecutorService or Android architecture components)
- Clean up temporary files after download/upload
- Clear warnings about risks in insecure networks (e.g., Wi-Fi)

---

## 8. Extensibility (Future Plans/Features)

- Favorites (folders/files)
- Thumbnails/previews for common file types
- Batch operations (multi-select)
- Progress indicators for upload/download
- Support for additional SMB features (e.g., guest access, anonymous browsing)
- Tablet-optimized layout

---

## 9. Documentation & GitHub Standards

- **README.md:** Feature overview, build instructions, security notes, license
- **CONTRIBUTING.md:** Contribution guidelines, coding style
- **LICENSE:** Apache 2.0 (recommended)
- **CHANGELOG.md:** Overview of changes
- **Issue tracker:** Open for feature requests, bug reports

---

## 10. Sample User Stories for Implementation

- **As a user**, I want to create a new SMB connection and securely save my credentials so I can quickly access my network shares.
- **As a user**, I want to navigate my shares in a clear file browser to easily find specific files.
- **As a user**, I want to download individual files, store them on my Android device, and open them with other apps.
- **As a user**, I want to upload files to the share to easily transfer data between PC and smartphone.
- **As a user**, I don't want to worry about my data—SambaLite stores nothing unencrypted and collects no usage data.

---

## 11. Example Package Structure

```
com.sambalite
│
├── di                // Dagger modules, components
├── data              // Model classes, SMB repository
├── ui                // Activities, fragments, adapters, viewmodels
├── util              // Helpers, logging, storage utils
├── SambaLiteApp.java // Application class for DI
```

---

## 12. Risks and Challenges

- **SMBJ integration:** Ensure SMB2/3 works reliably and efficiently on Android.
- **Android Storage Access Framework:** Integrate properly to remain compatible with recent Android versions.
- **Network timeouts/error handling:** Robust error messages and retry mechanisms.
- **Security:** Keystore integration must be consistent and well implemented.

---

## 13. Community and Collaboration Goals

- The goal is to motivate other developers to contribute (hence, clear README and issues)
- Potentially include translations, accessibility, and feature ideas from the community

---

## 14. Sample Feature Table (README)

| Feature                | Status (MVP) | Description                                  |
|------------------------|--------------|----------------------------------------------|
| SMB/share connection   | ✔            | Connect with user/password and domain        |
| Browse files           | ✔            | Navigate, expand/collapse folders            |
| Download/Upload        | ✔            | Securely copy files between device and share |
| Delete/Rename          | ✔            | Basic file ops with confirmation dialogs     |
| Modern UI              | ✔            | Material Design, Dark Mode support           |
| Multiple connections   | ✔            | Manage multiple shares with alias            |
| Security/Privacy       | ✔            | Encrypted storage, no telemetry              |

---

# Conclusion / Summary

**SambaLite** will be the open-source Samba client for Android that many have wished for years:  
Modern, lightweight, secure, without unnecessary features, but with everything needed for practical SMB share access.  
Thanks to Java, Gradle, and Dagger, the code is easy to understand, modular, and optimally prepared for further automation and AI-driven development.
