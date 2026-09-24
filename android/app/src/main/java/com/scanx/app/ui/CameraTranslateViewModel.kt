package com.scanx.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanx.app.convert.GeminiTranslator
import com.scanx.app.convert.GoogleTranslateClient
import com.scanx.app.convert.MlKitTranslator
import com.scanx.app.convert.MultiScriptOcr
import com.scanx.app.convert.PhotoTranslateRenderer
import com.scanx.app.convert.PhotoTranslation
import com.scanx.app.convert.Translation
import com.scanx.app.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Chụp để dịch" (bản 1.0) — kiểu Google Dịch: ảnh (chụp hoặc chọn từ máy) → OCR đa ngôn ngữ trên máy
 * ([MultiScriptOcr]) → gom khối ([PhotoTranslation]) → dịch sang tiếng Việt → vẽ bản dịch đè lên ảnh
 * ([PhotoTranslateRenderer]).
 *
 * Máy dịch theo thứ tự ưu tiên (quyết định đã chốt với anh Nam): Google Cloud Translation (nếu có key)
 * → Gemini (nếu có key, miễn phí) → ML Kit offline. Máy trước lỗi (mất mạng, hết hạn mức, key sai) thì
 * tự chuyển máy sau và báo cho người dùng biết.
 */
class CameraTranslateViewModel(application: Application) : AndroidViewModel(application) {

    sealed class State {
        data object Camera : State()
        data class Working(val message: String) : State()
        class Result(
            val original: Bitmap,
            val translated: Bitmap,
            val pairs: List<Pair<String, String>>,
            val engineLabel: String,
            val notice: String?,
        ) : State()
        data class Error(val message: String) : State()
    }

    private val prefs = AppPreferences(application)
    private val _state = MutableStateFlow<State>(State.Camera)
    val state: StateFlow<State> = _state.asStateFlow()

    fun reset() {
        (_state.value as? State.Result)?.let { r ->
            if (r.translated !== r.original && !r.translated.isRecycled) r.translated.recycle()
            if (!r.original.isRecycled) r.original.recycle()
        }
        _state.value = State.Camera
    }

    /** Ảnh chụp từ camera (đã xoay đứng). ViewModel nhận quyền sở hữu [bitmap]. */
    fun translatePhoto(bitmap: Bitmap) {
        viewModelScope.launch { process(bitmap) }
    }

    /** Ảnh chọn từ thư viện. */
    fun translateUri(uri: Uri) {
        viewModelScope.launch {
            _state.value = State.Working("Đang mở ảnh…")
            val bmp = withContext(Dispatchers.IO) { runCatching { decodeUpright(uri, MAX_SIDE) }.getOrNull() }
            if (bmp == null) {
                _state.value = State.Error("Không đọc được ảnh đã chọn")
                return@launch
            }
            process(bmp)
        }
    }

    private suspend fun process(input: Bitmap) {
        try {
            val bmp = withContext(Dispatchers.Default) { downscale(input, MAX_SIDE) }
            _state.value = State.Working("Đang đọc chữ trên ảnh…")
            val lines = withContext(Dispatchers.Default) { MultiScriptOcr().use { it.recognize(bmp) } }
            val blocks = PhotoTranslation.groupBlocks(lines)
            val todo = blocks.withIndex().filter { PhotoTranslation.needsTranslation(it.value) }
            if (todo.isEmpty()) {
                _state.value = State.Result(bmp, bmp, emptyList(), "", if (blocks.isEmpty()) "Không tìm thấy chữ trên ảnh" else "Chữ trên ảnh đã là tiếng Việt")
                return
            }
            _state.value = State.Working("Đang dịch ${todo.size} đoạn…")
            val (translatedTexts, engine, notice) = withContext(Dispatchers.IO) { translateTexts(todo.map { it.value }) }
            val perBlock = arrayOfNulls<String>(blocks.size)
            todo.forEachIndexed { k, (i, _) -> perBlock[i] = translatedTexts.getOrNull(k) }
            _state.value = State.Working("Đang ghép bản dịch vào ảnh…")
            val rendered = withContext(Dispatchers.Default) { PhotoTranslateRenderer.render(bmp, blocks, perBlock.toList()) }
            val pairs = todo.mapIndexedNotNull { k, (_, b) -> translatedTexts.getOrNull(k)?.let { b.text to it } }
            _state.value = State.Result(bmp, rendered, pairs, engine, notice)
        } catch (e: Throwable) {
            _state.value = State.Error("Không dịch được ảnh: ${e.message}")
        }
    }

    /** Dịch lần lượt theo thứ tự ưu tiên; trả (bản dịch cùng thứ tự, tên máy dịch, ghi chú nếu phải đổi máy). */
    private suspend fun translateTexts(blocks: List<PhotoTranslation.TextBlock>): Triple<List<String?>, String, String?> {
        val errors = ArrayList<String>()
        val googleKey = prefs.googleTranslateKey
        if (googleKey.isNotBlank()) {
            try {
                val res = GoogleTranslateClient(googleKey).translate(blocks.map { it.text }, Translation.TARGET)
                return Triple(res.map { it.text }, "Google Dịch", null)
            } catch (e: Throwable) {
                errors.add(e.message ?: "Google Dịch lỗi")
            }
        }
        val items = blocks.mapIndexed { i, b -> Translation.Item("b$i", b.text, b.lang) }
        val geminiKey = prefs.geminiApiKey
        if (geminiKey.isNotBlank()) {
            try {
                val map = GeminiTranslator(geminiKey, prefs.geminiModel).translate(items, "ảnh chụp bằng điện thoại") { _, _ -> }
                if (map.isNotEmpty()) return Triple(items.map { map[it.id] }, "Gemini", errors.firstOrNull()?.let { "Đã chuyển sang Gemini: $it" })
            } catch (e: Throwable) {
                errors.add(e.message ?: "Gemini lỗi")
            }
        }
        val map = MlKitTranslator().translate(items, "") { _, _ -> }
        val note = when {
            errors.isNotEmpty() -> "Đã chuyển sang ML Kit offline: ${errors.last()}"
            googleKey.isBlank() && geminiKey.isBlank() -> "Chưa có API key Google Dịch/Gemini trong Cài đặt — đang dịch bằng ML Kit offline"
            else -> null
        }
        return Triple(items.map { map[it.id] }, "ML Kit (offline)", note)
    }

    private fun downscale(bmp: Bitmap, maxSide: Int): Bitmap {
        val k = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (k >= 1f) return bmp
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun decodeUpright(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    override fun onCleared() {
        super.onCleared()
        reset()
    }

    companion object {
        /** Cạnh dài tối đa khi OCR + vẽ — đủ nét cho chữ nhỏ, nhanh, không tốn RAM. */
        const val MAX_SIDE = 2400
    }
}
