import UIKit
import Vision

/// 1 dòng chữ OCR, toạ độ chuẩn hoá 0…1, gốc trên-trái (giống text_layers.json bên Android).
struct TextLine: Codable, Hashable {
    let text: String
    let x: Double
    let y: Double
    let w: Double
    let h: Double
}

/// OCR trên máy bằng Apple Vision (thay ML Kit Text Recognition bên Android).
enum PageOCR {
    private static let preferredPrefixes = ["vi", "en", "ko", "ja", "zh-Hans"]

    /// Chạy đồng bộ — gọi từ luồng nền.
    static func recognize(url: URL) -> [TextLine] {
        guard let cgImage = ImageLoader.downsampled(at: url, maxPixel: 2400)?.cgImage else { return [] }
        if let lines = try? perform(cgImage: cgImage, languages: preferredLanguages()) {
            return lines
        }
        // Một số tổ hợp ngôn ngữ Vision không cho chạy chung → thử lại với mặc định.
        return (try? perform(cgImage: cgImage, languages: nil)) ?? []
    }

    static func preferredLanguages() -> [String] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        let supported = (try? request.supportedRecognitionLanguages()) ?? []
        var result: [String] = []
        for prefix in preferredPrefixes {
            if let match = supported.first(where: { $0.hasPrefix(prefix) }), !result.contains(match) {
                result.append(match)
            }
        }
        return result
    }

    private static func perform(cgImage: CGImage, languages: [String]?) throws -> [TextLine] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.automaticallyDetectsLanguage = true
        if let languages, !languages.isEmpty {
            request.recognitionLanguages = languages
        }
        let handler = VNImageRequestHandler(cgImage: cgImage, orientation: .up, options: [:])
        try handler.perform([request])
        let observations = request.results ?? []
        return observations.compactMap { observation in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            let text = candidate.string.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            let box = observation.boundingBox
            return TextLine(
                text: text,
                x: Double(box.minX),
                y: Double(1 - box.maxY),
                w: Double(box.width),
                h: Double(box.height)
            )
        }
    }
}
