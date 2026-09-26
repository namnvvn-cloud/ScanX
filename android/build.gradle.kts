// Root build file. Không cần khai báo dependency ở đây — plugin version quản lý tập trung bên dưới.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    // Đọc google-services.json, sinh cấu hình Firebase lúc build (Bước "Đăng nhập" — Firebase Auth).
    id("com.google.gms.google-services") version "4.4.2" apply false
}
