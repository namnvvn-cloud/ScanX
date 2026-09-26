import Foundation
import Security

/// API key của người dùng (Claude, Gemini, Google Dịch) lưu trong Keychain — tương đương KeystoreCrypto
/// (AES-GCM trong AndroidKeyStore) bên Android: không lưu dạng chữ thường trong UserDefaults.
enum SecretStore {
    static let claudeKey = "claude_api_key"
    static let geminiKey = "gemini_api_key"
    static let googleTranslateKey = "google_translate_api_key"

    private static let service = "com.scanx.app.apikeys"

    static func get(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8),
              !value.isEmpty
        else { return nil }
        return value
    }

    static func set(_ account: String, _ value: String?) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func has(_ account: String) -> Bool { get(account) != nil }
}

extension AppSettings {
    static let claudeModelKey = "claude_model"
    static let geminiModelKey = "gemini_model"

    static var claudeModel: String {
        let v = UserDefaults.standard.string(forKey: claudeModelKey) ?? ""
        return v.isEmpty ? ClaudeClient.defaultModel : v
    }

    static var geminiModel: String {
        let v = UserDefaults.standard.string(forKey: geminiModelKey)?.trimmingCharacters(in: .whitespaces) ?? ""
        return v.isEmpty ? GeminiClient.defaultModel : v
    }
}
