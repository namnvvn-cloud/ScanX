package com.scanx.app.util

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Chuyển một Google Play Services Task<T> (dùng trong ML Kit) thành suspend function,
 * viết tay để không cần thêm dependency kotlinx-coroutines-play-services (giảm rủi ro
 * lệch version khi build lần đầu chưa có mạng để Gradle thử đồng bộ).
 */
suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { exception -> cont.resumeWithException(exception) }
    addOnCanceledListener { cont.cancel() }
}
