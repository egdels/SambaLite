// swift-tools-version: 6.0
// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0

import PackageDescription

let package = Package(
    name: "SambaLite",
    platforms: [.macOS(.v12)],
    products: [
        .library(name: "SambaLiteCore", targets: ["SambaLiteCore"]),
        .executable(name: "SambaLite", targets: ["SambaLite"]),
        .executable(name: "smb-probe", targets: ["smb-probe"]),
    ],
    dependencies: [
        // SMB2/3 client (libsmb2 underneath), LGPL-2.1-or-later, vendored
        // with a crash fix (Vendor/AMSMB2/README-SambaLite.md). Its
        // product is a dynamic library, which scripts/bundle.sh puts into
        // Contents/Frameworks, so it stays replaceable as the LGPL asks.
        .package(path: "Vendor/AMSMB2"),
    ],
    targets: [
        .target(
            name: "SambaLiteCore",
            dependencies: [.product(name: "AMSMB2", package: "AMSMB2")]
        ),
        // The app: a SwiftUI executable, wrapped into SambaLite.app by
        // scripts/bundle.sh.
        .executableTarget(name: "SambaLite", dependencies: ["SambaLiteCore"]),
        // Developer tool, see Sources/smb-probe/main.swift.
        .executableTarget(name: "smb-probe", dependencies: ["SambaLiteCore"]),
        .testTarget(name: "SambaLiteCoreTests", dependencies: ["SambaLiteCore"]),
    ]
)
