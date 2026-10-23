package app.inkdown.markdown

import app.inkdown.export.Importer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WikiLinksTest {
    @Test
    fun `finds links, aliases, headings and embeds`() {
        val links = WikiLinks.scan("See [[Note]], [[Other|shown]], [[Deep#Part]] and ![[pic.png]].")
        assertThat(links.map { Triple(it.target, it.alias, it.embed) }).containsExactly(
            Triple("Note", null, false),
            Triple("Other", "shown", false),
            Triple("Deep#Part", null, false),
            Triple("pic.png", null, true),
        ).inOrder()
        assertThat(links[2].noteName).isEqualTo("Deep")
        assertThat(links[2].label).isEqualTo("Deep › Part")
        assertThat(links[1].label).isEqualTo("shown")
    }

    @Test
    fun `escaped brackets, code and html are not links`() {
        val md = "\\[[no]] `[[code]]` <a title=\"[[html]]\">x</a>\n\n```\n[[fenced]]\n```\n\n[[yes]]"
        val links = WikiLinks.scan(md, Markdown.literalRanges(md))
        assertThat(links.map { it.target }).containsExactly("yes")
    }

    @Test
    fun `an escaped bang makes a plain link, not an embed`() {
        val links = WikiLinks.scan("\\![[a]]")
        assertThat(links.single().embed).isFalse()
    }

    @Test
    fun `brackets or line breaks inside end the attempt, as in the editor`() {
        assertThat(WikiLinks.scan("[[a]b]] [[c\nd]] [[ ]]")).isEmpty()
        assertThat(WikiLinks.scan("[[[x]]]").map { it.target }).containsExactly("x")
    }

    @Test
    fun `table-safe pipe drops the backslash from the target`() {
        val link = WikiLinks.scan("| [[a\\|b]] |").single()
        assertThat(link.target).isEqualTo("a")
        assertThat(link.alias).isEqualTo("b")
    }

    @Test
    fun `link names drop the characters links cannot hold`() {
        assertThat(WikiLinks.linkName("My #1 [draft] | v2^")).isEqualTo("My 1 draft v2")
        assertThat(WikiLinks.key("  Mixed Case  ")).isEqualTo("mixed case")
    }

    // The editor marks missing links with the same keys: editor/test/links.test.ts uses these.
    @Test
    fun `a note can be linked by its title and by every tail of its folder path`() {
        assertThat(WikiLinks.lookupKeys("Plan", "Work/Sub")).containsExactly("plan", "work/sub/plan", "sub/plan").inOrder()
        assertThat(WikiLinks.lookupKeys("Meeting 10/23", "Work")).containsExactly("meeting 10/23", "work/meeting 10/23")
        assertThat(WikiLinks.lookupKeys("Top", "")).containsExactly("top", "/top")
        assertThat(WikiLinks.lookupKeys("  ", "Work")).isEmpty()
    }

    @Test
    fun `a folder link is looked up by its normalized path`() {
        assertThat(WikiLinks.pathKey(" Work / Sub /Plan")).isEqualTo("work/sub/plan")
        assertThat(WikiLinks.pathKey("/Top")).isEqualTo("/top")
        assertThat(WikiLinks.pathKey("Plan")).isNull()
        assertThat(WikiLinks.pathKey("Work/")).isNull()
    }

    @Test
    fun `typed link targets keep their heading part`() {
        assertThat(WikiLinks.cleanTarget(" Plan#Goals ")).isEqualTo("Plan#Goals")
        assertThat(WikiLinks.cleanTarget("a[b]|c\nd")).isEqualTo("abcd")
    }
}

class LinkRewriterTest {
    @Test
    fun `renames links but keeps headings, aliases, embeds and folders`() {
        val md = "[[Old]] [[old#Sec|alias]] ![[Old]] [[dir/Old]] [[Older]] `[[Old]]`"
        assertThat(LinkRewriter.rename(md, "Old", "New"))
            .isEqualTo("[[New]] [[New#Sec|alias]] ![[New]] [[dir/New]] [[Older]] `[[Old]]`")
    }

    @Test
    fun `keeps the table escape and the front matter`() {
        val md = "---\ntitle: [[Old]]\n---\n| [[Old\\|x]] |\n| --- |\n"
        assertThat(LinkRewriter.rename(md, "Old", "New")).isEqualTo("---\ntitle: [[Old]]\n---\n| [[New\\|x]] |\n| --- |\n")
    }

    @Test
    fun `a title with a slash is renamed whole, and md suffixes stay`() {
        val md = "[[Meeting 10/23]] [[Work/Meeting 10/23#Notes]] [[Meeting 10/23.md]] [[Other/23]]"
        assertThat(LinkRewriter.rename(md, "Meeting 10/23", "Retro"))
            .isEqualTo("[[Retro]] [[Work/Retro#Notes]] [[Retro.md]] [[Other/23]]")
        assertThat(LinkRewriter.rename("[[Plan.md|p]]", "Plan", "Roadmap")).isEqualTo("[[Roadmap.md|p]]")
    }

