package io.voxphilosophica

/** Import phase reporting; percent covers the complete operation, not only extraction. */
data class ImportUpdate(val title: String, val detail: String, val percent: Int) {
    init { require(percent in -1..100) }
}

class ImportCancelledException : RuntimeException("Import cancelled before saving the book")

object ImportProgress {
    fun copying(bytes: Long, expected: Long): ImportUpdate {
        val done = bytes.coerceAtLeast(0)
        return if (expected > 0) {
            val percent = ((done.coerceAtMost(expected) * 25L) / expected).toInt()
            ImportUpdate("Copying your book",
                (done / 1_048_576).toString() + " of " + (expected / 1_048_576) +
                    " MB copied locally", percent)
        } else {
            ImportUpdate("Copying your book",
                (done / 1_048_576).toString() + " MB copied to private storage", -1)
        }
    }

    fun extracting(format: String, completed: Int, total: Int, unit: String): ImportUpdate {
        val safeTotal = total.coerceAtLeast(1)
        val done = completed.coerceIn(0, safeTotal)
        val overall = 25 + (done * 65L / safeTotal).toInt()
        return ImportUpdate("Extracting " + format + " text",
            done.toString() + " / " + safeTotal + " " + unit + " processed", overall)
    }

    fun saving(): ImportUpdate =
        ImportUpdate("Saving to your library", "Writing extracted chapters to private storage", 94)

    fun complete(): ImportUpdate =
        ImportUpdate("Import complete", "Your book is ready to read and listen to", 100)
}
