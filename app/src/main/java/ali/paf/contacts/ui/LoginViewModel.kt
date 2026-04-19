package ali.paf.contacts.ui

import android.accounts.Account
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ali.paf.contacts.account.AccountRepository
import ali.paf.contacts.data.AddressBookCollection
import ali.paf.contacts.data.CardDavDiscovery
import ali.paf.contacts.util.HttpClientFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    application: Application,
    private val accountRepository: AccountRepository
) : AndroidViewModel(application) {

    companion object { private const val TAG = "LoginViewModel" }

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        data class Success(val mainAccount: Account, val addressBooks: List<AddressBookCollection>) : LoginState()
        data class Error(val message: String) : LoginState()
    }

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state

    fun login(rawUrl: String, username: String, password: String) {
        if (rawUrl.isBlank() || username.isBlank() || password.isBlank()) {
            _state.value = LoginState.Error("Please fill in all fields."); return
        }
        val baseUrl = normaliseUrl(rawUrl) ?: run {
            _state.value = LoginState.Error("Invalid URL — include https://"); return
        }
        _state.value = LoginState.Loading
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val httpClient = HttpClientFactory.create(getApplication(), username, password)
                    val addressBooks = CardDavDiscovery(httpClient).discoverAddressBooks(baseUrl, username)
                    val accountName = "$username@${URI(baseUrl).host}"
                    if (!accountRepository.createMainAccount(accountName, baseUrl, username, password))
                        throw IllegalStateException("Account '$accountName' already exists.")
                    Pair(Account(accountName, "ali.paf.contacts"), addressBooks)
                }
                Log.i(TAG, "Login success, ${result.second.size} address books found")
                _state.value = LoginState.Success(result.first, result.second)
            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                _state.value = LoginState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() { _state.value = LoginState.Idle }

    private fun normaliseUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        val withScheme = if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) trimmed else "https://$trimmed"
        return try { URI(withScheme).toString() } catch (e: Exception) { null }
    }
}