    @Test
    fun `carriage returns are normalized without shifting the text`() {
        val md = "---\r\na: 1\r\n---\r\nx [[Old]]\r\n"
        assertThat(LinkRewriter.rename(md, "Old", "New")).isEqualTo("---\na: 1\n---\nx [[New]]\n")
    }

    @Test
    fun `returns null when nothing links to the old name`() {
        assertThat(LinkRewriter.rename("[[Other]]", "Old", "New")).isNull()
    }
}

class NoteIndexerTest {
    @Test
    fun `collects tags from text and front matter, not from code`() {
        val body = "---\ntags: [project, \"two words\"]\n---\nA #idea and #nested/tag but not `#code` or # heading or #123.\n\n```\n#fenced\n```"
        val index = NoteIndexer.index("Title", body)
        assertThat(index.tags.keys).containsExactly("project", "two words", "idea", "nested/tag")
    }

    @Test
    fun `block list tags in front matter`() {
        assertThat(NoteIndexer.frontMatterTags("tags:\n  - a\n  - '#b'\nother: x")).containsExactly("a", "#b").inOrder()
    }

    @Test
    fun `links are indexed by whole name and by last part, without md`() {
        val index = NoteIndexer.index("t", "[[Some Note#h]] [[folder/Other|x]] [[#local]] [[Plan.md]]")
        assertThat(index.links).containsExactly("some note", "folder/other", "other", "plan")
    }

    @Test
    fun `search text and snippet come from the plain text`() {
        val index = NoteIndexer.index("My Title", "# Head\n\nSome **bold**   text.")
        assertThat(index.searchText).contains("my title")
        assertThat(index.searchText).contains("some bold text")
        assertThat(index.snippet).isEqualTo("Head Some bold text.")
    }

    @Test
    fun `a first heading repeating the title is left out of the snippet`() {
        assertThat(NoteIndexer.index("Groceries", "# Groceries\n\n- milk").snippet).isEqualTo("- milk")
    }
}

class PlainTextTest {
    @Test
    fun `renders structure without symbols`() {
        val md = "# Title\n\nSome *em* and [a link](https://x.com) and [[Note|alias]].\n\n- one\n- [x] done\n  - nested\n\n1. first\n2. second\n\n> quoted\n\n| a | bb |\n| --- | --- |\n| ccc | d |\n"
        assertThat(PlainText.render(md)).isEqualTo(
            """
            Title
            =====

            Some em and a link (https://x.com) and alias.

            - one
            - [x] done
                  - nested

            1. first
            2. second

            > quoted

            a   | bb
            ----+---
            ccc | d
            """.trimIndent(),
        )
    }

    @Test
    fun `single newlines are line breaks`() {
        assertThat(PlainText.render("one\ntwo")).isEqualTo("one\ntwo")
    }
}

class HtmlTest {
    @Test
    fun `raw html is shown, not run, and unsafe links are dropped`() {
        val html = Html.emailBody("<script>alert(1)</script>\n\n[x](javascript:alert(1))")
        assertThat(html).doesNotContain("<script>")
        assertThat(html).contains("&lt;script&gt;")
        assertThat(html).doesNotContain("javascript:")
    }

    @Test
    fun `highlights, tasks and wikilinks render for email`() {
        val html = Html.emailBody("==hi== [[Note|label]]\n\n- [ ] todo\n- [x] done")
        assertThat(html).contains("<mark")
        assertThat(html).contains("label")
        assertThat(html).contains("☐ ")
        assertThat(html).contains("☑ ")
        assertThat(html).doesNotContain("<input")
    }

    @Test
    fun `a full document has the title and styles`() {
        val doc = Html.document("A <b> title", "text")
        assertThat(doc).contains("<title>A &lt;b&gt; title</title>")
        assertThat(doc).contains("<h1>A &lt;b&gt; title</h1>")
    }
}

class PlainTextImportTest {
    private val samples = listOf(
        "# not a heading",
        "- not a list\n1. nor this\n> nor a quote",
        "**not bold** _not em_ `not code` ~~no~~ ==no==",
        "[not](a link) [[not wiki]] <b>not html</b> &amp; stays",
        "    indented line\n\ttabbed",
        "trailing  ",
        "---\n===\n***",
        "a | b\n--|--",
        "back\\slash at end\\",
        "line one\n\n\nafter blank lines",
    )

    @Test
    fun `plain text survives as literal text`() {
        for (s in samples) {
            val md = Importer.plainTextToMarkdown(s)
            // Reading the markdown back gives the original text, line for line.
            val back = PlainText.render(md)
            assertThat(back.lines().filter { it.isNotEmpty() }).isEqualTo(s.lines().filter { it.isNotEmpty() })
        }
    }
}
