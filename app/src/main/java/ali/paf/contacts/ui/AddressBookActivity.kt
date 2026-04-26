package ali.paf.contacts.ui

import android.accounts.Account
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ali.paf.contacts.R
import ali.paf.contacts.account.AccountConfig
import ali.paf.contacts.account.AccountRepository
import ali.paf.contacts.data.AddressBookCollection
import ali.paf.contacts.databinding.ActivityAddressbookBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class AddressBookActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCOUNT_NAME = "account_name"
        const val EXTRA_ADDRESS_BOOKS = "address_books"
    }

    @Inject lateinit var accountRepository: AccountRepository

    private lateinit var binding: ActivityAddressbookBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddressbookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setTitle(R.string.addressbook_title)

        val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME) ?: run { finish(); return }

        val rawBundles = intent.getParcelableArrayListExtra<Bundle>(EXTRA_ADDRESS_BOOKS) ?: arrayListOf()
        val addressBooks = rawBundles.map { b ->
            AddressBookCollection(
                url = b.getString("url", ""),
                displayName = b.getString("displayName", "Address Book"),
                description = b.getString("description"),
                readOnly = b.getBoolean("readOnly", false)
            )
        }

        val mainAccount = Account(accountName, AccountConfig.ACCOUNT_TYPE)

        val adapter = AddressBookSelectionAdapter(addressBooks)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnSave.setOnClickListener {
            val selected = adapter.getSelected()
            if (selected.isEmpty()) {
                Snackbar.make(binding.root, "Select at least one address book.", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.progressBar.visibility = View.VISIBLE
            binding.btnSave.isEnabled = false
            CoroutineScope(Dispatchers.IO).launch {
                selected.forEach { ab ->
                    accountRepository.createOrUpdateAddressBook(mainAccount, ab.url, ab.displayName)
                }
                accountRepository.syncNowDirect(mainAccount, forceResync = true)
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }
}
