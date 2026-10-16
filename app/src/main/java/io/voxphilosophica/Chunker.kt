package io.voxphilosophica

import java.text.BreakIterator
import java.util.Locale

/** Text chunks are generated without paraphrasing the source. */
object Chunker {
    fun split(input: String, maxLength: Int = 340): List<String> {
        require(maxLength >= 60)
        val clean = input.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val boundary = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        boundary.setText(clean)
        val sentences = mutableListOf<String>()
        var start = boundary.first()
        var end = boundary.next()
        while (end != BreakIterator.DONE) {
            val sentence = clean.substring(start, end).trim()
            if (sentence.isNotEmpty()) sentences.addAll(splitLong(sentence, maxLength))
            start = end; end = boundary.next()
        }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > maxLength) {
                chunks.add(current.toString()); current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }
    private fun splitLong(s: String, max: Int): List<String> {
        val result = mutableListOf<String>()
        var rest = s
        while (rest.length > max) {
            val lastSpace = rest.lastIndexOf(' ', max)
            val cut = if (lastSpace >= max / 2) lastSpace else max
            result.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotBlank()) result.add(rest)
        return result
    }
}
