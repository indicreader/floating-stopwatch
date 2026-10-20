// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.model

import java.util.Locale

/**
 * Supported multiple-choice options and skip choice.
 */
enum class OptionChoice(val label: String) {
    A("A"),
    B("B"),
    C("C"),
    D("D"),
    E("E"),
    F("F"),
    SKIP("SKIP");

    fun getDisplayLabel(isNumeric: Boolean): String {
        return when (this) {
            A -> if (isNumeric) "1" else "A"
            B -> if (isNumeric) "2" else "B"
            C -> if (isNumeric) "3" else "C"
            D -> if (isNumeric) "4" else "D"
            E -> if (isNumeric) "5" else "E"
            F -> if (isNumeric) "6" else "F"
            SKIP -> "SKIP"
        }
    }
}

/**
 * Post-pause answer verification status.
 */
enum class VerificationStatus {
    UNVERIFIED,
    CORRECT,
    INCORRECT,
    SKIPPED
}

/**
 * Pacing category relative to benchmark threshold.
 */
enum class PacingCategory {
    FAST,       // Under target (e.g. < 90% of target)
    ON_PACE,    // Within ±10% of target
    SLOW        // Over target (> 110% of target / time trap)
}

/**
 * Immutable entity representing a single question's recorded answer, timing, and verification status.
 */
data class QuestionResponse(
    val questionNumber: Int,                     // Question index (e.g. 1, 101)
    val selectedOption: OptionChoice,            // A, B, C, D, or SKIP
    val splitDurationMillis: Long,               // Delta duration since last lap/start
    val cumulativeTimeMillis: Long,              // Total stopwatch elapsed time at moment of tap
    val targetDurationSeconds: Int = 45,         // Target pacing budget in seconds (default: 45s)
    var verificationStatus: VerificationStatus = if (selectedOption == OptionChoice.SKIP) VerificationStatus.SKIPPED else VerificationStatus.UNVERIFIED,
    var isFlagged: Boolean = false               // Mark-for-review flag
) {
    val isOvertime: Boolean
        get() = splitDurationMillis > (targetDurationSeconds * 1000L)

    val overtimeSeconds: Long
        get() = if (isOvertime) (splitDurationMillis - (targetDurationSeconds * 1000L)) / 1000 else 0L

    val deltaSeconds: Long
        get() = (splitDurationMillis / 1000) - targetDurationSeconds

    val deltaFormatted: String
        get() {
            val diff = deltaSeconds
            return if (diff > 0) "+${diff}s" else "${diff}s"
        }

    val pacingCategory: PacingCategory
        get() {
            val targetMillis = targetDurationSeconds * 1000L
            val lowerBound = (targetMillis * 0.90).toLong()
            val upperBound = (targetMillis * 1.10).toLong()
            return when {
                splitDurationMillis < lowerBound -> PacingCategory.FAST
                splitDurationMillis <= upperBound -> PacingCategory.ON_PACE
                else -> PacingCategory.SLOW
            }
        }

    val formattedSplit: String
        get() = formatDuration(splitDurationMillis)

    val formattedCumulative: String
        get() = formatDuration(cumulativeTimeMillis)

    companion object {
        fun formatDuration(millis: Long): String {
            val totalSeconds = millis / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val tenths = (millis % 1000) / 100
            return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
        }

        fun formatSecondsDisplay(millis: Long): String {
            val seconds = millis / 1000.0
            return String.format(Locale.US, "%.1fs", seconds)
        }
    }
}

/**
 * Headline summary metrics & session diagnostics calculated across the active session.
 */
data class SessionMetrics(
    val accuracyPercentage: Int = 0,
    val averageCorrectMillis: Long = 0L,
    val averageIncorrectMillis: Long = 0L,
    val totalAnswered: Int = 0,
    val totalCorrect: Int = 0,
    val totalIncorrect: Int = 0,
    val totalSkipped: Int = 0,
    val totalFlagged: Int = 0,
    val topTimeSinks: List<QuestionResponse> = emptyList(),
    val totalSessionDurationMillis: Long = 0L,
    val fastestQuestion: QuestionResponse? = null,
    val slowestQuestion: QuestionResponse? = null,
    val pacingAccuracyScore: Int = 0,             // % of questions finished <= target benchmark
    val averageTimePerQuestionMillis: Long = 0L
) {
    val formattedAvgCorrect: String
        get() = if (totalCorrect > 0) QuestionResponse.formatSecondsDisplay(averageCorrectMillis) else "—"

    val formattedAvgIncorrect: String
        get() = if (totalIncorrect > 0) QuestionResponse.formatSecondsDisplay(averageIncorrectMillis) else "—"

    val formattedTotalDuration: String
        get() = QuestionResponse.formatDuration(totalSessionDurationMillis)

    val formattedAvgTimePerQuestion: String
        get() = if (averageTimePerQuestionMillis > 0L) QuestionResponse.formatSecondsDisplay(averageTimePerQuestionMillis) else "—"

    fun calculateNetScore(markCorrect: Float, markNegative: Float): Float {
        return (totalCorrect * markCorrect) - (totalIncorrect * markNegative)
    }

    fun calculateMaxPossibleScore(markCorrect: Float): Float {
        return (totalAnswered + totalSkipped) * markCorrect
    }
}
