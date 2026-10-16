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
    SKIP("SKIP")
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
 * Immutable entity representing a single question's recorded answer, timing, and verification status.
 */
data class QuestionResponse(
    val questionNumber: Int,                     // Question index (e.g. 1, 101)
    val selectedOption: OptionChoice,            // A, B, C, D, or SKIP
    val splitDurationMillis: Long,               // Delta duration since last lap/start
    val cumulativeTimeMillis: Long,              // Total stopwatch elapsed time at moment of tap
    val targetDurationSeconds: Int = 30,         // Target pacing budget in seconds
    var verificationStatus: VerificationStatus = if (selectedOption == OptionChoice.SKIP) VerificationStatus.SKIPPED else VerificationStatus.UNVERIFIED
) {
    val isOvertime: Boolean
        get() = splitDurationMillis > (targetDurationSeconds * 1000L)

    val overtimeSeconds: Long
        get() = if (isOvertime) (splitDurationMillis - (targetDurationSeconds * 1000L)) / 1000 else 0L

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
 * Headline summary metrics calculated across the active session.
 */
data class SessionMetrics(
    val accuracyPercentage: Int = 0,
    val averageCorrectMillis: Long = 0L,
    val averageIncorrectMillis: Long = 0L,
    val totalAnswered: Int = 0,
    val totalCorrect: Int = 0,
    val totalIncorrect: Int = 0,
    val totalSkipped: Int = 0
) {
    val formattedAvgCorrect: String
        get() = if (totalCorrect > 0) QuestionResponse.formatSecondsDisplay(averageCorrectMillis) else "—"

    val formattedAvgIncorrect: String
        get() = if (totalIncorrect > 0) QuestionResponse.formatSecondsDisplay(averageIncorrectMillis) else "—"
}
