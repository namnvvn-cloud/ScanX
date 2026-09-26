import UIKit

/// "Chụp để dịch" — port từ Android CameraTranslateViewModel.kt: ảnh (chụp hoặc chọn) → OCR trên máy (Vision)
/// → gom khối → dịch sang tiếng Việt → vẽ bản dịch đè. Máy dịch theo thứ tự ưu tiên: Google Dịch → Gemini →
/// Claude → Apple offline; máy trước lỗi thì tự chuyển máy sau và báo lý do.
@MainActor
final class PhotoTranslateModel: ObservableObject {
    struct Result {
        let original: UIImage
        let translated: UIImage
        let pairs: [(original: String, translated: String)]
        let engineLabel: String
        let notice: String?
    }

    enum State {
        case idle
        case working(String)
        case result(Result)
        case failure(String)
    }

    @Published private(set) var state: State = .idle

    /// Cạnh dài tối đa khi OCR + vẽ — đủ nét cho chữ nhỏ, nhanh, không tốn RAM (như Android).
    static let maxSide: CGFloat = 2400

    func reset() {
        state = .idle
    }

    func process(_ image: UIImage) {
        state = .working("Đang đọc chữ trên ảnh…")
        Task {
            do {
                let result = try await Self.run(image) { message in
                    Task { @MainActor in self.state = .working(message) }
                }
                state = .result(result)
            } catch {
                state = .failure("Không dịch được ảnh: \(error.localizedDescription)")
            }
        }
    }

    private static func run(_ image: UIImage, status: @escaping (String) -> Void) async throws -> Result {
        guard let cg = uprightImage(image, maxSide: maxSide) else {
            throw AIClientError(message: "Không đọc được ảnh")
        }
        let original = UIImage(cgImage: cg)
        let blocks = await Task.detached(priority: .userInitiated) {
            PhotoTranslation.groupBlocks(PageLayoutExtractor.ocrLines(cg))
        }.value
        let todo = blocks.enumerated().filter { PhotoTranslation.needsTranslation($0.element) }
        if todo.isEmpty {
            return Result(
                original: original,
                translated: original,
                pairs: [],
                engineLabel: "",
                notice: blocks.isEmpty ? "Không tìm thấy chữ trên ảnh" : "Chữ trên ảnh đã là tiếng Việt"
            )
        }
        status("Đang dịch \(todo.count) đoạn…")
        // Dịch TỪNG ĐOẠN (tách theo xuống dòng trong khối) rồi ghép lại — giữ đúng dòng của danh sách/hội thoại.
        let texts = todo.map { $0.element.text }
        let (segments, counts) = PhotoTranslation.splitSegments(texts)
        var langs: [String] = []
        for (k, item) in todo.enumerated() {
            langs += Array(repeating: item.element.lang, count: counts[k])
        }
        let (segTr, engine, notice) = try await translateTexts(segments, langs: langs)
        let translatedTexts = PhotoTranslation.joinSegments(segTr, counts: counts, originals: texts)
        var perBlock = [String?](repeating: nil, count: blocks.count)
        for (k, item) in todo.enumerated() where k < translatedTexts.count {
            perBlock[item.offset] = translatedTexts[k]
        }
        status("Đang ghép bản dịch vào ảnh…")
        let rendered = await Task.detached(priority: .userInitiated) {
            PhotoTranslateRenderer.render(cg, blocks: blocks, translations: perBlock)
        }.value
        var pairs: [(original: String, translated: String)] = []
        for (k, item) in todo.enumerated() {
            if k < translatedTexts.count, let t = translatedTexts[k] {
                pairs.append((item.element.text, t))
            }
        }
        return Result(original: original, translated: rendered ?? original, pairs: pairs, engineLabel: engine, notice: notice)
    }

    /// Dịch lần lượt theo thứ tự ưu tiên; trả (bản dịch cùng thứ tự, tên máy dịch, ghi chú nếu phải đổi máy).
    private static func translateTexts(_ texts: [String], langs: [String]) async throws -> ([String?], String, String?) {
        var errors: [String] = []
        if let key = SecretStore.get(SecretStore.googleTranslateKey) {
            do {
                let res = try await GoogleTranslateClient(apiKey: key).translate(texts, target: DocTranslation.target)
                return (res.map { $0.isEmpty ? nil : $0 }, "Google Dịch", nil)
            } catch {
                errors.append(error.localizedDescription)
            }
        }
        let items = texts.enumerated().map { i, t in
            DocTranslation.Item(id: "b\(i)", text: t, lang: i < langs.count ? langs[i] : "")
        }
        for choice in [TranslationChoice.gemini, .claude, .apple] where choice.isAvailable {
            do {
                let engine = try choice.makeEngine()
                let map = try await engine.translate(items, context: "ảnh chụp bằng điện thoại") { _, _ in }
                if !map.isEmpty {
                    let note = errors.last.map { "Đã chuyển sang \(engine.label): \($0)" }
                    return (items.map { map[$0.id] }, engine.label, note)
                }
            } catch {
                errors.append(error.localizedDescription)
            }
        }
        if let last = errors.last { throw AIClientError(message: last) }
        throw AIClientError(message: "Chưa có máy dịch: nhập API key Google Dịch hoặc Gemini (miễn phí) trong Cài đặt, hoặc dùng iOS 26 để dịch offline.")
    }

    /// Ảnh đúng chiều (theo EXIF/orientation), cạnh dài ≤ maxSide.
    static func uprightImage(_ image: UIImage, maxSide: CGFloat) -> CGImage? {
        let longest = max(image.size.width, image.size.height)
        guard longest > 0 else { return nil }
        let scale = min(1, maxSide / longest)
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }.cgImage
    }
}
