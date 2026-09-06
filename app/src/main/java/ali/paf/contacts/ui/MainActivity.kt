package ali.paf.contacts.ui

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
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
        } else {
            // Permissions granted, trigger refresh/setup if needed
            viewModel.refresh()
            if (viewModel.accounts.value.isEmpty()) {
                viewModel.performAutoSetup()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setTitle(R.string.main_title)

        val hasReadContacts = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val hasWriteContacts = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasReadContacts || !hasWriteContacts) {
            requestContactsPermissions.launch(
                arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS
                )
            )
        }

        checkBatteryOptimizations()

        setupInstructionsWithIcon()

        // UNUSED APP RESTRICTION PERMISSION IS REMOVED NOW - THE PERMISSION WILL BE GRANTED BY MDM
        // checkUnusedAppRestrictions()

        adapter = AccountsAdapter(
            onSyncClick = { viewModel.syncNow(it) },
            onRemoveClick = { confirmRemove(it) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnAppInfo.setOnClickListener {
            startActivity(Intent(this, AppInfoActivity::class.java))
        }

        /* ADD ACCOUNT BUTTON CURRENTLY REMOVED FROM THE UI

        binding.fabAddAccount.setOnClickListener {
            AccountManager.get(this)
                .addAccount("ali.paf.contacts", null, null, null, this, null, null)
        }

         */

        lifecycleScope.launch {
            viewModel.accounts.collect { accounts ->
                adapter.submitList(accounts)
                adapter.updateSyncStatus(
                    viewModel.syncingAccountNames.value,
                    accounts.associate { account -> account.name to viewModel.lastSuccessfulSync(account) }
                )
                if (accounts.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                    // Trigger auto-setup only if permissions are already granted
                    val hasReadContacts = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.READ_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasWriteContacts = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.WRITE_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasReadContacts && hasWriteContacts) {
                        viewModel.performAutoSetup()
                    }
                } else {
                    binding.tvEmpty.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.syncingAccountNames.collect { syncingNames ->
                val lastSuccessfulSyncs = adapter.currentList.associate { account ->
                    account.name to viewModel.lastSuccessfulSync(account)
                }
                adapter.updateSyncStatus(syncingNames, lastSuccessfulSyncs)
            }
        }

        lifecycleScope.launch {
            viewModel.syncMessage.collect { message ->
                if (message.isNullOrEmpty()) return@collect
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                viewModel.clearSyncMessage()
            }
        }

        lifecycleScope.launch {
            viewModel.lastAttemptStatus.collect { status ->
                binding.tvSyncStatus.text = getString(R.string.main_last_attempt, status)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        viewModel.refreshLastAttemptStatus()
        adapter.updateSyncStatus(
            viewModel.syncingAccountNames.value,
            adapter.currentList.associate { account ->
                account.name to viewModel.lastSuccessfulSync(account)
            }
        )
    }

    private fun checkBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.battery_opt_title)
                .setMessage(R.string.battery_opt_message)
                .setCancelable(false)
                .setPositiveButton(R.string.battery_opt_positive) { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            //    .setNegativeButton(R.string.battery_opt_negative, null)     //disabled negative button
                .show()
        }
    }


    /* UNUSED APP RESTRICTION PERMISSION IS REMOVED NOW - THE PERMISSION WILL BE GRANTED BY MDM

    private fun checkUnusedAppRestrictions() {
        val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(this)
        future.addListener({
            val status = try { future.get() } catch (_: Exception) { UnusedAppRestrictionsConstants.ERROR }
            if (status == UnusedAppRestrictionsConstants.API_30 ||
                status == UnusedAppRestrictionsConstants.API_31 ||
                status == UnusedAppRestrictionsConstants.API_30_BACKPORT) {

                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.unused_app_restrictions_title)
                        .setMessage(R.string.unused_app_restrictions_message)
                        .setCancelable(false)
                        .setPositiveButton(R.string.unused_app_restrictions_positive) { _, _ ->
                            val intent = IntentCompat.createManageUnusedAppRestrictionsIntent(this, packageName)
                            startActivity(intent)
                        }
                    //    .setNegativeButton(R.string.battery_opt_negative, null)      //disabled negative button
                        .show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

     */

    private fun confirmRemove(account: Account) {
        AlertDialog.Builder(this)
            .setTitle(R.string.main_remove_account)
            .setMessage("Remove '${account.name}'? Local contacts will be deleted.")
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.removeAccount(account) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupInstructionsWithIcon() {
        val instructions = getText(R.string.main_activity_instructions)
        val ssb = SpannableStringBuilder(instructions)
        val targetText = "2. Pressing Sync Button"
        val plainText = instructions.toString()
        val index = plainText.indexOf(targetText)
        if (index != -1) {
            val insertIndex = index + targetText.length
            val drawable = ContextCompat.getDrawable(this, R.drawable.syncbtn2)
            drawable?.let {
                // Size it relative to the text size
                val size = (binding.mainInstructionsText.textSize * 1.2).toInt()
                it.setBounds(0, 0, size, size)
                val imageSpan = ImageSpan(it, ImageSpan.ALIGN_BOTTOM)
                ssb.insert(insertIndex, "  ")
                ssb.setSpan(imageSpan, insertIndex + 1, insertIndex + 2, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                binding.mainInstructionsText.text = ssb
            }
        }
    }
}
