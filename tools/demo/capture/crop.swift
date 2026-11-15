// crop.swift <in.png> <x> <y> <width> <height> <out.png> [maxWidth]
// Cuts a rectangle (pixels, origin at the top left) out of a picture; scales it down to maxWidth if it is wider.
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let a = CommandLine.arguments
guard a.count >= 7, let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: a[1]) as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
      let x = Int(a[2]), let y = Int(a[3]), let w = Int(a[4]), let h = Int(a[5]),
      var cut = image.cropping(to: CGRect(x: x, y: y, width: w, height: h)) else { exit(1) }
if a.count > 7, let maxWidth = Int(a[7]), cut.width > maxWidth {
    let height = Int((Double(cut.height) * Double(maxWidth) / Double(cut.width)).rounded())
    guard let context = CGContext(data: nil, width: maxWidth, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { exit(1) }
    context.interpolationQuality = .high
    context.draw(cut, in: CGRect(x: 0, y: 0, width: maxWidth, height: height))
    guard let scaled = context.makeImage() else { exit(1) }
    cut = scaled
}
guard let destination = CGImageDestinationCreateWithURL(URL(fileURLWithPath: a[6]) as CFURL, UTType.png.identifier as CFString, 1, nil) else { exit(1) }
CGImageDestinationAddImage(destination, cut, nil)
if !CGImageDestinationFinalize(destination) { exit(1) }
