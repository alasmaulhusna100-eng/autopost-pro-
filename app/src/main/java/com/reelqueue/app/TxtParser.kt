package com.reelqueue.app

/**
 * Reads a text file made of blocks like:
 *
 * --- ক্লিপ 01 (Chitose_S01E02_part01.mp4) ---
 * টাইটেল: ...
 * ডেসক্রিপশন:
 * (many lines)
 */
object TxtParser {
    private val HEADER = Regex(
        """^\s*-{2,}\s*(?:ক্লিপ|clip|episode|ep|part|video|ভিডিও)?\s*(\p{Nd}+)\s*(?:\(([^)]*)\))?\s*-{2,}\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val TITLE = Regex("""^\s*(?:টাইটেল|শিরোনাম|title)\s*[:：]\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val DESC = Regex("""^\s*(?:ডেসক্রিপশন|বিবরণ|description|desc)\s*[:：]\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun looksLikeBlocks(raw: String): Boolean =
        raw.lineSequence().any { HEADER.matches(it) }

    private fun toInt(s: String): Int? =
        s.map { Character.digit(it, 10) }.filter { it >= 0 }.joinToString("").toIntOrNull()

    fun parse(raw: String): List<CsvRow> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<CsvRow>()

        var open = false
        var number: Int? = null
        var file: String? = null
        var title = ""
        val desc = StringBuilder()
        var inDesc = false

        fun flush() {
            if (open) {
                out.add(
                    CsvRow(
                        title = title.trim(),
                        description = desc.toString().trim(),
                        number = number,
                        fileName = file?.trim()?.ifBlank { null }
                    )
                )
            }
        }

        for (line in text.split('\n')) {
            val h = HEADER.matchEntire(line)
            if (h != null) {
                flush()
                open = true
                number = toInt(h.groupValues[1])
                file = h.groupValues[2]
                title = ""
                desc.setLength(0)
                inDesc = false
                continue
            }
            if (!open) continue
            if (!inDesc) {
                val t = TITLE.matchEntire(line)
                if (t != null) {
                    title = t.groupValues[1]
                    continue
                }
                val d = DESC.matchEntire(line)
                if (d != null) {
                    inDesc = true
                    if (d.groupValues[1].isNotBlank()) desc.append(d.groupValues[1])
                    continue
                }
            } else {
                if (desc.isNotEmpty()) desc.append('\n')
                desc.append(line)
            }
        }
        flush()
        return out
    }
}
