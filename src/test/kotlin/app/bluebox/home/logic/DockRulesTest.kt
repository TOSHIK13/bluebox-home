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

    @Test fun `size limit and duplicates`() {
        val installed = listOf(a("x", "X"), a("x", "X"), a("y", "Y"), a("z", "Z"))
        assertEquals(2, DockRules.pick(installed, self, pinned = emptyList(), size = 2).size)
        assertEquals(3, DockRules.all(installed, self).size)
    }
}
