package app.oreshkov.rabosh.memory

import com.anthropic.helpers.BetaMemoryToolHandler
import java.util.Optional
import java.util.TreeMap

/**
 * The memory tool over a `TreeMap`, written the most literal way the specification allows.
 *
 * **This is the oracle, and its value comes entirely from being written twice.** Every response
 * string here is spelled out longhand rather than taken from [MemoryResponses], and every algorithm
 * is expressed in the most obvious form rather than the efficient one — a listing sorts a flat list
 * of entries where the real handler walks a tree, occurrences are counted by a different loop, and
 * sizes are divided down rather than derived from a bit length. Sharing either would make the
 * differential suite agree with a typo instead of catching it.
 *
 * The one thing it deliberately *does* share is [MemoryPath]. The namespace rules are this project's
 * own rather than the specification's, so re-deriving them here would be testing a second guess
 * against the first. They have unit tests of their own in `MemoryPathTest`.
 *
 * This is the pattern rabosh's own `build-and-release.md` establishes: the rejected implementation
 * survives as the test oracle. It is also the only way to catch a divergence in a *response string*,
 * which is the failure mode that silently confuses the model rather than failing a build.
 */
internal class ReferenceMemoryHandler(
    private val options: MemoryOptions = MemoryOptions.DEFAULT,
) : BetaMemoryToolHandler {

    /** Path to content. Sorted, so a listing and a subtree walk are both obvious. */
    val store: TreeMap<String, String> = TreeMap()

    override fun view(path: String, viewRange: Optional<List<Long>>): String {
        val resolved = MemoryPath.normalise(path)
            ?: return "The path $path does not exist. Please provide a valid path."

        val content = if (resolved == "/memories") null else store[resolved]
        if (content != null) return viewFile(resolved, content, viewRange.orElse(null))

        val descendants = descendantsOf(resolved)
        if (descendants.isEmpty() && resolved != "/memories") {
            return "The path $resolved does not exist. Please provide a valid path."
        }
        return viewDirectory(resolved, descendants)
    }

    override fun create(path: String, fileText: String): String {
        val resolved = MemoryPath.normalise(path) ?: return "Error: File $path already exists"
        if (resolved == "/memories") return "Error: File $resolved already exists"
        if (utf8Length(fileText) > options.maxMemoryBytes) {
            return "Error: File $resolved exceeds the maximum memory file size of " +
                "${options.maxMemoryBytes} bytes"
        }
        if (store.containsKey(resolved) && !options.createOverwrites) {
            return "Error: File $resolved already exists"
        }
        store[resolved] = fileText
        return "File created successfully at: $resolved"
    }

    override fun strReplace(path: String, oldStr: String, newStr: String): String {
        val resolved = MemoryPath.normalise(path)
            ?: return "Error: The path $path does not exist. Please provide a valid path."
        if (resolved == "/memories") {
            return "Error: The path $resolved does not exist. Please provide a valid path."
        }
        val content = store[resolved]
            ?: return "Error: The path $resolved does not exist. Please provide a valid path."

        if (oldStr.isEmpty()) {
            return "No replacement was performed, old_str `$oldStr` did not appear verbatim in $resolved."
        }

        val starts = ArrayList<Int>()
        var at = content.indexOf(oldStr)
        while (at >= 0) {
            starts.add(at)
            at = content.indexOf(oldStr, at + 1)
        }
        if (starts.isEmpty()) {
            return "No replacement was performed, old_str `$oldStr` did not appear verbatim in $resolved."
        }

        val disjoint = ArrayList<Int>()
        var cursor = 0
        while (true) {
            val next = content.indexOf(oldStr, cursor)
            if (next < 0) break
            disjoint.add(next)
            cursor = next + oldStr.length
        }
        if (disjoint.size > 1) {
            val lines = starts.map { position -> content.substring(0, position).count { it == '\n' } + 1 }
            return "No replacement was performed. Multiple occurrences of old_str `$oldStr` " +
                "in lines: ${lines.joinToString(", ")}. Please ensure it is unique"
        }

        // The non-overlapping list, not `starts`: a self-overlapping `old_str` such as "aa" in
        // "aaa" starts twice and can still be replaced once, and the disjoint list is the one that
        // decided the edit was unambiguous.
        val position = disjoint.single()
        val updated = content.substring(0, position) + newStr + content.substring(position + oldStr.length)
        if (utf8Length(updated) > options.maxMemoryBytes) {
            return "Error: File $resolved exceeds the maximum memory file size of " +
                "${options.maxMemoryBytes} bytes"
        }
        store[resolved] = updated

        val changed = content.substring(0, position).count { it == '\n' }
        val lines = updated.split("\n")
        val first = maxOf(0, changed - 2)
        val last = minOf(lines.size, changed + 3)
        val snippet = (first until last).joinToString("\n") { numbered(it + 1, lines[it]) }
        return "The memory file has been edited. Here is the snippet showing the change " +
            "(with line numbers):\n$snippet"
    }

    override fun insert(path: String, insertLine: Long, insertText: String): String {
        val resolved = MemoryPath.normalise(path) ?: return "Error: The path $path does not exist"
        if (resolved == "/memories") return "Error: The path $resolved does not exist"
        val content = store[resolved] ?: return "Error: The path $resolved does not exist"

        val lines = if (content.isEmpty()) {
            ArrayList()
        } else {
            content.removeSuffix("\n").split("\n").toMutableList()
        }
        if (insertLine < 0 || insertLine > lines.size) {
            return "Error: Invalid `insert_line` parameter: $insertLine. " +
                "It should be within the range of lines of the file: [0, ${lines.size}]"
        }
        lines.add(insertLine.toInt(), insertText.removeSuffix("\n"))
        var updated = lines.joinToString("\n")
        if (!updated.endsWith("\n")) updated += "\n"
        if (utf8Length(updated) > options.maxMemoryBytes) {
            return "Error: File $resolved exceeds the maximum memory file size of " +
                "${options.maxMemoryBytes} bytes"
        }
        store[resolved] = updated
        return "The file $resolved has been edited."
    }

    override fun delete(path: String): String {
        val resolved = MemoryPath.normalise(path) ?: return "Error: The path $path does not exist"
        if (resolved == "/memories") return "Error: Cannot delete the /memories directory itself"

        val doomed = store.keys.filter { it == resolved || it.startsWith("$resolved/") }
        if (doomed.isEmpty()) return "Error: The path $resolved does not exist"
        doomed.forEach(store::remove)
        return "Successfully deleted $resolved"
    }

    override fun rename(oldPath: String, newPath: String): String {
        val from = MemoryPath.normalise(oldPath) ?: return "Error: The path $oldPath does not exist"
        val to = MemoryPath.normalise(newPath) ?: return "Error: The destination $newPath already exists"

        if (from == "/memories") return "Error: Cannot rename the /memories directory itself"
        if (to == "/memories") return "Error: The destination $to already exists"
        if (to == from) return "Error: The destination $to already exists"
        if (to.startsWith("$from/")) return "Error: Cannot rename $from to $to, which is inside it"

        val moving = store.keys.filter { it == from || it.startsWith("$from/") }
        if (moving.isEmpty()) return "Error: The path $from does not exist"
        if (store.keys.any { it == to || it.startsWith("$to/") }) {
            return "Error: The destination $to already exists"
        }

        val moved = moving.associateWith { store.getValue(it) }
        moving.forEach(store::remove)
        for ((source, content) in moved) store[to + source.substring(from.length)] = content
        return "Successfully renamed $from to $to"
    }

    // --- rendering, written the obvious way -----------------------------------------------------

    private fun viewFile(path: String, content: String, viewRange: List<Long>?): String {
        val lines = content.split("\n")
        if (lines.size > 999_999) return "File $path exceeds maximum line limit of 999,999 lines."

        var first = 0
        var last = lines.size
        if (viewRange != null && viewRange.size == 2) {
            first = clamp(maxOf(1L, viewRange[0]) - 1, lines.size)
            last = when {
                viewRange[1] == -1L -> lines.size
                viewRange[1] < 0 -> clamp(lines.size + viewRange[1], lines.size)
                else -> clamp(viewRange[1], lines.size)
            }
        }
        val selected = if (last > first) lines.subList(first, last) else emptyList()

        val rendered = StringBuilder("Here's the content of $path with line numbers:")
        var body = 0
        var shown = 0
        for ((offset, line) in selected.withIndex()) {
            val entry = numbered(first + 1 + offset, line)
            if (shown > 0 && body + 1 + entry.length > options.viewMaxChars) break
            rendered.append('\n').append(entry)
            body += (if (shown > 0) 1 else 0) + entry.length
            shown++
        }
        if (shown < selected.size) {
            rendered.append('\n').append(
                "[Truncated: showing lines ${first + 1} to ${first + shown} of ${lines.size}. " +
                    "Use view_range [${first + shown + 1}, -1] to read the rest.]",
            )
        }
        return rendered.toString()
    }

    /**
     * The listing as a flat, explicitly sorted list of entries.
     *
     * The comparator is the whole of the ordering rule: compare the relative segments one at a time,
     * a shorter list first when one is a prefix of the other, and a file before the directory of the
     * same name. That is what a name-sorted depth-first walk produces, said as a total order — and
     * it is deliberately *not* a sort of the joined paths, which would put `p.md` before `p/` since
     * `.` sorts before `/`.
     */
    private fun viewDirectory(directory: String, descendants: List<String>): String {
        val visible = descendants
            .map { it to it.substring(directory.length + 1).split("/") }
            .filter { (_, segments) -> segments.none { it.startsWith(".") || it == "node_modules" } }

        val total = visible.sumOf { (path, _) -> utf8Length(store.getValue(path)) }

        val entries = ArrayList<Entry>()
        for ((path, segments) in visible) {
            if (segments.size <= 2) {
                entries.add(Entry(segments, isDirectory = false, bytes = utf8Length(store.getValue(path))))
            }
            for (depth in 1..minOf(2, segments.size - 1)) {
                val prefix = segments.subList(0, depth)
                if (entries.none { it.isDirectory && it.segments == prefix }) {
                    val bytes = visible
                        .filter { (_, other) -> other.size > depth && other.subList(0, depth) == prefix }
                        .sumOf { (other, _) -> utf8Length(store.getValue(other)) }
                    entries.add(Entry(prefix, isDirectory = true, bytes = bytes))
                }
            }
        }
        entries.sortWith(ENTRY_ORDER)

        val out = StringBuilder(
            "Here're the files and directories up to 2 levels deep in $directory, " +
                "excluding hidden items and node_modules:",
        )
        out.append('\n').append(humanSize(total)).append('\t').append(directory)
        for (entry in entries) {
            val suffix = if (entry.isDirectory) "/" else ""
            out.append('\n')
                .append(humanSize(entry.bytes))
                .append('\t')
                .append(directory).append('/').append(entry.segments.joinToString("/")).append(suffix)
        }
        return out.toString()
    }

    private fun descendantsOf(directory: String): List<String> =
        store.keys.filter { it.startsWith("$directory/") }

    private fun numbered(lineNumber: Int, line: String): String =
        "${lineNumber.toString().padStart(6)}\t$line"

    private fun clamp(value: Long, size: Int): Int = value.coerceIn(0L, size.toLong()).toInt()

    private fun utf8Length(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    /** Divided down in a loop rather than derived from a bit length, so the two do not share a bug. */
    private fun humanSize(bytes: Long): String {
        if (bytes <= 0L) return "0B"
        val units = listOf("B", "K", "M", "G")
        var index = 0
        var remaining = bytes
        while (remaining >= 1024 && index < units.size - 1) {
            remaining /= 1024
            index++
        }
        val scaled = bytes.toDouble() / Math.pow(1024.0, index.toDouble())
        return if (scaled == Math.floor(scaled)) {
            "${scaled.toLong()}${units[index]}"
        } else {
            java.math.BigDecimal(scaled)
                .setScale(1, java.math.RoundingMode.HALF_EVEN)
                .toPlainString() + units[index]
        }
    }

    private class Entry(val segments: List<String>, val isDirectory: Boolean, val bytes: Long)

    private companion object {
        val ENTRY_ORDER = Comparator<Entry> { left, right ->
            val shared = minOf(left.segments.size, right.segments.size)
            for (index in 0 until shared) {
                val order = left.segments[index].compareTo(right.segments[index])
                if (order != 0) return@Comparator order
            }
            val bySize = left.segments.size.compareTo(right.segments.size)
            if (bySize != 0) bySize else left.isDirectory.compareTo(right.isDirectory)
        }
    }
}
