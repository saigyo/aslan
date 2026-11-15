// compose.swift <region.png> <window.png> <out.png>
// Writes the region picture, kept only where the window picture is opaque. The region picture holds
// the window with its popups; the window picture gives the window's shape, so the pixels outside the
// rounded corners (whatever lies behind the window) become transparent.
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

func load(_ path: String) -> CGImage {
    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
        FileHandle.standardError.write("cannot read \(path)\n".data(using: .utf8)!); exit(1)
    }
    return image
}

let args = CommandLine.arguments
guard args.count == 4 else { FileHandle.standardError.write("usage: compose region window out\n".data(using: .utf8)!); exit(1) }
let region = load(args[1]), window = load(args[2])
guard region.width == window.width, region.height == window.height else {
    FileHandle.standardError.write("sizes differ: \(region.width)x\(region.height) and \(window.width)x\(window.height)\n".data(using: .utf8)!); exit(2)
}
let rect = CGRect(x: 0, y: 0, width: region.width, height: region.height)
guard let context = CGContext(data: nil, width: region.width, height: region.height, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { exit(1) }
context.draw(region, in: rect)
context.setBlendMode(.destinationIn)
context.draw(window, in: rect)
guard let result = context.makeImage(),
      let destination = CGImageDestinationCreateWithURL(URL(fileURLWithPath: args[3]) as CFURL, UTType.png.identifier as CFString, 1, nil) else { exit(1) }
CGImageDestinationAddImage(destination, result, nil)
if !CGImageDestinationFinalize(destination) { exit(1) }
