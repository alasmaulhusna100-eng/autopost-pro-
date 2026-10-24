package com.reelqueue.app

data class CsvRow(
    val title: String,
    val description: String,
    val number: Int? = null,
    val fileName: String? = null
)

object CsvParser {
    private val TITLE_KEYS = setOf("title", "টাইটেল", "শিরোনাম")
    private val DESC_KEYS = setOf("description", "desc", "ডেসক্রিপশন", "বিবরণ")

    fun parse(raw: String): List<CsvRow> {
        val text = raw.removePrefix("\uFEFF")
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delim = listOf(',', ';', '\t')
            .maxByOrNull { d -> firstLine.count { it == d } }
            ?.takeIf { firstLine.contains(it) } ?: ','

        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(c)
                }
            } else {
                when {
                    c == '"' -> inQuotes = true
                    c == delim -> {
                        row.add(cell.toString())
                        cell.setLength(0)
                    }
                    c == '\n' || c == '\r' -> {
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        row.add(cell.toString())
                        cell.setLength(0)
                        rows.add(row)
                        row = ArrayList()
                    }
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }

        val clean = rows.filter { r -> r.any { it.isNotBlank() } }
        if (clean.isEmpty()) return emptyList()

        val header = clean.first().map { it.trim().lowercase() }
        val titleIdx = header.indexOfFirst { it in TITLE_KEYS }
        val titleCol: Int
        val descCol: Int
        val data: List<List<String>>
        if (titleIdx >= 0) {
            titleCol = titleIdx
            val d = header.indexOfFirst { it in DESC_KEYS }
            descCol = if (d >= 0) d else titleIdx + 1
            data = clean.drop(1)
        } else {
            titleCol = 0
            descCol = 1
            data = clean
        }

        return data.map { r ->
            CsvRow(
                title = r.getOrNull(titleCol).orEmpty().trim(),
                description = r.getOrNull(descCol).orEmpty().trim()
            )
        }
    }
}
