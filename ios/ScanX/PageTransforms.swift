import UIKit
import CoreImage
import Vision

/// Khung cắt 4 góc, toạ độ chuẩn hoá 0…1, gốc trên-trái.
struct Quad: Equatable {
    var topLeft: CGPoint
    var topRight: CGPoint
    var bottomRight: CGPoint
    var bottomLeft: CGPoint

    static let full = Quad(
        topLeft: CGPoint(x: 0, y: 0),
        topRight: CGPoint(x: 1, y: 0),
        bottomRight: CGPoint(x: 1, y: 1),
        bottomLeft: CGPoint(x: 0, y: 1)
    )

    subscript(corner: Int) -> CGPoint {
        get {
            switch corner {
            case 0: return topLeft
            case 1: return topRight
            case 2: return bottomRight
            default: return bottomLeft
            }
        }
        set {
            switch corner {
            case 0: topLeft = newValue
            case 1: topRight = newValue
            case 2: bottomRight = newValue
            default: bottomLeft = newValue
            }
        }
    }
}

/// Cắt/xoay trên ảnh gốc (tương đương scan/PageCropRotate.kt): xoay bội 90°, chỉnh nghiêng tự do
/// (nền trắng), cắt 4 góc có nắn phối cảnh (CIPerspectiveCorrection), tự dò khung bằng Vision
/// (VNDetectDocumentSegmentationRequest — thay AI 4 góc DocAligner bên Android).
enum PageTransforms {
    static let context = CIContext()

    static func loadMaster(_ url: URL) -> CIImage? {
        CIImage(contentsOf: url, options: [.applyOrientationProperty: true])
    }

    static func rotate(_ image: CIImage, quarterTurns: Int, tiltDegrees: Double) -> CIImage {
        var out = image
        let turns = ((quarterTurns % 4) + 4) % 4
        if turns != 0 {
            let orientations: [CGImagePropertyOrientation] = [.up, .right, .down, .left]
            out = out.oriented(orientations[turns])
        }
        if abs(tiltDegrees) > 0.01 {
            let radians = CGFloat(-tiltDegrees * .pi / 180)
            let rotated = out.transformed(by: CGAffineTransform(rotationAngle: radians))
            let white = CIImage(color: CIColor.white).cropped(to: rotated.extent)
            out = rotated.composited(over: white)
        }
        out = out.transformed(by: CGAffineTransform(translationX: -out.extent.minX, y: -out.extent.minY))
        return out.cropped(to: CGRect(x: 0, y: 0, width: out.extent.width.rounded(.down), height: out.extent.height.rounded(.down)))
    }

    static func crop(_ image: CIImage, quad: Quad) -> CIImage {
        if quad == .full { return image }
        let extent = image.extent
        func vector(_ p: CGPoint) -> CIVector {
            CIVector(x: extent.minX + p.x * extent.width, y: extent.minY + (1 - p.y) * extent.height)
        }
        guard let filter = CIFilter(name: "CIPerspectiveCorrection") else { return image }
        filter.setValue(image, forKey: kCIInputImageKey)
        filter.setValue(vector(quad.topLeft), forKey: "inputTopLeft")
        filter.setValue(vector(quad.topRight), forKey: "inputTopRight")
        filter.setValue(vector(quad.bottomRight), forKey: "inputBottomRight")
        filter.setValue(vector(quad.bottomLeft), forKey: "inputBottomLeft")
        guard let output = filter.outputImage else { return image }
        return output.transformed(by: CGAffineTransform(translationX: -output.extent.minX, y: -output.extent.minY))
    }

    static func apply(to image: CIImage, quarterTurns: Int, tiltDegrees: Double, quad: Quad) -> CIImage {
        crop(rotate(image, quarterTurns: quarterTurns, tiltDegrees: tiltDegrees), quad: quad)
    }

    static func render(_ image: CIImage) -> CGImage? {
        let extent = image.extent
        guard extent.width >= 1, extent.height >= 1, extent.width.isFinite, extent.height.isFinite else { return nil }
        return context.createCGImage(image, from: extent)
    }

    static func detectQuad(in image: CGImage) -> Quad? {
        let request = VNDetectDocumentSegmentationRequest()
        let handler = VNImageRequestHandler(cgImage: image, orientation: .up, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return nil
        }
        guard let observation = request.results?.first else { return nil }
        func flip(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x, y: 1 - p.y) }
        return Quad(
            topLeft: flip(observation.topLeft),
            topRight: flip(observation.topRight),
            bottomRight: flip(observation.bottomRight),
            bottomLeft: flip(observation.bottomLeft)
        )
    }
}
