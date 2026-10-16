package io.voxphilosophica

/** The v1.0 IDs follow the upstream Sherpa-ONNX Kokoro speaker table. */
data class NarratorVoice(val label: String, val speakerId: Int)

object NarratorVoices {
    val english = listOf(
        NarratorVoice("British male · George", 26),
        NarratorVoice("British male · Daniel", 24),
        NarratorVoice("British male · Fable", 25),
        NarratorVoice("British male · Lewis", 27),
        NarratorVoice("American male · Adam", 11),
        NarratorVoice("American male · Echo", 12),
        NarratorVoice("American male · Eric", 13),
        NarratorVoice("American male · Fenrir", 14),
        NarratorVoice("American male · Liam", 15),
        NarratorVoice("American male · Michael", 16),
        NarratorVoice("American male · Onyx", 17),
        NarratorVoice("American male · Puck", 18),
        NarratorVoice("American male · Santa", 19),
        NarratorVoice("British female · Alice", 20),
        NarratorVoice("British female · Emma", 21),
        NarratorVoice("British female · Isabella", 22),
        NarratorVoice("British female · Lily", 23),
        NarratorVoice("American female · Alloy", 0),
        NarratorVoice("American female · Aoede", 1),
        NarratorVoice("American female · Bella", 2),
        NarratorVoice("American female · Heart", 3),
        NarratorVoice("American female · Jessica", 4),
        NarratorVoice("American female · Kore", 5),
        NarratorVoice("American female · Nicole", 6),
        NarratorVoice("American female · Nova", 7),
        NarratorVoice("American female · River", 8),
        NarratorVoice("American female · Sarah", 9),
        NarratorVoice("American female · Sky", 10)
    )
    val latest = listOf(
        NarratorVoice("English · Maple", 0),
        NarratorVoice("English · Sol", 1),
        NarratorVoice("British English · Vale", 2)
    ) + (3..57).map { NarratorVoice("Mandarin female " + (it - 2), it) } +
        (58..102).map { NarratorVoice("Mandarin male " + (it - 57), it) }

    val system = (0 until 11).map { NarratorVoice("Installed offline voice " + (it + 1), it) }

    fun forMode(mode: String): List<NarratorVoice> = when (mode) {
        "kokoro-english" -> english
        "kokoro-latest" -> latest
        else -> system
    }
}
