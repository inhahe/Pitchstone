# The editor page calls into the app through this JavascriptInterface; keep its methods.
-keepclassmembers class app.pitchstone.ui.editor.EditorWebView$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
