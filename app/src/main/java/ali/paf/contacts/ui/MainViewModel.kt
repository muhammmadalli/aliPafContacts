package ali.paf.contacts.ui

import android.accounts.Account
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ali.paf.contacts.account.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    fun refresh() {
        viewModelScope.launch {
            _accounts.value = withContext(Dispatchers.IO) { accountRepository.getMainAccounts() }
        }
    }

    fun removeAccount(account: Account) {
        viewModelScope.launch(Dispatchers.IO) { accountRepository.removeMainAccount(account); refresh() }
    }

    fun syncNow(account: Account) { accountRepository.requestSync(account) }
}
