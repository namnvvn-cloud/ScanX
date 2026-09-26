import Foundation

struct AIClientError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private func errorMessage(from data: Data) -> String {
    if let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
       let err = json["error"] as? [String: Any],
       let msg = err["message"] as? String {
        return msg
    }
    return String(String(data: data, encoding: .utf8)?.prefix(200) ?? "")
}

private func postJSON(
    url: URL,
    headers: [String: String],
    body: [String: Any],
    timeout: TimeInterval
) async throws -> (Data, Int) {
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.timeoutInterval = timeout
    request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "content-type")
    for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
    request.httpBody = try JSONSerialization.data(withJSONObject: body)
    let (data, response) = try await URLSession.shared.data(for: request)
    let code = (response as? HTTPURLResponse)?.statusCode ?? -1
    return (data, code)
}

/// Claude (Anthropic Messages API, có thị giác) — port từ Android CloudAiClient.kt. Ảnh/chữ gửi thẳng
/// từ máy tới api.anthropic.com qua HTTPS bằng API key của chính người dùng, không qua máy chủ trung gian.
struct ClaudeClient {
    static let endpoint = URL(string: "https://api.anthropic.com/v1/messages")!
    static let defaultModel = "claude-sonnet-5"
    static let models: [(id: String, title: String)] = [
        ("claude-sonnet-5", "Claude Sonnet 5 (khuyên dùng: nhanh, chi phí thấp)"),
        ("claude-opus-5", "Claude Opus 5 (chính xác nhất, chi phí cao hơn)"),
    ]

    let apiKey: String
    let model: String

    /// Yêu cầu chỉ có chữ (vd dịch).
    func complete(_ prompt: String, maxTokens: Int = 8192) async throws -> String {
        try await call([
            "model": model,
            "max_tokens": maxTokens,
            "messages": [["role": "user", "content": prompt]],
        ])
    }

    /// Đọc chữ NHIỀU trang trong 1 lần gọi (gộp trang như bản Android 0.7). jpegs = ảnh từng trang.
    func transcribeBatch(pages: [DocPage], jpegs: [Data]) async throws -> [DocPage] {
        guard pages.count == jpegs.count, !pages.isEmpty else { return pages }
        func imagePart(_ jpeg: Data) -> [String: Any] {
            [
                "type": "image",
                "source": ["type": "base64", "media_type": "image/jpeg", "data": jpeg.base64EncodedString()],
            ]
        }
        if pages.count == 1 {
            let content: [[String: Any]] = [imagePart(jpegs[0]), ["type": "text", "text": CloudTranscription.prompt(pages[0])]]
            let raw = try await call([
                "model": model,
                "max_tokens": 8192,
                "messages": [["role": "user", "content": content]],
            ])
            guard let json = DocTranslation.extractJSONObject(raw) else {
                throw AIClientError(message: "AI trả về dữ liệu không đúng định dạng")
            }
            let (answers, extras) = CloudTranscription.parseSlotsAndExtras(json)
            return [CloudTranscription.apply(pages[0], answers: answers, extras: extras)]
        }
        var content: [[String: Any]] = jpegs.map { imagePart($0) }
        content.append(["type": "text", "text": CloudTranscription.batchPrompt(pages)])
        let raw = try await call([
            "model": model,
            "max_tokens": min(6000 * pages.count, 24000),
            "messages": [["role": "user", "content": content]],
        ])
        guard let json = DocTranslation.extractJSONObject(raw), let pagesJson = json["pages"] as? [Any] else {
            throw AIClientError(message: "AI trả về dữ liệu không đúng định dạng (thiếu \"pages\")")
        }
        return pages.enumerated().map { i, page in
            guard i < pagesJson.count, let pj = pagesJson[i] as? [String: Any] else { return page }
            let (answers, extras) = CloudTranscription.parseSlotsAndExtras(pj)
            return CloudTranscription.apply(page, answers: answers, extras: extras)
        }
    }

    private func call(_ body: [String: Any]) async throws -> String {
        let (data, code) = try await postJSON(
            url: Self.endpoint,
            headers: ["x-api-key": apiKey, "anthropic-version": "2023-06-01"],
            body: body,
            timeout: 180
        )
        guard (200..<300).contains(code) else {
            let msg = errorMessage(from: data)
            switch code {
            case 401: throw AIClientError(message: "API key Claude không hợp lệ (\(msg))")
            case 429: throw AIClientError(message: "Vượt hạn mức gọi AI, thử lại sau (\(msg))")
            default: throw AIClientError(message: "Lỗi AI Cloud \(code): \(msg)")
            }
        }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let content = json["content"] as? [[String: Any]]
        else { return "" }
        return content.compactMap { $0["type"] as? String == "text" ? $0["text"] as? String : nil }.joined()
    }
}

