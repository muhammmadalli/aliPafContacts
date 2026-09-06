package ali.paf.contacts.ui

import ali.paf.contacts.account.AccountConfig
import android.accounts.Account
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ali.paf.contacts.account.AccountRepository
import ali.paf.contacts.sync.SyncStatusStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val accountRepository: AccountRepository
) : AndroidViewModel(application) {

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts
    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage
    private val syncStatusStore = SyncStatusStore(application)
    private val _lastAttemptStatus = MutableStateFlow(syncStatusStore.lastAttemptStatus())
    val lastAttemptStatus: StateFlow<String> = _lastAttemptStatus.asStateFlow()
    private val _syncingAccountNames = MutableStateFlow<Set<String>>(emptySet())
    val syncingAccountNames: StateFlow<Set<String>> = _syncingAccountNames.asStateFlow()


    fun performAutoSetup() {
        viewModelScope.launch(Dispatchers.IO) {
            val success = accountRepository.createMainAccount(
                name = "Sync ${AccountConfig.HARDCODED_USERNAME}",
                baseUrl = AccountConfig.HARDCODED_BASE_URL,
                username = AccountConfig.HARDCODED_USERNAME,
                password = AccountConfig.HARDCODED_PASSWORD
            )
            if (success) {
                val mainAccount = Account("Sync ${AccountConfig.HARDCODED_USERNAME}", AccountConfig.ACCOUNT_TYPE)
                accountRepository.createOrUpdateAddressBook(
                    mainAccount,
                    AccountConfig.HARDCODED_ADDRESSBOOK_URL,
                    AccountConfig.HARDCODED_DISPLAY_NAME
                )
                accountRepository.syncNowDirect(mainAccount, forceResync = true)
                refresh() // Update the UI
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _accounts.value = withContext(Dispatchers.IO) { accountRepository.getMainAccounts() }
        }
    }

    fun removeAccount(account: Account) {
        viewModelScope.launch(Dispatchers.IO) { accountRepository.removeMainAccount(account); refresh() }
    }

    fun syncNow(account: Account) {
        viewModelScope.launch(Dispatchers.IO) {
            _syncingAccountNames.value = _syncingAccountNames.value + account.name
            // A normal manual sync should use the saved sync token/ETags just like a
            // scheduled sync. Force resync is reserved for recovery or setup flows.
            val result = accountRepository.syncNowDirect(account)
            _lastAttemptStatus.value = syncStatusStore.lastAttemptStatus()
            _syncMessage.value = result.fold(
                onSuccess = { "Synced $it address book(s) for ${account.name}." },
                onFailure = { "Sync failed for ${account.name}: ${it.message ?: "Unknown error"}" }
            )
            _syncingAccountNames.value = _syncingAccountNames.value - account.name
        }
    }

    fun lastSuccessfulSync(account: Account): Long = syncStatusStore.lastSuccessfulSync(account)

    fun refreshLastAttemptStatus() {
        _lastAttemptStatus.value = syncStatusStore.lastAttemptStatus()
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }
}
