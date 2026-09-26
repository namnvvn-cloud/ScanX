import AVFoundation
import NaturalLanguage
import UIKit
import Vision
#if canImport(Translation)
import Translation
#endif

/// DỊCH TRỰC TIẾP khi soi camera — port từ Android convert/LiveTranslator.kt (kiểu "Tức thì" của Google Dịch):
/// mỗi khung phân tích (≥ 200 ms/khung) → OCR Vision trên máy → gom khối (PhotoTranslation) → dịch bằng máy
/// dịch OFFLINE của Apple (iOS 26+, thay ML Kit Translation) → phát Frame để lớp phủ vẽ bản dịch đè đúng chỗ.
///  - Bản dịch lưu đệm theo nội dung khối (LRU 500): chữ đứng yên chỉ dịch 1 lần, lớp phủ không nháy.
///  - Khối mới dịch nền (≤ 40 khối chờ), không chặn camera; chưa có bản dịch thì để nguyên chữ gốc.
///  - Vị trí khối cùng nội dung được làm mượt (trượt 60%) để bớt rung.
///  - Muốn chính xác hơn: bấm chụp → dịch online (Google Dịch / Gemini / Claude) trên ảnh độ phân giải cao.
final class LiveTranslator: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    enum Script: String, CaseIterable, Identifiable {
        case latin
        case chinese
        case japanese
        case korean

        var id: String { rawValue }

        var label: String {
            switch self {
            case .latin: return "Anh/Latin"
            case .chinese: return "中文"
            case .japanese: return "日本語"
            case .korean: return "한국어"
            }
        }

