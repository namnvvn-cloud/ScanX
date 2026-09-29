# Quy tắc ProGuard/R8 mặc định cho ScanX.
# ML Kit cần giữ lại các class model để tránh crash khi minify bản release.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_document_scanner.** { *; }
-dontwarn com.google.mlkit.**

# OpenCV: lớp Java được thư viện native gọi ngược (JNI) — giữ nguyên tên.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
# Google Play services (ML Kit qua Play services, Code Scanner, Module Install).
-dontwarn com.google.android.gms.**
# Giữ số dòng trong stack trace để đọc lỗi crash.
-keepattributes SourceFile,LineNumberTable
# Annotation chỉ dùng lúc biên dịch (thư viện Google/Guava tham chiếu) — không có trong APK.
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
