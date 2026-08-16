package app.oreshkov.rabosh.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Path normalisation, including the Windows cases that are the reason it is a string function.
 *
 * The build enforces the other half — `checkNoNioPath` fails if a main source outside the
 * store-directory allowlist so much as mentions `java.nio.file.Path` — because the divergence this
 * prevents is one a POSIX-only CI leg would never see.
 */
class MemoryPathTest {

    @Test
    fun `canonical paths are returned unchanged`() {
        assertEquals("/memories", MemoryPath.normalise("/memories"))
        assertEquals("/memories/a.md", MemoryPath.normalise("/memories/a.md"))
        assertEquals("/memories/p/q/deep.md", MemoryPath.normalise("/memories/p/q/deep.md"))
    }

    @Test
    fun `redundant separators and dot segments collapse`() {
        assertEquals("/memories/a.md", MemoryPath.normalise("/memories//a.md"))
        assertEquals("/memories/a.md", MemoryPath.normalise("/memories/./a.md"))
        assertEquals("/memories/a.md", MemoryPath.normalise("/memories/p/../a.md"))
        assertEquals("/memories", MemoryPath.normalise("/memories/"))
        assertEquals("/memories", MemoryPath.normalise("/memories/p/.."))
    }

    @Test
    fun `traversal out of the root is refused`() {
        assertNull(MemoryPath.normalise("/memories/../etc/passwd"))
        assertNull(MemoryPath.normalise("/memories/../../secrets.env"))
        assertNull(MemoryPath.normalise("/memories/p/../../x"))
        assertNull(MemoryPath.normalise("/memories/.."))
    }

    @Test
    fun `paths outside the root are refused, prefix lookalikes included`() {
        assertNull(MemoryPath.normalise("/etc/passwd"))
        assertNull(MemoryPath.normalise("memories/a.md"))
        assertNull(MemoryPath.normalise(""))
        // Starts with the root as *text* and is not under it. This is why the prefix is checked
        // again after normalisation rather than only before.
        assertNull(MemoryPath.normalise("/memoriesish/a.md"))
        assertNull(MemoryPath.normalise("/memories.md"))
    }

    @Test
    fun `percent-encoded traversal is refused rather than taken literally`() {
        assertNull(MemoryPath.normalise("/memories/%2e%2e/a.md"))
        assertNull(MemoryPath.normalise("/memories/%2E%2E/a.md"))
        assertNull(MemoryPath.normalise("/memories/%2e%2E%2f/a.md"))
        // A single encoded dot is not a traversal sequence and is an ordinary, if odd, segment.
        assertEquals("/memories/%2e/a.md", MemoryPath.normalise("/memories/%2e/a.md"))
    }

    @Test
    fun `a backslash is refused, because it is a separator on one platform and not the other`() {
        assertNull(MemoryPath.normalise("/memories\\a.md"))
        assertNull(MemoryPath.normalise("/memories\\..\\etc"))
        assertNull(MemoryPath.normalise("/memories/p\\q.md"))
    }

    @Test
    fun `Windows spellings that a filesystem would accept are not paths here`() {
        assertNull(MemoryPath.normalise("C:/memories/a.md"))
        assertNull(MemoryPath.normalise("\\\\server\\share\\memories"))
        // A drive letter *inside* the namespace is an ordinary segment: nothing resolves it.
        assertEquals("/memories/C:/a.md", MemoryPath.normalise("/memories/C:/a.md"))
    }

    @Test
    fun `control characters are refused`() {
        assertNull(MemoryPath.normalise("/memories/a\u0000.md"))
        assertNull(MemoryPath.normalise("/memories/a\n.md"))
        assertNull(MemoryPath.normalise("/memories/a\u007F.md"))
        assertNull(MemoryPath.normalise("/memories/a\t.md"))
    }

    @Test
    fun `an unpaired surrogate is refused, because it has no UTF-8 encoding`() {
        assertNull(MemoryPath.normalise("/memories/a\uD800.md"))
        assertNull(MemoryPath.normalise("/memories/a\uDC00.md"))
        assertEquals("/memories/a\uD83D\uDE00.md", MemoryPath.normalise("/memories/a\uD83D\uDE00.md"))
    }

    @Test
    fun `lengths are bounded in UTF-8 bytes rather than characters`() {
        // 127 two-byte characters is 254 bytes and fits; one more does not, although a
        // character-counting implementation would accept both.
        val longSegment = "é".repeat(127)
        assertEquals("/memories/$longSegment", MemoryPath.normalise("/memories/$longSegment"))
        assertNull(MemoryPath.normalise("/memories/${"é".repeat(128)}"))

        val deep = (1..200).joinToString("/") { "segment$it" }
        assertNull(MemoryPath.normalise("/memories/$deep"))
    }

    @Test
    fun ancestors() {
        assertEquals(emptyList(), MemoryPath.ancestors("/memories"))
        assertEquals(listOf("/memories"), MemoryPath.ancestors("/memories/a.md"))
        assertEquals(
            listOf("/memories", "/memories/p", "/memories/p/q"),
            MemoryPath.ancestors("/memories/p/q/deep.md"),
        )
    }

    @Test
    fun segmentsBelow() {
        assertEquals(listOf("a.md"), MemoryPath.segmentsBelow("/memories", "/memories/a.md"))
        assertEquals(listOf("p", "x.md"), MemoryPath.segmentsBelow("/memories", "/memories/p/x.md"))
        assertNull(MemoryPath.segmentsBelow("/memories/p", "/memories/q/x.md"))
        // The trailing slash is what stops `/memories/a` matching `/memories/ab`.
        assertNull(MemoryPath.segmentsBelow("/memories/a", "/memories/ab.md"))
    }
}
