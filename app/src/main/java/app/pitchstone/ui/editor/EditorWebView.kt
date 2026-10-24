package app.pitchstone.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import android.webkit.MimeTypeMap
import app.pitchstone.BuildConfig
import app.pitchstone.container
import app.pitchstone.data.AttachmentStore
import java.io.FileInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The WebView that hosts the editor page (editor/, bundled into the app's assets).
 *
 * The page is served from https://appassets.androidplatform.net by [WebViewAssetLoader], with
 * no file access. It never navigates: any attempt goes to [onExternalLink] instead. Messages
 * from the page arrive on the main thread through [onMessage].
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class EditorWebView(
    context: Context,
    private val onMessage: (JsonObject) -> Unit,
    private val onExternalLink: (Uri) -> Unit,
    /** The page's renderer died (out of memory, crash); the view must be replaced. */
    private val onRendererGone: () -> Unit,
) : WebView(context) {
    companion object {
        const val PAGE = "https://appassets.androidplatform.net/assets/editor/index.html"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val main = Handler(Looper.getMainLooper())
    private val assets = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        // The images notes show: /attachments/<name> (editor/src/editor/attachments.ts).
        .addPathHandler("/attachments/", AttachmentPathHandler(context.container.attachments))
        .build()

    /** Serves stored attachments by name (case and folders ignored, as notes may write them). */
    private class AttachmentPathHandler(private val store: AttachmentStore) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse {
            val file = store.file(Uri.decode(path))
                ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), null)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
            return WebResourceResponse(mime, null, FileInputStream(file))
        }
    }

    /** Called by the page (on a background thread). */
    inner class Bridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            val parsed = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
            main.post { onMessage(parsed) }
        }
    }

    init {
        // Debug builds can be inspected with chrome://inspect (and driven by tests over CDP).
        if (BuildConfig.DEBUG) setWebContentsDebuggingEnabled(true)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.textZoom = 100
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString() != PAGE) onExternalLink(request.url)
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                main.post(onRendererGone)
                return true
            }
        }
        addJavascriptInterface(Bridge(), "PitchstoneAndroid")
        loadUrl(PAGE)
    }

    /** Calls `window.pitchstone.<function>(<argument as a JS string>)`; arguments are JSON text. */
    fun call(function: String, vararg args: String) {
        val list = args.joinToString(",") { JsonPrimitive(it).toString() }
        evaluateJavascript("window.pitchstone && window.pitchstone.$function($list)", null)
    }

    /**
     * Shows the soft keyboard for the field the page focused. Runs after the page has had a
     * moment to focus it (the load call that does so is asynchronous).
     */
    fun showKeyboard() {
        postDelayed({
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    /** Asks the page for its unsaved change; [callback] gets the parsed result, or null. */
    fun flush(callback: (JsonObject?) -> Unit) {
        evaluateJavascript("window.pitchstone ? window.pitchstone.flush() : null") { result ->
            val obj = runCatching {
                // The result is the JSON encoding of the string flush() returned.
                val text = json.parseToJsonElement(result).jsonPrimitive.content
                json.parseToJsonElement(text).jsonObject
            }.getOrNull()
            callback(obj)
        }
    }
}
