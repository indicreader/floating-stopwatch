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
     * Dynamically calculates headline accuracy, average times, and question counts.
     */
    fun calculateMetrics(): SessionMetrics {
        var correctCount = 0
        var incorrectCount = 0
        var skippedCount = 0
        var correctDurationSum = 0L
        var incorrectDurationSum = 0L

        for (item in _responses) {
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

        return SessionMetrics(
            accuracyPercentage = accuracy,
            averageCorrectMillis = avgCorrect,
            averageIncorrectMillis = avgIncorrect,
            totalAnswered = _responses.size - skippedCount,
            totalCorrect = correctCount,
            totalIncorrect = incorrectCount,
            totalSkipped = skippedCount
        )
    }

    /**
     * Wipes all in-memory session records and resets base timing reference.
     */
    fun reset() {
        _responses.clear()
        lastRecordedTimestamp = 0L
    }
}