/// Gemini (Google AI Studio, MIỄN PHÍ ở hạn mức cá nhân) — port từ Android GeminiAiClient.kt, dùng API
/// "Interactions" (POST /v1beta/interactions, header x-goog-api-key). Tự thử lại khi lỗi 429.
struct GeminiClient {
    static let endpoint = URL(string: "https://generativelanguage.googleapis.com/v1beta/interactions")!
    static let defaultModel = "gemini-2.5-flash"
    static let suggestedModels: [(id: String, title: String)] = [
        ("gemini-2.5-flash", "gemini-2.5-flash (ổn định, khuyên dùng)"),
        ("gemini-3.8-flash", "gemini-3.8-flash (mới nhất, có thể hạn mức khác)"),
    ]
    private static let retryDelaysSeconds: [UInt64] = [3, 8, 16]

    let apiKey: String
    let model: String

    func complete(_ prompt: String, maxTokens: Int = 8192) async throws -> String {
        let body: [String: Any] = [
            "model": model,
            "input": prompt,
            "generation_config": ["temperature": 0.2, "max_output_tokens": maxTokens],
        ]
        var attempt = 0
        while true {
            let (data, code) = try await postJSON(url: Self.endpoint, headers: ["x-goog-api-key": apiKey], body: body, timeout: 180)
            if code == 429 {
                if attempt < Self.retryDelaysSeconds.count {
                    try await Task.sleep(nanoseconds: Self.retryDelaysSeconds[attempt] * 1_000_000_000)
                    attempt += 1
                    continue
                }
                throw AIClientError(message: "Vượt hạn mức miễn phí Gemini, đã tự thử lại nhưng vẫn còn giới hạn (\(errorMessage(from: data)))")
            }
            guard (200..<300).contains(code) else {
                let msg = errorMessage(from: data)
                if code == 401 || code == 403 { throw AIClientError(message: "API key Gemini không hợp lệ (\(msg))") }
                throw AIClientError(message: "Lỗi Gemini \(code): \(msg)")
            }
            guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let steps = json["steps"] as? [[String: Any]]
            else { return "" }
            var text = ""
            for step in steps where step["type"] as? String == "model_output" {
                for part in step["content"] as? [[String: Any]] ?? [] where part["type"] as? String == "text" {
                    text += part["text"] as? String ?? ""
                }
            }
            return text
        }
    }
}

/// Google Cloud Translation v2 (máy dịch của Google Dịch) — port từ Android GoogleTranslateClient.kt.
struct GoogleTranslateClient {
    static let endpoint = "https://translation.googleapis.com/language/translate/v2"
    static let maxStrings = 100
    static let maxChars = 4500

    let apiKey: String

    /// Dịch theo lô; kết quả cùng thứ tự với texts.
    func translate(_ texts: [String], target: String, source: String? = nil) async throws -> [String] {
        var out: [String] = []
        for chunk in Self.chunkIndices(texts) {
            out += try await call(chunk.map { texts[$0] }, target: target, source: source)
        }
        return out
    }

    private func call(_ texts: [String], target: String, source: String?) async throws -> [String] {
        var body: [String: Any] = ["q": texts, "target": target, "format": "text"]
        if let source, !source.isEmpty { body["source"] = source }
        let key = apiKey.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? apiKey
        guard let url = URL(string: "\(Self.endpoint)?key=\(key)") else { throw AIClientError(message: "API key Google Dịch không hợp lệ") }
        let (data, code) = try await postJSON(url: url, headers: [:], body: body, timeout: 60)
        guard (200..<300).contains(code) else {
            let msg = errorMessage(from: data)
            switch code {
            case 400:
                throw AIClientError(message: msg.lowercased().contains("key") ? "API key Google Dịch không hợp lệ (\(msg))" : "Google Dịch từ chối yêu cầu (\(msg))")
            case 401, 403:
                throw AIClientError(message: "Google Dịch chưa sẵn sàng: kiểm tra đã bật Cloud Translation API và tài khoản thanh toán cho dự án chưa (\(msg))")
            case 429:
                throw AIClientError(message: "Vượt hạn mức Google Dịch, thử lại sau ít phút (\(msg))")
            default:
                throw AIClientError(message: "Lỗi Google Dịch \(code): \(msg)")
            }
        }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let dataObj = json["data"] as? [String: Any],
              let arr = dataObj["translations"] as? [[String: Any]]
        else { return texts.map { _ in "" } }
        return arr.map { ($0["translatedText"] as? String) ?? "" }
    }

    /// Lô ≤ maxStrings chuỗi và ≤ maxChars ký tự (chuỗi quá dài đi riêng) — chỉ số chuỗi, đúng thứ tự.
    static func chunkIndices(_ texts: [String], maxStrings: Int = GoogleTranslateClient.maxStrings, maxChars: Int = GoogleTranslateClient.maxChars) -> [[Int]] {
        var out: [[Int]] = []
        var cur: [Int] = []
        var chars = 0
        for (i, t) in texts.enumerated() {
            let len = t.count
            if !cur.isEmpty && (cur.count >= maxStrings || chars + len > maxChars) {
                out.append(cur)
                cur = []
                chars = 0
            }
            cur.append(i)
            chars += len
        }
        if !cur.isEmpty { out.append(cur) }
        return out
    }
}