        var visionLanguages: [String] {
            switch self {
            case .latin: return ["en-US", "fr-FR", "de-DE", "es-ES", "it-IT", "pt-BR"]
            case .chinese: return ["zh-Hans", "zh-Hant"]
            case .japanese: return ["ja-JP"]
            case .korean: return ["ko-KR"]
            }
        }
    }

    struct LiveBlock {
        let block: PhotoTranslation.TextBlock
        let translation: String?
        let colors: PhotoTranslateRenderer.Colors
    }

    /// Kết quả 1 khung hình, toạ độ theo ảnh đứng width × height (cùng khung nhìn với preview).
    struct Frame {
        let width: Int
        let height: Int
        let blocks: [LiveBlock]
        let status: String?
    }

    /// Gọi trên luồng chính.
    var onFrame: ((Frame?) -> Void)?

    private static let minInterval: CFTimeInterval = 0.2
    private static let langCheckInterval: CFTimeInterval = 1.5
    private static let cacheSize = 500
    private static let maxPending = 40
    private static let maxSide: CGFloat = 1280

    private let lock = NSLock()
    private var _enabled = true
    private var _script: Script = .latin
    private var lastRun: CFTimeInterval = 0
    private var latinSource = "en"
    private var sourceCheckedAt: CFTimeInterval = 0
    private var prevBlocks: [PhotoTranslation.TextBlock] = []
    private var cache: [String: String] = [:]
    private var cacheOrder: [String] = []
    private var pending: Set<String> = []
    private var readyLanguages: Set<String> = []
    private var checkingLanguages: Set<String> = []
    private var modelStatus: String?
    private let ciContext = CIContext()
    private var appleService: AnyObject?

    override init() {
        super.init()
        #if canImport(Translation)
        if #available(iOS 26.0, *) {
            appleService = AppleLiveTranslationService()
        }
        #endif
        if appleService == nil {
            modelStatus = "Dịch trực tiếp cần iOS 26 trở lên (máy dịch offline của Apple). Bấm nút chụp để dịch online."
        }
    }

    var enabled: Bool {
        get { lock.withLock { _enabled } }
        set {
            lock.withLock { _enabled = newValue }
            if !newValue { publish(nil) }
        }
    }

    var script: Script {
        get { lock.withLock { _script } }
        set {
            lock.withLock {
                if _script != newValue {
                    _script = newValue
                    prevBlocks = []
                }
            }
            publish(nil)
        }
    }

    // MARK: - Khung hình camera (luồng video, nối tiếp; khung trễ bị bỏ)

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = CACurrentMediaTime()
        guard enabled, now - lastRun >= Self.minInterval else { return }
        lastRun = now
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        var image = CIImage(cvPixelBuffer: pixelBuffer)
        let longest = max(image.extent.width, image.extent.height)
        if longest > Self.maxSide {
            let k = Self.maxSide / longest
            image = image.transformed(by: CGAffineTransform(scaleX: k, y: k))
        }
        guard let cg = ciContext.createCGImage(image, from: image.extent) else { return }
        autoreleasepool { process(cg) }
    }

    private func process(_ cg: CGImage) {
        let sc = script
        let lines = recognize(cg, script: sc)
        guard enabled else { return }
        if lines.isEmpty {
            lock.withLock { prevBlocks = [] }
            publish(Frame(width: cg.width, height: cg.height, blocks: [], status: currentStatus() ?? "Hướng camera vào chữ cần dịch"))
            return
        }
        let source = sourceFor(sc, lines: lines)
        let tagged = lines.map { line -> OcrLine in
            var l = line
            l.lang = source
            return l
        }
        if source == DocTranslation.target {
            lock.withLock { prevBlocks = [] }
            publish(Frame(width: cg.width, height: cg.height, blocks: [], status: "Chữ trên camera đã là tiếng Việt"))
            return
        }
        let ready = ensureReady(source)
        let blocks = smooth(PhotoTranslation.groupBlocks(tagged).filter { PhotoTranslation.needsTranslation($0) })
        lock.withLock { prevBlocks = blocks }
        let pixels = ScanFilters.rgbxBuffer(from: cg)
        var out: [LiveBlock] = []
        for b in blocks {
            let key = "\(source)|\(b.text)"
            let tr = cached(key)
            if tr == nil && ready { enqueue(key: key, source: source, text: b.text) }
            let colors = pixels.map { PhotoTranslateRenderer.blockColors($0, b) }
                ?? PhotoTranslateRenderer.Colors(background: .white, ink: .black)
            out.append(LiveBlock(block: b, translation: tr, colors: colors))
        }
        publish(Frame(width: cg.width, height: cg.height, blocks: out, status: currentStatus()))
    }

    private func recognize(_ cg: CGImage, script: Script) -> [OcrLine] {
        let request = VNRecognizeTextRequest()
        // Latin: chế độ nhanh (đủ cho soi trực tiếp); Hán/Kana/Hangul chỉ có ở chế độ chính xác.
        request.recognitionLevel = script == .latin ? .fast : .accurate
        request.usesLanguageCorrection = script != .latin
        request.recognitionLanguages = script.visionLanguages
        let handler = VNImageRequestHandler(cgImage: cg, orientation: .up, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return []
        }
        let W = Double(cg.width)
        let H = Double(cg.height)
        func px(_ p: CGPoint) -> [Double] { [Double(p.x) * W, (1 - Double(p.y)) * H] }
        return (request.results ?? []).compactMap { observation in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            let text = candidate.string.trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty { return nil }
            let r = observation.boundingBox
            let box = Box(left: Double(r.minX) * W, top: (1 - Double(r.maxY)) * H, right: Double(r.maxX) * W, bottom: (1 - Double(r.minY)) * H)
            var line = OcrLine(text: text, box: box, words: [], confidence: Double(candidate.confidence))
            line.quad = px(observation.topLeft) + px(observation.topRight) + px(observation.bottomRight) + px(observation.bottomLeft)
            return line
        }
    }

    /// Ngôn ngữ nguồn: CJK theo hệ chữ đã chọn; Latin đoán bằng NaturalLanguage (1,5 s/lần, cả khung hình).
    private func sourceFor(_ script: Script, lines: [OcrLine]) -> String {
        switch script {
        case .chinese: return "zh"
        case .japanese: return "ja"
        case .korean: return "ko"
        case .latin:
            let now = CACurrentMediaTime()
            if now - sourceCheckedAt > Self.langCheckInterval {
                let joined = String(lines.map { $0.text }.joined(separator: " ").prefix(600))
                if joined.filter({ $0.isLetter }).count >= 12 {
                    sourceCheckedAt = now
                    let recognizer = NLLanguageRecognizer()
                    recognizer.processString(joined)
                    if let lang = recognizer.dominantLanguage, lang != .undetermined {
                        latinSource = String(lang.rawValue.prefix(2))
                    }
                }
            }
            return latinSource
        }
    }

    /// Cùng nội dung với khối ở khung trước → trượt 60% về vị trí mới; đổi chỗ xa thì nhảy luôn.
    private func smooth(_ blocks: [PhotoTranslation.TextBlock]) -> [PhotoTranslation.TextBlock] {
        let previous = lock.withLock { prevBlocks }
        if previous.isEmpty { return blocks }
        var byText: [String: PhotoTranslation.TextBlock] = [:]
        for p in previous { byText[p.text] = p }
        return blocks.map { b in
            guard let p = byText[b.text] else { return b }
            let lim = 2 * max(b.lineHeight, 1)
            if abs(p.box.cx - b.box.cx) > lim || abs(p.box.cy - b.box.cy) > lim || abs(p.angle - b.angle) > 3 { return b }
            func mix(_ a: Double, _ n: Double) -> Double { a + (n - a) * 0.6 }
            var out = b
            out.box = Box(
                left: mix(p.box.left, b.box.left), top: mix(p.box.top, b.box.top),
                right: mix(p.box.right, b.box.right), bottom: mix(p.box.bottom, b.box.bottom)
            )
            out.lineHeight = mix(p.lineHeight, b.lineHeight)
            out.angle = mix(p.angle, b.angle)
            return out
        }
    }

    // MARK: - Máy dịch offline (Apple)

    private func currentStatus() -> String? {
        lock.withLock { modelStatus }
    }

    /// Gói dịch source → Việt đã có trên máy chưa; chưa thì kiểm tra nền và báo trạng thái.
    private func ensureReady(_ source: String) -> Bool {
        #if canImport(Translation)
        if #available(iOS 26.0, *) {
            let (ready, shouldCheck) = lock.withLock { () -> (Bool, Bool) in
                if readyLanguages.contains(source) { return (true, false) }
                if checkingLanguages.contains(source) { return (false, false) }
                checkingLanguages.insert(source)
                return (false, true)
            }
            if ready { return true }
            if shouldCheck {
                let name = Lang.displayName(source)
                Task {
                    let status = await LanguageAvailability().status(
                        from: Locale.Language(identifier: source),
                        to: Locale.Language(identifier: DocTranslation.target)
                    )
                    self.lock.withLock {
                        switch status {
                        case .installed:
                            self.readyLanguages.insert(source)
                            self.modelStatus = nil
                        case .supported:
                            self.modelStatus = "Chưa có gói dịch \(name) → Tiếng Việt trên máy: tải trong Cài đặt → Ứng dụng → Dịch thuật."
                        case .unsupported:
                            self.modelStatus = "Máy dịch Apple chưa hỗ trợ \(name) → Tiếng Việt."
                        @unknown default:
                            self.modelStatus = nil
                        }
                        self.checkingLanguages.remove(source)
                    }
                }
            }
            return false
        }
        #endif
        return false
    }

    private func cached(_ key: String) -> String? {
        lock.withLock { cache[key] }
    }

    private func store(_ key: String, _ value: String) {
        lock.withLock {
            if cache[key] == nil {
                cacheOrder.append(key)
                if cacheOrder.count > Self.cacheSize {
                    let evicted = cacheOrder.removeFirst()
                    cache.removeValue(forKey: evicted)
                }
            }
            cache[key] = value
        }
    }

    private func enqueue(key: String, source: String, text: String) {
        let accepted = lock.withLock { () -> Bool in
            if pending.count >= Self.maxPending || pending.contains(key) { return false }
            pending.insert(key)
            return true
        }
        guard accepted else { return }
        #if canImport(Translation)
        if #available(iOS 26.0, *), let service = appleService as? AppleLiveTranslationService {
            Task {
                if let out = try? await service.translate(text, source: source),
                   !out.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    self.store(key, out)
                }
                _ = self.lock.withLock { self.pending.remove(key) }
            }
            return
        }
        #endif
        _ = lock.withLock { pending.remove(key) }
    }

    private func publish(_ frame: Frame?) {
        DispatchQueue.main.async { [weak self] in
            self?.onFrame?(frame)
        }
    }
}

#if canImport(Translation)
/// Giữ phiên dịch theo từng ngôn ngữ nguồn (tạo 1 lần, dùng lại cho mọi khung hình).
@available(iOS 26.0, *)
actor AppleLiveTranslationService {
    private var sessions: [String: TranslationSession] = [:]

    func translate(_ text: String, source: String) async throws -> String {
        let session: TranslationSession
        if let existing = sessions[source] {
            session = existing
        } else {
            session = TranslationSession(
                installedSource: Locale.Language(identifier: source),
                target: Locale.Language(identifier: DocTranslation.target)
            )
            sessions[source] = session
        }
        var parts: [String] = []
        for part in text.components(separatedBy: "\n") {
            let trimmed = part.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty {
                parts.append(part)
            } else {
                parts.append(try await session.translate(trimmed).targetText)
            }
        }
        return parts.joined(separator: "\n")
    }
}
#endif
