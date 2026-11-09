package app.pitchstone.ui.editor

import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorWebViewTest {
    @Test
    fun fillsTheSpaceItIsGiven() {
        // Laid out wrap-content, a WebView gives its page a viewport 0 high for `vh` units, and
        // the space for tapping below a note's text (editor.css) collapses.
        val web = EditorWebView(ApplicationProvider.getApplicationContext(), onMessage = {}, onExternalLink = {}, onRendererGone = {})
        try {
            // None at all would get wrap-content ones from the host.
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, web.layoutParams?.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, web.layoutParams?.height)
        } finally {
            web.destroy()
        }
    }
}
