package app.pitchstone.ui.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FoldCaseTest {
    @Test
    fun `positions stay where a character's lower case is longer`() {
        val title = "İstanbul plan"
        val folded = foldCase(title)
        assertThat(folded.length).isEqualTo(title.length)
        assertThat(folded.indexOf("plan")).isEqualTo(title.indexOf("plan"))
        assertThat(foldCase("ABC Ä")).isEqualTo("abc ä")
    }
}
