# Quy tắc ProGuard/R8 mặc định cho ScanX.
# ML Kit cần giữ lại các class model để tránh crash khi minify bản release.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_document_scanner.** { *; }
-dontwarn com.google.mlkit.**
