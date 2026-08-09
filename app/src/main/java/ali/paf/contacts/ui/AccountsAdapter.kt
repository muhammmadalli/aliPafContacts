package ali.paf.contacts.ui

import android.accounts.Account
import android.text.format.DateFormat
import android.view.View
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
    private var syncingAccountNames: Set<String> = emptySet()
    private var lastSuccessfulSyncs: Map<String, Long> = emptyMap()

    fun updateSyncStatus(syncingNames: Set<String>, successfulSyncs: Map<String, Long>) {
        syncingAccountNames = syncingNames
        lastSuccessfulSyncs = successfulSyncs
        notifyDataSetChanged()
    }

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
            val isSyncing = account.name in syncingAccountNames
            binding.syncProgress.visibility = if (isSyncing) View.VISIBLE else View.GONE
            binding.btnSync.isEnabled = !isSyncing
            val lastSync = lastSuccessfulSyncs[account.name] ?: 0L
            binding.tvLastUpdate.text = if (lastSync == 0L) {
                binding.root.context.getString(ali.paf.contacts.R.string.main_last_update_never)
            } else {
                binding.root.context.getString(
                    ali.paf.contacts.R.string.main_last_update,
                    DateFormat.format("EEEE, MMM d 'at' h:mm a", lastSync)
                )
            }
        // below delete button was removed from the layout item_account.xml in commit 28e3bdfa07b76c89f4028e1631ca52c57e703a70
        //    binding.btnRemove.setOnClickListener { onRemoveClick(account) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Account>() {
            override fun areItemsTheSame(a: Account, b: Account) = a.name == b.name
            override fun areContentsTheSame(a: Account, b: Account) = a == b
        }
    }
}
