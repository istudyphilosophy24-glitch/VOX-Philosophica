package io.voxphilosophica

/**
 * Deterministic spoken-text preparation for scholarly audiobooks.
 * Never substitutes arguments or paraphrases source sentences.
 *
 * Kotlin's readable screen text is always the original imported text; these
 * tweaks only address punctuation that phonemizers sometimes read literally.
 */
object NarrationDirector {
    fun forSpeech(original: String): String = original
        .replace('\u00A0', ' ')
        .replace("\u201C", "\"").replace("\u201D", "\"")
        .replace("\u2018", "'").replace("\u2019", "'")
        .replace("\u2014", "; ")
        .replace("\u2026", "...")
        .replace(Regex("[ \t]{2,}"), " ")
        .trim()
}
