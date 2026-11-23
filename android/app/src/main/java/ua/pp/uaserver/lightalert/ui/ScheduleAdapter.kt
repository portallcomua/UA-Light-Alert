package ua.pp.uaserver.lightalert.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ua.pp.uaserver.lightalert.R
import ua.pp.uaserver.lightalert.data.HalfHourSlotRow
import ua.pp.uaserver.lightalert.databinding.ItemScheduleSlotBinding

class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.SlotViewHolder>() {

    private var items: List<HalfHourSlotRow> = emptyList()

    fun submitList(rows: List<HalfHourSlotRow>) {
        items = rows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotViewHolder {
        val binding = ItemScheduleSlotBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SlotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SlotViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class SlotViewHolder(private val b: ItemScheduleSlotBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: HalfHourSlotRow) {
            val ctx = b.root.context
            b.tvSlotTime.text = row.timeRangeLabel
            if (row.isCurrentSlot) {
                b.tvSlotTime.setBackgroundColor(ContextCompat.getColor(ctx, R.color.brand_orange_soft))
                b.tvSlotTime.setTextColor(ContextCompat.getColor(ctx, R.color.brand_orange_dark))
                b.tvSlotTime.setTypeface(null, Typeface.BOLD)
            } else {
                b.tvSlotTime.setBackgroundColor(ContextCompat.getColor(ctx, R.color.card_bg))
                b.tvSlotTime.setTextColor(ContextCompat.getColor(ctx, R.color.ink_primary))
                b.tvSlotTime.setTypeface(null, Typeface.NORMAL)
            }

            when (row.status) {
                "yes" -> {
                    b.tvSlotStatus.text = "Світло є"
                    b.tvSlotStatus.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_on_bg))
                    b.tvSlotStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_on_fg))
                }
                "no" -> {
                    b.tvSlotStatus.text = "Світла немає"
                    b.tvSlotStatus.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_off_bg))
                    b.tvSlotStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_off_fg))
                }
                else -> {
                    b.tvSlotStatus.text = "Немає даних"
                    b.tvSlotStatus.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_maybe_bg))
                    b.tvSlotStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_maybe_fg))
                }
            }
        }
    }
}
