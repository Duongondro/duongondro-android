// Writes core/src/test/resources/qr-cases.json: the modules iOS's DuongondroQR
// draws for a set of texts, each checked by CoreImage's QR detector first, so
// the Kotlin port can be held to the same output without a decoder on the JVM.
//
//   swiftc -O -o /tmp/qr-cases ../duongondro-ios/Core/Sources/DuongondroQR/*.swift Scripts/qr-cases/main.swift
//   /tmp/qr-cases > core/src/test/resources/qr-cases.json
import CoreGraphics
import CoreImage
import Foundation

let texts = [
    "HTTPS://DUONGONDRO.APP/I/7K2MQ9XA#H4N8R2CJ6TPW3ZQF",
    "HTTPS://DUONGONDRO.APP/F/7K2MQ9XA#H4N8R2CJ6TPW3ZQF",
    "https://duongondro.app/i/7k2mq9xa#h4n8r2cj6tpw3zqf",
    "hello, world",
    "Duongöndro: 你好",
    "0123456789012345678901234567890123456789",
    "AB1CD",
    "K3$ 9:Z/.-+*%QW0E2R7T5Y8U1I4O6PASDFGHJKLZXCVBNM 0123456789A",
    String(repeating: "abcdefghij klmnop ", count: 6),
    String(repeating: "Practice makes the mind calm. ", count: 3),
]

func decode(_ qr: QRCode) -> String? {
    let scale = 8, quiet = 4
    let side = (qr.size + 2 * quiet) * scale
    let ctx = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    ctx.setFillColor(gray: 1, alpha: 1)
    ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
    ctx.setFillColor(gray: 0, alpha: 1)
    for y in 0..<qr.size {
        for x in 0..<qr.size where qr[x, y] {
            ctx.fill(CGRect(x: (x + quiet) * scale, y: (qr.size - 1 - y + quiet) * scale, width: scale, height: scale))
        }
    }
    let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])!
    return (detector.features(in: CIImage(cgImage: ctx.makeImage()!)).first as? CIQRCodeFeature)?.messageString
}

let names: [ECC: String] = [.low: "LOW", .medium: "MEDIUM", .quality: "QUALITY", .high: "HIGH"]
var cases: [[String: Any]] = []
func add(_ text: String, _ ecc: ECC, _ minVersion: Int) {
    let qr = try! QRCode.encode(text, ecc: ecc, minVersion: minVersion)
    guard decode(qr) == text else { fatalError("CoreImage did not read back \(text) at \(ecc)") }
    let rows = (0..<qr.size).map { y in String((0..<qr.size).map { x in qr[x, y] ? "1" : "0" }) }
    cases.append(["text": text, "ecc": names[ecc]!, "minVersion": minVersion, "version": qr.version, "mask": qr.mask, "rows": rows])
}
for text in texts { for ecc in ECC.allCases { add(text, ecc, 1) } }
for version in 1...QRCode.maxVersion { add("HTTPS://DUONGONDRO.APP", .low, version) }
let data = try! JSONSerialization.data(withJSONObject: cases, options: [.prettyPrinted, .sortedKeys])
print(String(decoding: data, as: UTF8.self))
