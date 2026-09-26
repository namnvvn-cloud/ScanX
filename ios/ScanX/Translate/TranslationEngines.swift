import Foundation
#if canImport(Translation)
import Translation
#endif

/// Máy dịch sang tiếng Việt: nhận các đoạn, trả bản dịch theo mã đoạn.
protocol TranslationEngine {
    var label: String { get }
    func translate(
        _ items: [DocTranslation.Item],
        context: String,
        progress: @escaping (Int, Int) -> Void
    ) async throws -> [String: String]
}

/// Lựa chọn máy dịch — cùng danh sách với Android (GOOGLE, CLAUDE, GEMINI, MLKIT); bản iOS thay ML Kit
/// offline bằng máy dịch có sẵn của Apple (Translation framework, iOS 26+, cần tải gói ngôn ngữ).
enum TranslationChoice: String, CaseIterable, Identifiable {
    case google
    case gemini
    case claude
    case apple

    var id: String { rawValue }

    var title: String {
        switch self {
        case .google: return "Google Dịch (Cloud)"
        case .gemini: return "Gemini (miễn phí)"
        case .claude: return "Claude (chính xác nhất)"
        case .apple: return "Apple (offline, trên máy)"
        }
    }

    /// Đã cấu hình / dùng được trên máy này chưa.
    var isAvailable: Bool {
        switch self {
        case .google: return SecretStore.has(SecretStore.googleTranslateKey)
        case .gemini: return SecretStore.has(SecretStore.geminiKey)
        case .claude: return SecretStore.has(SecretStore.claudeKey)
        case .apple:
            if #available(iOS 26.0, *) { return true }
            return false
        }
    }

    var unavailableReason: String {
        switch self {
        case .google: return "Chưa nhập API key Google Dịch (Cài đặt)"
        case .gemini: return "Chưa nhập API key Gemini (Cài đặt)"
        case .claude: return "Chưa nhập API key Claude (Cài đặt)"
        case .apple: return "Cần iOS 26 trở lên"
        }
    }

    /// Mặc định giống Android: ưu tiên Google → Gemini → Claude → máy dịch trên máy.
    static var preferred: TranslationChoice {
        [.google, .gemini, .claude, .apple].first { $0.isAvailable } ?? .apple
    }

    func makeEngine() throws -> TranslationEngine {
        switch self {
        case .google:
            guard let key = SecretStore.get(SecretStore.googleTranslateKey) else { throw AIClientError(message: unavailableReason) }
            return GoogleCloudTranslator(client: GoogleTranslateClient(apiKey: key))
        case .gemini:
            guard let key = SecretStore.get(SecretStore.geminiKey) else { throw AIClientError(message: unavailableReason) }
            return LLMTranslator(label: "Gemini", complete: { prompt in
                try await GeminiClient(apiKey: key, model: AppSettings.geminiModel).complete(prompt, maxTokens: 16000)
            })
        case .claude:
            guard let key = SecretStore.get(SecretStore.claudeKey) else { throw AIClientError(message: unavailableReason) }
            return LLMTranslator(label: "Claude", complete: { prompt in
                try await ClaudeClient(apiKey: key, model: AppSettings.claudeModel).complete(prompt, maxTokens: 16000)
            })
        case .apple:
            #if canImport(Translation)
            if #available(iOS 26.0, *) { return AppleTranslator() }
            #endif
            throw AIClientError(message: unavailableReason)
        }
    }
}

/// Dịch bằng mô hình ngôn ngữ lớn (Claude/Gemini) theo lô ~6000 ký tự, trả JSON theo mã đoạn.
struct LLMTranslator: TranslationEngine {
    let label: String
    let complete: (String) async throws -> String

    func translate(_ items: [DocTranslation.Item], context: String, progress: @escaping (Int, Int) -> Void) async throws -> [String: String] {
        var out: [String: String] = [:]
        let batches = DocTranslation.batches(items)
        for (i, batch) in batches.enumerated() {
            progress(i + 1, batches.count)
            let raw = try await complete(DocTranslation.llmPrompt(batch, context: context))
            guard let json = DocTranslation.extractJSONObject(raw) else {
                throw AIClientError(message: "\(label) trả về bản dịch không đúng định dạng")
            }
            for it in batch {
                if let s = json[it.id] as? String, !s.trimmingCharacters(in: .whitespaces).isEmpty {
                    out[it.id] = s
                }
            }
        }
        return out
    }
}

/// Dịch bằng Google Cloud Translation — nhanh nhất, mỗi đoạn dịch độc lập.
struct GoogleCloudTranslator: TranslationEngine {
    let client: GoogleTranslateClient
    var label: String { "Google Dịch" }

    func translate(_ items: [DocTranslation.Item], context: String, progress: @escaping (Int, Int) -> Void) async throws -> [String: String] {
        var out: [String: String] = [:]
        let chunks = GoogleTranslateClient.chunkIndices(items.map { $0.text })
        for (ci, idx) in chunks.enumerated() {
            progress(ci + 1, chunks.count)
            let res = try await client.translate(idx.map { items[$0].text }, target: DocTranslation.target)
            for (k, i) in idx.enumerated() where k < res.count && !res[k].isEmpty {
                out[items[i].id] = res[k]
            }
        }
        return out
    }
}

#if canImport(Translation)
/// Dịch offline bằng máy dịch của Apple (tương đương ML Kit Translation bên Android). Mỗi đoạn dịch theo
/// ngôn ngữ nguồn của chính nó; gói ngôn ngữ phải được tải sẵn trong Cài đặt → Ứng dụng → Dịch thuật.
@available(iOS 26.0, *)
struct AppleTranslator: TranslationEngine {
    var label: String { "Apple (offline)" }

    func translate(_ items: [DocTranslation.Item], context: String, progress: @escaping (Int, Int) -> Void) async throws -> [String: String] {
        var out: [String: String] = [:]
        let groups = Dictionary(grouping: items) { $0.lang.isEmpty ? "en" : $0.lang }
        var done = 0
        for (source, group) in groups {
            if source == DocTranslation.target {
                done += group.count
                continue
            }
            let session = TranslationSession(
                installedSource: Locale.Language(identifier: source),
                target: Locale.Language(identifier: DocTranslation.target)
            )
            let requests = group.map { TranslationSession.Request(sourceText: $0.text, clientIdentifier: $0.id) }
            do {
                let responses = try await session.translations(from: requests)
                for r in responses {
                    if let id = r.clientIdentifier, !r.targetText.isEmpty { out[id] = r.targetText }
                }
            } catch {
                throw AIClientError(message: "Máy dịch Apple chưa dịch được \(Lang.displayName(source)) → Tiếng Việt: hãy tải gói ngôn ngữ trong Cài đặt → Ứng dụng → Dịch thuật (\(error.localizedDescription))")
            }
            done += group.count
            progress(done, items.count)
        }
        return out
    }
}
#endif
