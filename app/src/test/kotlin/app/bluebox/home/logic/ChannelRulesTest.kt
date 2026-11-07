package app.bluebox.home.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelRulesTest {
    private fun ch(pkg: String, name: String, total: Int = 4, portrait: Int = 0) = ChannelRow(
        channelId = 1, packageName = pkg, name = name, description = null, entries = emptyList(),
        stats = ChannelStats(total, 0, total, 0, portrait, 0),
    )

    private val a = ch("p.a", "Новинки")
    private val b = ch("p.b", "Рекомендуем")
    private val c = ch("p.c", "Скоро")

    @Test fun `without a saved order the provider order stays`() {
        assertEquals(listOf(a, b, c), ChannelRules.arrange(listOf(a, b, c), emptyList()))
    }

    @Test fun `saved order wins and new channels go last`() {
        val order = listOf(c.key, a.key)
        assertEquals(listOf(c, a, b), ChannelRules.arrange(listOf(a, b, c), order))
    }

    @Test fun `unknown keys in the saved order are ignored`() {
        assertEquals(listOf(b, a), ChannelRules.arrange(listOf(a, b), listOf("gone|x", b.key, a.key)))
    }

    @Test fun `hidden channels drop out of Home`() {
        assertEquals(listOf(a, c), ChannelRules.visible(listOf(a, b, c), setOf(b.key)))
    }

    @Test fun `move shifts one place and clamps at the ends`() {
        val keys = listOf("1", "2", "3")
        assertEquals(listOf("2", "1", "3"), ChannelRules.move(keys, "2", -1))
        assertEquals(listOf("1", "3", "2"), ChannelRules.move(keys, "2", 1))
        assertEquals(keys, ChannelRules.move(keys, "1", -1))
        assertEquals(keys, ChannelRules.move(keys, "3", 1))
        assertEquals(keys, ChannelRules.move(keys, "zz", 1))
    }

    @Test fun `a row of mostly tall posters is portrait`() {
        assertTrue(ch("p", "n", total = 4, portrait = 3).portrait)
        assertFalse(ch("p", "n", total = 4, portrait = 2).portrait)
        assertFalse(ch("p", "n", total = 0).portrait)
    }
}
