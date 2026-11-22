package ua.pp.uaserver.lightalert.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ua.pp.uaserver.lightalert.R
import ua.pp.uaserver.lightalert.data.HourSlotRow
import ua.pp.uaserver.lightalert.databinding.ItemScheduleSlotBinding

class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.SlotViewHolder>() {

    private var items: List<HourSlotRow> = emptyList()

    fun submitList(rows: List<HourSlotRow>) {
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
        fun bind(row: HourSlotRow) {
            val ctx = b.root.context
            b.tvSlotTime.text = row.timeLabel
            b.tvSlotTime.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (row.isCurrentHour) R.color.brand_orange_dark else R.color.ink_primary
                )
            )

            styleHalf(b.tvSlotHalf1, row.firstHalfStatus, "00-30")
            styleHalf(b.tvSlotHalf2, row.secondHalfStatus, "30-00")
        }

        private fun styleHalf(view: android.widget.TextView, status: String?, label: String) {
            val ctx = view.context
            when (status) {
                "yes" -> {
                    view.text = "$label · Є"
                    view.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_on_bg))
                    view.setTextColor(ContextCompat.getColor(ctx, R.color.status_on_fg))
                }
                "no" -> {
                    view.text = "$label · Нема"
                    view.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_off_bg))
                    view.setTextColor(ContextCompat.getColor(ctx, R.color.status_off_fg))
                }
                else -> {
                    view.text = "$label · ?"
                    view.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_maybe_bg))
                    view.setTextColor(ContextCompat.getColor(ctx, R.color.status_maybe_fg))
                }
            }
        }
    }
}
