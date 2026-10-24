package app.pitchstone.ui.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinkChoicesTest {
    private val titles = listOf("Plan", "Issue #42", "C# notes", "Planning")

    private fun targets(query: String) = LinkChoices.of(query, titles).map { it.target }

    @Test
    fun `a title holding # links whole, by its link name, showing the title`() {
        val first = LinkChoices.of("Issue #42", titles).first()
        assertThat(first.target).isEqualTo("Issue 42")
        assertThat(first.alias).isEqualTo("Issue #42")
        assertThat(first.kind).isEqualTo(LinkChoice.Kind.NOTE)
        assertThat(LinkChoices.of("C# n", titles).first { it.kind == LinkChoice.Kind.NOTE }.target).isEqualTo("C notes")
    }

    @Test
    fun `a # after a note's name links to that heading`() {
        assertThat(targets("Plan#Goals")).containsExactly("Plan#Goals", "Planning#Goals").inOrder()
        assertThat(LinkChoices.of("Plan#Goals", titles).first().label).isEqualTo("Plan › Goals")
    }

    @Test
    fun `only a heading links into this note`() {
        val c = LinkChoices.of("#Goals", titles).first()
        assertThat(c.kind).isEqualTo(LinkChoice.Kind.THIS_NOTE)
        assertThat(c.target).isEqualTo("#Goals")
    }

    @Test
    fun `a name no note has offers a new note first`() {
        val c = LinkChoices.of("Plans for May", titles)
        assertThat(c.first().kind).isEqualTo(LinkChoice.Kind.NEW_NOTE)
        assertThat(c.first().target).isEqualTo("Plans for May")
        assertThat(LinkChoices.of("plan", titles).none { it.kind == LinkChoice.Kind.NEW_NOTE }).isTrue()
    }
}
