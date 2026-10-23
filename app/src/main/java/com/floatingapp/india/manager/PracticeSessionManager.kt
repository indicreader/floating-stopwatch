// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.manager

import com.floatingapp.india.model.OptionChoice
import com.floatingapp.india.model.QuestionResponse
import com.floatingapp.india.model.SessionMetrics
import com.floatingapp.india.model.VerificationStatus

/**
 * PracticeSessionManager maintains volatile in-memory session records, calculates
 * split durations against target time budgets, and dynamically computes headline
 * accuracy metrics.
 */
class PracticeSessionManager {

    var startingQuestionOffset: Int = 1
    var targetDurationSeconds: Int = 30

    private val _responses = mutableListOf<QuestionResponse>()
    val responses: List<QuestionResponse> get() = _responses

    private var lastRecordedTimestamp: Long = 0L

    val nextQuestionNumber: Int
        get() = startingQuestionOffset + _responses.size

    val activeSplitBaseTimestamp: Long
        get() = lastRecordedTimestamp

    /**
     * Records an option or skip lap.
     * @param option Tapped option (A, B, C, D, or SKIP)
     * @param currentElapsedMillis Total cumulative stopwatch elapsed milliseconds
     */
    fun recordLap(option: OptionChoice, currentElapsedMillis: Long): QuestionResponse {
        val split = if (_responses.isEmpty()) {
            currentElapsedMillis
        } else {
            currentElapsedMillis - lastRecordedTimestamp
        }

        lastRecordedTimestamp = currentElapsedMillis

        val currentQNum = nextQuestionNumber
        val response = QuestionResponse(
            questionNumber = currentQNum,
            selectedOption = option,
            splitDurationMillis = split,
            cumulativeTimeMillis = currentElapsedMillis,
            targetDurationSeconds = targetDurationSeconds
        )

        // Add in chronological order so questions appear in the order answered (e.g., 1 to 98)
        _responses.add(response)
        return response
    }

    /**
     * Updates post-pause verification status for a specific question.
     */
    fun updateVerification(questionNumber: Int, status: VerificationStatus) {
        val index = _responses.indexOfFirst { it.questionNumber == questionNumber }
        if (index != -1) {
            val current = _responses[index]
            _responses[index] = current.copy(verificationStatus = status)
        }
    }

    /**
     * Updates recorded option for a question while preserving original split duration.
     */
    fun updateOption(questionNumber: Int, newOption: OptionChoice): QuestionResponse? {
        val index = _responses.indexOfFirst { it.questionNumber == questionNumber }
        if (index != -1) {
            val current = _responses[index]
            val newVerification = if (newOption == OptionChoice.SKIP) {
                VerificationStatus.SKIPPED
            } else if (current.verificationStatus == VerificationStatus.SKIPPED) {
                VerificationStatus.UNVERIFIED
            } else {
                current.verificationStatus
            }
            val updated = current.copy(selectedOption = newOption, verificationStatus = newVerification)
            _responses[index] = updated
            return updated
        }
        return null
    }

    /**
     * Toggles the bookmark/review flag for a question.
     */
    fun toggleFlag(questionNumber: Int): Boolean {
        val index = _responses.indexOfFirst { it.questionNumber == questionNumber }
        if (index != -1) {
            val current = _responses[index]
            val updated = current.copy(isFlagged = !current.isFlagged)
            _responses[index] = updated
            return updated.isFlagged
        }
        return false
    }

    /**
     * Replaces the option of the last recorded lap (for immediate long-press correction during drill).
     */
    fun overrideLastLapOption(newOption: OptionChoice): QuestionResponse? {
        if (_responses.isNotEmpty()) {
            val last = _responses.last()
            return updateOption(last.questionNumber, newOption)
        }
        return null
    }

    /**
     * Dynamically calculates headline accuracy, average times, and question counts.
     */
    fun calculateMetrics(totalElapsedMillis: Long = 0L): SessionMetrics {
        var correctCount = 0
        var incorrectCount = 0
        var skippedCount = 0
        var flaggedCount = 0
        var correctDurationSum = 0L
        var incorrectDurationSum = 0L

        for (item in _responses) {
            if (item.isFlagged) {
                flaggedCount++
            }
            when (item.verificationStatus) {
                VerificationStatus.CORRECT -> {
                    correctCount++
                    correctDurationSum += item.splitDurationMillis
                }
                VerificationStatus.INCORRECT -> {
                    incorrectCount++
                    incorrectDurationSum += item.splitDurationMillis
                }
                VerificationStatus.SKIPPED -> {
                    skippedCount++
                }
                VerificationStatus.UNVERIFIED -> {
                    if (item.selectedOption == OptionChoice.SKIP) {
                        skippedCount++
                    }
                }
            }
        }

        val totalVerified = correctCount + incorrectCount
        val accuracy = if (totalVerified > 0) (correctCount * 100) / totalVerified else 0
        val avgCorrect = if (correctCount > 0) correctDurationSum / correctCount else 0L
        val avgIncorrect = if (incorrectCount > 0) incorrectDurationSum / incorrectCount else 0L

        val topSinks = _responses.sortedByDescending { it.splitDurationMillis }.take(3)

        val effectiveTotalElapsed = if (totalElapsedMillis > 0L) {
            totalElapsedMillis
        } else {
            _responses.lastOrNull()?.cumulativeTimeMillis ?: 0L
        }

        val avgTimePerQuestion = if (_responses.isNotEmpty()) {
            effectiveTotalElapsed / _responses.size
        } else {
            0L
        }

        return SessionMetrics(
            accuracyPercentage = accuracy,
            averageTimePerQuestionMillis = avgTimePerQuestion,
            averageCorrectMillis = avgCorrect,
            averageIncorrectMillis = avgIncorrect,
            totalAnswered = _responses.size - skippedCount,
            totalCorrect = correctCount,
            totalIncorrect = incorrectCount,
            totalSkipped = skippedCount,
            totalFlagged = flaggedCount,
            topTimeSinks = topSinks,
            totalElapsedMillis = effectiveTotalElapsed
        )
    }

    /**
     * Serializes recorded session laps and pace statistics into CSV format.
     */
    fun exportToCsv(drillTag: String, totalElapsedMillis: Long = 0L): String {
        val metrics = calculateMetrics(totalElapsedMillis)
        val sb = StringBuilder()
        sb.append("question_number,selected_option,split_duration_seconds,cumulative_time_seconds,verification_status,is_flagged,average_time_per_question_seconds\n")
        val avgSec = if (_responses.isNotEmpty()) (metrics.totalElapsedMillis / _responses.size) / 1000.0 else 0.0
        for (r in _responses) {
            sb.append("${r.questionNumber},${r.selectedOption.label},${r.splitDurationMillis / 1000.0},${r.cumulativeTimeMillis / 1000.0},${r.verificationStatus.name},${r.isFlagged},${String.format(java.util.Locale.US, "%.1f", avgSec)}\n")
        }
        sb.append("\n# SUMMARY\n")
        sb.append("# drill_tag,${drillTag}\n")
        sb.append("# total_questions,${_responses.size}\n")
        sb.append("# total_time_seconds,${metrics.totalElapsedMillis / 1000.0}\n")
        sb.append("# average_time_per_question_seconds,${String.format(java.util.Locale.US, "%.1f", avgSec)}\n")
        sb.append("# accuracy_percentage,${metrics.accuracyPercentage}%\n")
        return sb.toString()
    }

    /**
     * Wipes all in-memory session records and resets base timing reference.
     */
    fun reset() {
        _responses.clear()
        lastRecordedTimestamp = 0L
    }
}
