// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.ui.lap

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.floatingapp.india.R
import com.floatingapp.india.databinding.ItemLapResponseBinding
import com.floatingapp.india.model.OptionChoice
import com.floatingapp.india.model.QuestionResponse
import com.floatingapp.india.model.VerificationStatus
import com.google.android.material.color.MaterialColors

/**
 * LapAdapter displays recorded question responses with split timings, pacing spike tags,
 * and post-pause one-touch verification toggles with visual state locking and highlighting.
 */
class LapAdapter(
    private val onVerificationChanged: (questionNumber: Int, status: VerificationStatus) -> Unit
) : ListAdapter<QuestionResponse, LapAdapter.LapViewHolder>(DiffCallback) {

    var isPaused: Boolean = false
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LapViewHolder {
        val binding = ItemLapResponseBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LapViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LapViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LapViewHolder(
        private val binding: ItemLapResponseBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: QuestionResponse) {
            val context = itemView.context

            // Question index formatting
            binding.tvQuestionIndex.text = String.format("Q%02d", item.questionNumber)

            // Option / Skip badge
            if (item.selectedOption == OptionChoice.SKIP) {
                binding.tvSelectedOption.text = "SKP"
                binding.tvSelectedOption.setBackgroundResource(R.drawable.bg_badge_skip)
                binding.tvSelectedOption.setTextColor(
                    ContextCompat.getColor(context, R.color.md_theme_light_onSecondaryContainer)
                )
            } else {
                binding.tvSelectedOption.text = item.selectedOption.label
                binding.tvSelectedOption.setBackgroundResource(R.drawable.bg_badge_option)
                binding.tvSelectedOption.setTextColor(
                    ContextCompat.getColor(context, R.color.md_theme_light_onSecondaryContainer)
                )
            }

            // Split duration & overtime spike tag
            binding.tvSplitTime.text = "+${item.formattedSplit}"
            binding.tvCumulativeTime.text = "Total: ${item.formattedCumulative}"

            if (item.isOvertime) {
                binding.tvSpikeTag.visibility = View.VISIBLE
                binding.tvSpikeTag.text = String.format("+%ds over", item.overtimeSeconds)
            } else {
                binding.tvSpikeTag.visibility = View.GONE
            }

            // Post-pause verification controls & state highlighting
            if (isPaused) {
                binding.layoutVerification.visibility = View.VISIBLE
                applyRowStyling(binding, item)

                if (item.selectedOption != OptionChoice.SKIP) {
                    binding.btnVerifyCorrect.setOnClickListener {
                        val pos = bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                        val currentItem = getItem(pos)

                        // Debounce & Selection Locking:
                        // If already in CORRECT state, ignore tap (no-op). Do not re-trigger animations, re-bindings, or metrics.
                        if (currentItem.verificationStatus == VerificationStatus.CORRECT) {
                            return@setOnClickListener
                        }

                        // Provide subtle haptic feedback (short 10ms click) on successful state changes
                        triggerHapticFeedback(it)

                        // Transition state and update row background
                        currentItem.verificationStatus = VerificationStatus.CORRECT
                        applyRowStyling(binding, currentItem)
                        onVerificationChanged(currentItem.questionNumber, VerificationStatus.CORRECT)
                    }

                    binding.btnVerifyWrong.setOnClickListener {
                        val pos = bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                        val currentItem = getItem(pos)

                        // Debounce & Selection Locking:
                        // If already in INCORRECT state, ignore tap (no-op).
                        if (currentItem.verificationStatus == VerificationStatus.INCORRECT) {
                            return@setOnClickListener
                        }

                        // Provide subtle haptic feedback (short 10ms click) on successful state changes
                        triggerHapticFeedback(it)

                        // Transition state and update row background
                        currentItem.verificationStatus = VerificationStatus.INCORRECT
                        applyRowStyling(binding, currentItem)
                        onVerificationChanged(currentItem.questionNumber, VerificationStatus.INCORRECT)
                    }
                }
            } else {
                binding.layoutVerification.visibility = View.GONE
                val colorSurfaceContainer = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorSurfaceContainer,
                    Color.parseColor("#1E2022")
                )
                binding.itemContainer.setBackgroundColor(colorSurfaceContainer)
            }
        }

        private fun applyRowStyling(
            binding: ItemLapResponseBinding,
            item: QuestionResponse
        ) {
            val root = binding.root

            // Resolve Material 3 dynamic semantic color tokens
            val colorSurfaceContainer = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorSurfaceContainer,
                Color.parseColor("#1E2022")
            )
            val colorPrimaryContainer = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorPrimaryContainer,
                Color.parseColor("#1B3B2B")
            )
            val colorErrorContainer = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorErrorContainer,
                Color.parseColor("#442020")
            )

            // Skip: default to a neutral gray surface tint with both buttons unset
            if (item.selectedOption == OptionChoice.SKIP) {
                binding.itemContainer.setBackgroundColor(colorSurfaceContainer)
                binding.tvVerifySkipped.visibility = View.VISIBLE
                binding.btnVerifyCorrect.visibility = View.GONE
                binding.btnVerifyWrong.visibility = View.GONE
                return
            }

            binding.tvVerifySkipped.visibility = View.GONE
            binding.btnVerifyCorrect.visibility = View.VISIBLE
            binding.btnVerifyWrong.visibility = View.VISIBLE

            val colorGreenActive = ColorStateList.valueOf(Color.BLACK)
            val colorRedActive = ColorStateList.valueOf(Color.WHITE)
            val colorDimmed = ColorStateList.valueOf(Color.parseColor("#7C8B84"))

            when (item.verificationStatus) {
                VerificationStatus.CORRECT -> {
                    // Tapped ✔ (Correct): The question item container changes its background to a muted green (?attr/colorPrimaryContainer)
                    binding.itemContainer.setBackgroundColor(colorPrimaryContainer)

                    binding.btnVerifyCorrect.setBackgroundResource(R.drawable.bg_circle_primary)
                    binding.btnVerifyCorrect.imageTintList = colorGreenActive
                    binding.btnVerifyCorrect.alpha = 1.0f

                    binding.btnVerifyWrong.setBackgroundResource(R.drawable.bg_circle_dimmed)
                    binding.btnVerifyWrong.imageTintList = colorDimmed
                    binding.btnVerifyWrong.alpha = 0.35f
                }
                VerificationStatus.INCORRECT -> {
                    // Tapped ✖ (Wrong): The question item container changes its background to a muted red (?attr/colorErrorContainer)
                    binding.itemContainer.setBackgroundColor(colorErrorContainer)

                    binding.btnVerifyCorrect.setBackgroundResource(R.drawable.bg_circle_dimmed)
                    binding.btnVerifyCorrect.imageTintList = colorDimmed
                    binding.btnVerifyCorrect.alpha = 0.35f

                    binding.btnVerifyWrong.setBackgroundResource(R.drawable.bg_circle_danger)
                    binding.btnVerifyWrong.imageTintList = colorRedActive
                    binding.btnVerifyWrong.alpha = 1.0f
                }
                else -> {
                    // Default / Unchecked: Standard neutral surface (?attr/colorSurfaceContainer)
                    binding.itemContainer.setBackgroundColor(colorSurfaceContainer)

                    binding.btnVerifyCorrect.setBackgroundResource(R.drawable.bg_circle_dimmed)
                    binding.btnVerifyCorrect.imageTintList = colorDimmed
                    binding.btnVerifyCorrect.alpha = 0.7f

                    binding.btnVerifyWrong.setBackgroundResource(R.drawable.bg_circle_dimmed)
                    binding.btnVerifyWrong.imageTintList = colorDimmed
                    binding.btnVerifyWrong.alpha = 0.7f
                }
            }
        }

        private fun triggerHapticFeedback(view: View) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val vibrator = ContextCompat.getSystemService(view.context, Vibrator::class.java)
                    vibrator?.vibrate(VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            } catch (_: Exception) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<QuestionResponse>() {
        override fun areItemsTheSame(oldItem: QuestionResponse, newItem: QuestionResponse): Boolean {
            return oldItem.questionNumber == newItem.questionNumber
        }

        override fun areContentsTheSame(oldItem: QuestionResponse, newItem: QuestionResponse): Boolean {
            return oldItem == newItem && oldItem.verificationStatus == newItem.verificationStatus
        }
    }
}
