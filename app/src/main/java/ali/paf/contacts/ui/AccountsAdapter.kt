package ali.paf.contacts.ui

import android.accounts.Account
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ali.paf.contacts.databinding.ItemAccountBinding

class AccountsAdapter(
    private val onSyncClick: (Account) -> Unit,
    private val onRemoveClick: (Account) -> Unit
) : ListAdapter<Account, AccountsAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemAccountBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(account: Account) {
            binding.tvAccountName.text = account.name
            binding.btnSync.setOnClickListener { onSyncClick(account) }
            binding.btnRemove.setOnClickListener { onRemoveClick(account) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Account>() {
            override fun areItemsTheSame(a: Account, b: Account) = a.name == b.name
            override fun areContentsTheSame(a: Account, b: Account) = a == b
        }
    }
}
