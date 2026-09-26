import Foundation

enum BackendAPIError: Error, LocalizedError {
    case http(Int, String)
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .http(let code, let message):
            return "Lỗi backend (\(code)): \(message)"
        case .invalidResponse:
            return "Phản hồi không hợp lệ từ backend"
        }
    }
}

/// Gọi thẳng backend NestJS đã deploy bằng URLSession (không dùng Alamofire/thư viện ngoài).
/// Cùng API, cùng endpoint với BackendApi.kt bên Android:
///   POST /auth/login, GET/POST /documents, PUT lên uploadUrl (presigned R2).
final class BackendAPI {
    static let baseURL = "https://scanx-450n.onrender.com"

    private let idToken: String
    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config)
    }()

    init(idToken: String) {
        self.idToken = idToken
    }

    @discardableResult
    func login() async throws -> [String: Any] {
        let data = try await send(method: "POST", path: "/auth/login", body: nil)
        return parseObject(data)
    }

    func listDocuments() async throws -> [[String: Any]] {
        let data = try await send(method: "GET", path: "/documents", body: nil)
        return (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] ?? []
    }

    func createDocument(title: String, pageCount: Int) async throws -> [String: Any] {
        let body: [String: Any] = ["title": title, "pageCount": pageCount, "mimeType": "application/pdf"]
        let data = try await send(method: "POST", path: "/documents", body: body)
        return parseObject(data)
    }

    func uploadFile(uploadURL: String, fileURL: URL) async throws {
        guard let url = URL(string: uploadURL) else { throw BackendAPIError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("application/pdf", forHTTPHeaderField: "Content-Type")
        let (_, response) = try await session.upload(for: request, fromFile: fileURL)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw BackendAPIError.http(code, "Upload thất bại")
        }
    }

    private func send(method: String, path: String, body: [String: Any]?) async throws -> Data {
        guard let url = URL(string: Self.baseURL + path) else { throw BackendAPIError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let body = body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw BackendAPIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8) ?? ""
            throw BackendAPIError.http(http.statusCode, message)
        }
        return data
    }

    private func parseObject(_ data: Data) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }
}
