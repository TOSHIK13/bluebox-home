package app.bluebox.home.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class DockRulesTest {
    private val self = "app.bluebox.home"
    private fun a(p: String, l: String = p) = AppEntry(p, l)

    @Test fun `pinned first in pin order, then alphabetical`() {
        val installed = listOf(a("z.app", "Zeta"), a("com.plexapp.android", "Plex"), a("a.app", "Alpha"), a("app.bluebox.tv", "Blue Box"))
        val dock = DockRules.pick(installed, self, size = 4).map { it.packageName }
        assertEquals(listOf("app.bluebox.tv", "com.plexapp.android", "a.app", "z.app"), dock)
    }

    @Test fun `own package is never listed`() {
        val installed = listOf(a(self, "Home"), a("b.app", "B"))
        assertEquals(listOf("b.app"), DockRules.pick(installed, self).map { it.packageName })
        assertEquals(listOf("b.app"), DockRules.all(installed, self).map { it.packageName })
    }

    @Test fun `variants with one label appear once in the dock`() {
        val installed = listOf(a("app.bluebox.tv.beta", "Blue Box"), a("app.bluebox.tv.debug", "Blue Box"), a("c.app", "C"))
        assertEquals(listOf("app.bluebox.tv.beta", "c.app"), DockRules.pick(installed, self).map { it.packageName })
    }

    private val installed = listOf(a("a", "A"), a("b", "B"), a("c", "C"), a("d", "D"), a("e", "E"), a("f", "F"), a(self, "Home"))
    private fun pkgs(s: List<DockRules.Slot>) = s.map { it.app?.packageName }

    @Test fun `default dock is five recent slots filled newest first`() {
        val s = DockRules.slots(installed, self, null, recent = listOf("c", self, "gone", "a"))
        assertEquals(listOf("c", "a", "b", "d", "e"), pkgs(s)) // history first, then the automatic pick
    }

    @Test fun `fixed app is skipped by recent slots`() {
        val s = DockRules.slots(installed, self, listOf("c", DockRules.RECENT, DockRules.RECENT), recent = listOf("c", "b"))
        assertEquals(listOf("c", "b", "a"), pkgs(s))
    }

    @Test fun `uninstalled fixed app has no slot, recent slot without apps stays empty`() {
        assertEquals(listOf("a"), pkgs(DockRules.slots(installed, self, listOf("gone", "a"), emptyList())))
        val one = listOf(a("x", "X"))
        assertEquals(listOf("x", null), pkgs(DockRules.slots(one, self, listOf(DockRules.RECENT, DockRules.RECENT), emptyList())))
    }

    @Test fun `normalize drops missing apps and keeps indexes aligned with slots`() {
        val t = DockRules.normalize(installed, self, listOf("gone", DockRules.RECENT, "a", self))
        assertEquals(listOf(DockRules.RECENT, "a"), t)
        assertEquals(t.size, DockRules.slots(installed, self, t, emptyList()).size)
    }

    @Test fun `move swaps neighbours and ignores the edges`() {
        val t = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), DockRules.move(t, 0, +1))
        assertEquals(listOf("a", "c", "b"), DockRules.move(t, 2, -1))
        assertEquals(t, DockRules.move(t, 0, -1))
        assertEquals(t, DockRules.move(t, 2, +1))
    }

    @Test fun `remove and add - fixed once, recent repeatable, capped`() {
        assertEquals(listOf("a", "c"), DockRules.removeAt(listOf("a", "b", "c"), 1))
        assertEquals(listOf("a"), DockRules.add(listOf("a"), "a"))
        assertEquals(listOf("a", DockRules.RECENT, DockRules.RECENT), DockRules.add(DockRules.add(listOf("a"), DockRules.RECENT), DockRules.RECENT))
        val full = (1..DockRules.MAX_SLOTS).map { "p$it" }
        assertEquals(full, DockRules.add(full, "new"))
    }

    @Test fun `picker order - recent first, the rest alphabetical, no self`() {
        val out = DockRules.byRecency(installed, self, listOf("d", "b")).map { it.packageName }
        assertEquals(listOf("d", "b", "a", "c", "e", "f"), out)
    }

    @Test fun `size limit and duplicates`() {
        val installed = listOf(a("x", "X"), a("x", "X"), a("y", "Y"), a("z", "Z"))
        assertEquals(2, DockRules.pick(installed, self, pinned = emptyList(), size = 2).size)
        assertEquals(3, DockRules.all(installed, self).size)
    }
}
