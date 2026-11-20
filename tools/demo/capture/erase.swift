// erase.swift <in.png> <x> <y> <width> <height> <out.png>
// Paints over a rectangle (pixels, origin at the top left): each row of it takes the colour of the pixel just left of
// it, so that a control on a plain background disappears and a gradient from top to bottom is kept.
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let a = CommandLine.arguments
guard a.count == 7, let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: a[1]) as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
      let x = Int(a[2]), let y = Int(a[3]), let w = Int(a[4]), let h = Int(a[5]),
      x > 0, y >= 0, w > 0, h > 0, x + w <= image.width, y + h <= image.height else { exit(1) }
let space = image.colorSpace?.model == .rgb ? image.colorSpace! : CGColorSpace(name: CGColorSpace.sRGB)!
guard let context = CGContext(data: nil, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: 0,
                              space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
      let data = context.data else { exit(1) }
context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
let pixels = data.bindMemory(to: UInt32.self, capacity: context.bytesPerRow / 4 * image.height)
let stride = context.bytesPerRow / 4
// The context's memory has the top row first.
for row in y..<(y + h) {
    let colour = pixels[row * stride + x - 1]
    for column in x..<(x + w) { pixels[row * stride + column] = colour }
}
guard let result = context.makeImage(),
      let destination = CGImageDestinationCreateWithURL(URL(fileURLWithPath: a[6]) as CFURL, UTType.png.identifier as CFString, 1, nil)
else { exit(1) }
CGImageDestinationAddImage(destination, result, nil)
if !CGImageDestinationFinalize(destination) { exit(1) }
