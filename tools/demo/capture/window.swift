// Prints the on-screen windows of the application named by the first argument (default "Main"):
// one line per window with its window number and its bounds in points: "<id> <x> <y> <width> <height>".
// Reads only owner name, layer, number and bounds; no window title and no pixels.
import CoreGraphics
import Foundation

let owner = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "Main"
let options: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
guard let list = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] else { exit(1) }
for window in list {
    guard let name = window[kCGWindowOwnerName as String] as? String, name == owner,
          let layer = window[kCGWindowLayer as String] as? Int, layer == 0,
          let number = window[kCGWindowNumber as String] as? Int,
          let bounds = window[kCGWindowBounds as String] as? [String: Any],
          let x = bounds["X"] as? Double, let y = bounds["Y"] as? Double,
          let w = bounds["Width"] as? Double, let h = bounds["Height"] as? Double else { continue }
    print("\(number) \(Int(x)) \(Int(y)) \(Int(w)) \(Int(h))")
}
