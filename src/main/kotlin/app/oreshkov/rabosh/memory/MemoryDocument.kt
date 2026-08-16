package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBuilder

/**
 * The shape of one stored memory, and the only place that knows it.
 *
 * ```json
 * {
 *   "path": "/memories/projects/rabosh.md",
 *   "content": "…",
 *   "bytes": 1234,
 *   "lines": 42,
 *   "createdAt": 1755300000000,
 *   "updatedAt": 1755300500000,
 *   "anc": ["/memories", "/memories/projects"]
 * }
 * ```
 *
 * **This is an ordinary document written through the engine's public API.** No magic number, no type
 * id, no section kind — so rabosh's `format-permanence.md` governs nothing here and no version bump
 * is ever involved. Worth stating where the shape is defined, so nobody reaches for that ceremony.
 *
 * Three choices are load-bearing:
 *
 * - **`bytes` and `lines` are stored rather than derived**, so a directory listing never decodes
 *   `content` and the line-limit check costs a field read rather than a split.
 * - **Timestamps are epoch millis as integers.** A shredded numeric column reconstructs an integer
 *   exactly; the engine's one-scale-per-column caveat bites on decimals, and this avoids it by
 *   never having one.
 * - **`anc` is written unconditionally**, whether or not anything indexes it. Adding it later would
 *   mean rewriting every document that already exists, and it costs a few tens of bytes against a
 *   document whose whole point is prose.
 *
 * `accessedAt` is absent unless [MemoryOptions.trackAccess] is on — see that option for why.
 */
internal object MemoryDocument {

    const val PATH: String = "path"
    const val CONTENT: String = "content"
    const val BYTES: String = "bytes"
    const val LINES: String = "lines"
    const val CREATED_AT: String = "createdAt"
    const val UPDATED_AT: String = "updatedAt"
    const val ACCESSED_AT: String = "accessedAt"
    const val ANCESTORS: String = "anc"

    /** The JSONPath a listing index is defined over. */
    const val ANCESTORS_PATH: String = "$.anc[*]"

    /** The JSONPath a size column is defined over. */
    const val BYTES_PATH: String = "$.bytes"

    /** The JSONPath expiry compares against when [MemoryOptions.trackAccess] is off. */
    const val UPDATED_AT_PATH: String = "$.updatedAt"

    /** The JSONPath expiry compares against when [MemoryOptions.trackAccess] is on. */
    const val ACCESSED_AT_PATH: String = "$.accessedAt"

    /**
     * Encodes one memory into [builder], which is reset first and whose dictionary is reused.
     *
     * Safe to call repeatedly against one builder inside a single command: `WriteBatch.put` copies
     * the metadata and value bytes at staging time rather than at commit time, so the next
     * [encode] cannot overwrite a document that is already in the batch.
     */
    fun encode(
        builder: VariantBuilder,
        path: String,
        content: String,
        createdAt: Long,
        updatedAt: Long,
        accessedAt: Long?,
    ): Variant {
        builder.reset()
        builder.startObject()

        builder.field(PATH)
        builder.appendString(path)

        builder.field(CONTENT)
        builder.appendString(content)

        builder.field(BYTES)
        builder.appendLong(utf8Size(content))

        builder.field(LINES)
        builder.appendLong(lineCount(content).toLong())

        builder.field(CREATED_AT)
        builder.appendLong(createdAt)

        builder.field(UPDATED_AT)
        builder.appendLong(updatedAt)

        if (accessedAt != null) {
            builder.field(ACCESSED_AT)
            builder.appendLong(accessedAt)
        }

        builder.field(ANCESTORS)
        builder.startArray()
        for (ancestor in MemoryPath.ancestors(path)) builder.appendString(ancestor)
        builder.endArray()

        builder.endObject()
        return builder.buildVariant()
    }

    fun content(document: Variant): String = document.field(CONTENT)?.stringValue() ?: ""

    fun path(document: Variant): String = document.field(PATH)?.stringValue() ?: ""

    fun bytes(document: Variant): Long = document.field(BYTES)?.longValue() ?: 0L

    fun lines(document: Variant): Long = document.field(LINES)?.longValue() ?: 1L

    fun createdAt(document: Variant): Long = document.field(CREATED_AT)?.longValue() ?: 0L

    fun updatedAt(document: Variant): Long = document.field(UPDATED_AT)?.longValue() ?: 0L

    fun accessedAt(document: Variant): Long? = document.field(ACCESSED_AT)?.longValue()

    /**
     * The number of lines `view` will render, which is one more than the number of newlines.
     *
     * Not "the number of lines a human would count": `view` splits on `\n` without stripping a
     * trailing one, so a file ending in a newline has a final empty line and this counts it. That
     * is the SDK reference implementation's behaviour and the number the 999,999 limit is compared
     * against, so it is the number worth storing. `insert` counts differently and computes its own
     * — see `RaboshMemoryToolHandler.linesForInsert`.
     */
    fun lineCount(content: String): Int = content.count { it == '\n' } + 1

    /** The UTF-8 length of [text], counted rather than encoded. */
    fun utf8Size(text: String): Long {
        var size = 0L
        var index = 0
        while (index < text.length) {
            val code = text[index].code
            when {
                code < 0x80 -> size += 1
                code < 0x800 -> size += 2
                code in 0xD800..0xDBFF && index + 1 < text.length &&
                    text[index + 1].code in 0xDC00..0xDFFF -> {
                    size += 4
                    index++
                }
                // A lone surrogate has no UTF-8 encoding. `String.toByteArray` substitutes U+FFFD,
                // which is three bytes, so that is what the stored content would cost.
                else -> size += 3
            }
            index++
        }
        return size
    }
}
