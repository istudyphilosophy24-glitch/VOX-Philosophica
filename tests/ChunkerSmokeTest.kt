import io.voxphilosophica.Chunker
fun main() {
    val examples = listOf(
        "Being is not a genus. It is that which is asked about. This question must be examined.",
        "An extraordinarily long sentence composed of many words and ideas that goes on and on and on and on and on and on and on and on and on and on and on and on and on and on.",
        "Hegel's Aufhebung preserves and cancels; it carries forward what it negates.",
        "",
        "Sentence.\n\nA second    sentence."
    )
    for (s in examples) {
        val got = Chunker.split(s, 65)
        check(got.all { it.length <= 65 }) { "Oversize chunk" }
        check(got.joinToString(" ").replace(Regex("\\s+"), " ").trim() == s.replace(Regex("\\s+"), " ").trim()) {
            "Missing or modified text: $got"
        }
    }
    println("Passed chunking integrity tests for ${examples.size} cases.")
}
