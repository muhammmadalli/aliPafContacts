package ali.paf.contacts.ui

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ali.paf.contacts.R
import ali.paf.contacts.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: AccountsAdapter

    private val requestContactsPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val hasAllPermissions = grants[Manifest.permission.READ_CONTACTS] == true &&
            grants[Manifest.permission.WRITE_CONTACTS] == true
        if (!hasAllPermissions) {
            Snackbar.make(binding.root, "Contacts permissions are required.", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setTitle(R.string.main_title)

        val hasReadContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasWriteContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!hasReadContacts || !hasWriteContacts) {
            requestContactsPermissions.launch(arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS
            ))
        }

        adapter = AccountsAdapter(
            onSyncClick = { viewModel.syncNow(it) },
            onRemoveClick = { confirmRemove(it) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fabAddAccount.setOnClickListener {
            AccountManager.get(this).addAccount("ali.paf.contacts", null, null, null, this, null, null)
        }

        lifecycleScope.launch {
            viewModel.accounts.collect { accounts ->
                adapter.submitList(accounts)
                binding.tvEmpty.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onResume() { super.onResume(); viewModel.refresh() }

    private fun confirmRemove(account: Account) {
        AlertDialog.Builder(this)
            .setTitle(R.string.main_remove_account)
            .setMessage("Remove '${account.name}'? Local contacts will be deleted.")
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.removeAccount(account) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
