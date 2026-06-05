package com.persianai.assistant.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.persianai.assistant.R
import com.persianai.assistant.utils.FormatUtils
import com.persianai.assistant.data.Transaction
import com.persianai.assistant.data.TransactionType
import com.persianai.assistant.utils.JalaliCalendar
import java.text.SimpleDateFormat
import java.util.*

class TransactionAdapter(
    private val transactions: MutableList<Transaction>,
    private val onDeleteClick: (Transaction) -> Unit,
    private val onEditClick: ((Transaction) -> Unit)? = null
) : RecyclerView.Adapter<TransactionAdapter.TransactionViewHolder>() {

    inner class TransactionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val typeIcon: TextView = view.findViewById(R.id.typeIcon)
        val description: TextView = view.findViewById(R.id.transactionDescription)
        val amount: TextView = view.findViewById(R.id.transactionAmount)
        val date: TextView = view.findViewById(R.id.transactionDate)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
        val editButton: ImageButton? = view.findViewById(R.id.editButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val transaction = transactions[position]
        
        // آیکون و رنگ بر اساس نوع
        when (transaction.type) {
            TransactionType.INCOME -> {
                holder.typeIcon.text = "💰"
                holder.amount.setTextColor(holder.itemView.context.getColor(android.R.color.holo_green_dark))
                holder.amount.text = "+${FormatUtils.formatMoney(transaction.amount)} تومان"
            }
            TransactionType.EXPENSE -> {
                holder.typeIcon.text = "💸"
                holder.amount.setTextColor(holder.itemView.context.getColor(android.R.color.holo_red_dark))
                holder.amount.text = "-${FormatUtils.formatMoney(transaction.amount)} تومان"
            }
            TransactionType.CHECK_IN -> {
                holder.typeIcon.text = "📝"
                holder.amount.setTextColor(holder.itemView.context.getColor(android.R.color.holo_green_light))
                holder.amount.text = "+${FormatUtils.formatMoney(transaction.amount)} تومان"
            }
            TransactionType.CHECK_OUT -> {
                holder.typeIcon.text = "📄"
                holder.amount.setTextColor(holder.itemView.context.getColor(android.R.color.holo_orange_dark))
                holder.amount.text = "-${FormatUtils.formatMoney(transaction.amount)} تومان"
            }
            TransactionType.INSTALLMENT -> {
                holder.typeIcon.text = "📊"
                holder.amount.setTextColor(holder.itemView.context.getColor(android.R.color.holo_blue_dark))
                holder.amount.text = "-${FormatUtils.formatMoney(transaction.amount)} تومان"
            }
        }
        
        // توضیحات
        holder.description.text = if (transaction.description.isNotEmpty()) {
            transaction.description
        } else {
            transaction.category
        }
        
        // تاریخ (شمسی)
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = transaction.date }
        val persianDate = com.persianai.assistant.utils.PersianDateConverter.gregorianToPersian(
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        )
        val timeFormat = SimpleDateFormat("HH:mm", Locale("fa"))
        holder.date.text = "${persianDate.toReadableString()} - ${timeFormat.format(Date(transaction.date))}"
        
        // دکمه حذف
        holder.deleteButton.setOnClickListener {
            onDeleteClick(transaction)
        }
        
        // دکمه ویرایش
        holder.editButton?.setOnClickListener {
            onEditClick?.invoke(transaction)
        }
    }

    override fun getItemCount() = transactions.size

    fun removeItem(transaction: Transaction) {
        val position = transactions.indexOf(transaction)
        if (position != -1) {
            transactions.removeAt(position)
            notifyItemRemoved(position)
        }
    }

}
