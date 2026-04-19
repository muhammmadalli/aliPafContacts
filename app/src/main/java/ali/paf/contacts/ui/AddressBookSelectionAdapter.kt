package ali.paf.contacts.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ali.paf.contacts.data.AddressBookCollection
import ali.paf.contacts.databinding.ItemAddressbookBinding

class AddressBookSelectionAdapter(
    private val items: List<AddressBookCollection>
) : RecyclerView.Adapter<AddressBookSelectionAdapter.ViewHolder>() {

    private val checked = BooleanArray(items.size) { true }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAddressbookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position], checked[position])
    override fun getItemCount() = items.size

    fun getSelected(): List<AddressBookCollection> = items.filterIndexed { i, _ -> checked[i] }

    inner class ViewHolder(private val binding: ItemAddressbookBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(ab: AddressBookCollection, isChecked: Boolean) {
            binding.checkBox.text = ab.displayName
            binding.checkBox.isChecked = isChecked
            binding.tvDescription.text = ab.description
            binding.tvDescription.visibility = if (ab.description != null) View.VISIBLE else View.GONE
            binding.tvReadOnly.visibility = if (ab.readOnly) View.VISIBLE else View.GONE
            binding.checkBox.setOnCheckedChangeListener { _, c ->
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    this@AddressBookSelectionAdapter.checked[pos] = c
                }
            }
        }
    }
}
