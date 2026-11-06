// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: Apache-2.0
//
// Draws the app icon: the Android app's network tree (one node above two,
// joined by a bracket) on its blue, in the macOS icon grid (an 824 pt
// rounded square inside 1024, with the system's soft shadow).
//
//   swift scripts/icon.swift <output.png> [size]

import AppKit

let args = CommandLine.arguments
let output = args.count > 1 ? args[1] : "AppIcon.png"
let size = args.count > 2 ? CGFloat(Double(args[2]) ?? 1024) : 1024
let scale = size / 1024

let image = NSImage(size: NSSize(width: size, height: size))
image.lockFocus()
let context = NSGraphicsContext.current!.cgContext
context.scaleBy(x: scale, y: scale)

// body with shadow
let body = CGRect(x: 100, y: 100, width: 824, height: 824)
let path = CGPath(roundedRect: body, cornerWidth: 185, cornerHeight: 185, transform: nil)
context.saveGState()
context.setShadow(offset: CGSize(width: 0, height: -10), blur: 28, color: NSColor.black.withAlphaComponent(0.28).cgColor)
context.addPath(path)
context.setFillColor(NSColor(srgbRed: 0.24, green: 0.68, blue: 0.85, alpha: 1).cgColor)
context.fillPath()
context.restoreGState()

// gradient: lighter at the top, as the Android blue under a light
context.saveGState()
context.addPath(path)
context.clip()
let colors = [
    NSColor(srgbRed: 0.36, green: 0.78, blue: 0.93, alpha: 1).cgColor,
    NSColor(srgbRed: 0.16, green: 0.57, blue: 0.78, alpha: 1).cgColor,
] as CFArray
let gradient = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: colors, locations: [0, 1])!
context.drawLinearGradient(gradient, start: CGPoint(x: 512, y: 924), end: CGPoint(x: 512, y: 100), options: [])
context.restoreGState()

// the tree, in white with a faint shadow
context.saveGState()
context.setShadow(offset: CGSize(width: 0, height: -6), blur: 14, color: NSColor.black.withAlphaComponent(0.18).cgColor)
context.setFillColor(NSColor.white.cgColor)
context.setStrokeColor(NSColor.white.cgColor)
context.setLineWidth(34)
context.setLineCap(.round)
context.setLineJoin(.round)
let top = CGPoint(x: 512, y: 640)
let left = CGPoint(x: 330, y: 360)
let right = CGPoint(x: 694, y: 360)
let bar: CGFloat = 500
context.move(to: CGPoint(x: top.x, y: top.y))
context.addLine(to: CGPoint(x: top.x, y: bar))
context.move(to: CGPoint(x: left.x, y: left.y))
context.addLine(to: CGPoint(x: left.x, y: bar))
context.addLine(to: CGPoint(x: right.x, y: bar))
context.addLine(to: CGPoint(x: right.x, y: right.y))
context.strokePath()
for (center, radius) in [(top, CGFloat(98)), (left, CGFloat(82)), (right, CGFloat(82))] {
    context.fillEllipse(in: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
}
context.restoreGState()
image.unlockFocus()

let rep = NSBitmapImageRep(data: image.tiffRepresentation!)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: output))
