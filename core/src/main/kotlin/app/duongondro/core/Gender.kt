package app.duongondro.core

/**
 * A person's optional grammatical gender, used only to conjugate words in the Slavic
 * languages: "Anna ukończyła", "Jan ukończył" (design: Localisation › Grammatical
 * gender). Nonbinary, like none given, gets the neutral forms ("ukończył(a)").
 */
enum class Gender(val wire: String) {
    Male("male"), Female("female"), Nonbinary("nonbinary");

    companion object {
        fun fromWire(value: String?): Gender? = entries.firstOrNull { it.wire == value }
    }
}
