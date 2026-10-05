package app.duongondro.core

/**
 * Where a practice sits on the path. The path only gates short refuge →
 * ngöndro → 8th Karmapa; it never judges combinations within ngöndro.
 */
enum class PracticeGroup { BeforeNgondro, Ngondro, AfterNgondro, AnyTime }

/**
 * A practice in the catalogue. The catalogue is data, so new practices ship
 * without migrations and users can add their own.
 *
 * @property name shown first: the Tibetan or Sanskrit name where practitioners use one.
 * @property secondName the second line, usually the English name.
 * @property target count per round; null for open-ended practices.
 * @property malaSize per-practice mala size; null means the global default.
 */
data class Practice(
    val id: String,
    val name: String,
    val secondName: String? = null,
    val group: PracticeGroup,
    val target: Int?,
    private val allowStreakOnly: Boolean,
    val malaSize: Int? = null,
    val isCustom: Boolean = false,
) {
    /** Whether the practice may be tracked by streak alone: never for ngöndro. */
    val streakOnlyAllowed: Boolean get() = group != PracticeGroup.Ngondro && allowStreakOnly

    fun effectiveMalaSize(globalDefault: Int): Int = malaSize ?: globalDefault

    /** Practices without a target (the Karmapa meditations) start as streak-only. */
    val streakOnlyByDefault: Boolean get() = streakOnlyAllowed && target == null
}

object Catalogue {
    val builtIn: List<Practice> = listOf(
        Practice("short-refuge", "Short refuge", null, PracticeGroup.BeforeNgondro, 11_111, true),
        Practice("refuge", "Refuge and the Enlightened Attitude", "Prostrations", PracticeGroup.Ngondro, 111_111, false),
        Practice("dorje-sempa", "Dorje Sempa", "Diamond Mind", PracticeGroup.Ngondro, 111_111, false),
        Practice("mandala", "Mandala offering", null, PracticeGroup.Ngondro, 111_111, false),
        Practice("guru-yoga", "Meditation on the Lama", "Guru Yoga", PracticeGroup.Ngondro, 111_111, false),
        Practice("8th-karmapa", "8th Karmapa Meditation", null, PracticeGroup.AfterNgondro, null, true),
        Practice("16th-karmapa", "Meditation on the 16th Karmapa", null, PracticeGroup.AnyTime, null, true),
        Practice("chenrezig", "Chenrezig", "Loving Eyes", PracticeGroup.AnyTime, 1_000_000, true),
        Practice("amitabha", "Amitabha", "Meditation on the Buddha of Limitless Light", PracticeGroup.AnyTime, 500_000, true),
    )

    /** Practices available given the onboarding answers. */
    fun available(finishedNgondro: Boolean, finishedShortRefuge: Boolean): List<Practice> =
        builtIn.filter {
            when (it.group) {
                PracticeGroup.AnyTime -> true
                PracticeGroup.AfterNgondro -> finishedNgondro
                PracticeGroup.Ngondro -> finishedNgondro || finishedShortRefuge
                // Short refuge stays open to everyone: we don't judge.
                PracticeGroup.BeforeNgondro -> true
            }
        }
}

/** Rounds of a counted practice: personal, never published. */
data class RoundProgress(val round: Int, val inRound: Int, val lifetime: Int) {
    companion object {
        fun of(lifetime: Int, target: Int): RoundProgress {
            require(target > 0)
            return RoundProgress(round = lifetime / target + 1, inRound = lifetime % target, lifetime = lifetime)
        }
    }
}
