// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.ui.lap

import android.content.res.ColorStateList
import android.graphics.Color
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

/**
 * LapAdapter displays recorded question responses with split timings, pacing spike tags,
 * and post-pause one-touch verification toggles.
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

            // Post-pause verification controls
            if (isPaused) {
                binding.layoutVerification.visibility = View.VISIBLE

                if (item.selectedOption == OptionChoice.SKIP) {
                    binding.tvVerifySkipped.visibility = View.VISIBLE
                    binding.btnVerifyCorrect.visibility = View.GONE
                    binding.btnVerifyIncorrect.visibility = View.GONE
                } else {
                    binding.tvVerifySkipped.visibility = View.GONE
                    binding.btnVerifyCorrect.visibility = View.VISIBLE
                    binding.btnVerifyIncorrect.visibility = View.VISIBLE

                    applyVerificationStyling(binding, item.verificationStatus)

                    binding.btnVerifyCorrect.setOnClickListener {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            val currentItem = getItem(pos)
                            val newStatus = if (currentItem.verificationStatus == VerificationStatus.CORRECT) {
                                VerificationStatus.UNVERIFIED
                            } else {
                                VerificationStatus.CORRECT
                            }
                            currentItem.verificationStatus = newStatus
                            applyVerificationStyling(binding, newStatus)
                            onVerificationChanged(currentItem.questionNumber, newStatus)
                        }
                    }

                    binding.btnVerifyIncorrect.setOnClickListener {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            val currentItem = getItem(pos)
                            val newStatus = if (currentItem.verificationStatus == VerificationStatus.INCORRECT) {
                                VerificationStatus.UNVERIFIED
                            } else {
                                VerificationStatus.INCORRECT
                            }
                            currentItem.verificationStatus = newStatus
                            applyVerificationStyling(binding, newStatus)
                            onVerificationChanged(currentItem.questionNumber, newStatus)
                        }
                    }
                }
            } else {
                binding.layoutVerification.visibility = View.GONE
            }
        }

        private fun applyVerificationStyling(
            binding: ItemLapResponseBinding,
            status: VerificationStatus
        ) {
            val colorGreenActive = ColorStateList.valueOf(Color.parseColor("#000000"))
            val colorRedActive = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
            val colorDimmed = ColorStateList.valueOf(Color.parseColor("#7C8B84"))

            when (status) {
                VerificationStatus.CORRECT -> {
                    // Right button turns vibrant green with dark checkmark
                    binding.btnVerifyCorrect.setBackgroundResource(R.drawable.bg_circle_primary)
                    binding.btnVerifyCorrect.imageTintList = colorGreenActive
                    binding.btnVerifyCorrect.alpha = 1.0f

                    // Wrong button dims
                    binding.btnVerifyIncorrect.setBackgroundResource(R.drawable.bg_circle_dimmed)
                    binding.btnVerifyIncorrect.imageTintList = colorDimmed
                    binding.btnVerifyIncorrect.alpha = 0.35f
                }
                VerificationStatus.INCORRECT -> {
                    // Right button dims
                    binding.btnVerifyCorrect.setBackgroundResource(R.drawable.bg_circle_dimmed)
                    binding.btnVerifyCorrect.imageTintList = colorDimmed
                    binding.btnVerifyCorrect.alpha = 0.35f

                    // Wrong button turns vibrant red with white cross
                    binding.btnVerifyIncorrect.setBackgroundResource(R.drawable.bg_circle_danger)
                    binding.btnVerifyIncorrect.imageTintList = colorRedActive
                    binding.btnVerifyIncorrect.alpha = 1.0f
                }
                else -> {
                    // Neither selected - both subtle/dimmed
                    binding.btnVerifyCorrect.setBackgroundResource(R.drawable.bg_circle_dimmed)
                    binding.btnVerifyCorrect.imageTintList = colorDimmed
                    binding.btnVerifyCorrect.alpha = 0.7f

                    binding.btnVerifyIncorrect.setBackgroundResource(R.drawable.bg_circle_dimmed)
                    binding.btnVerifyIncorrect.imageTintList = colorDimmed
                    binding.btnVerifyIncorrect.alpha = 0.7f
                }
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
