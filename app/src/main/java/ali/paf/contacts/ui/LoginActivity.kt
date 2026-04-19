package ali.paf.contacts.ui

import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ali.paf.contacts.R
import ali.paf.contacts.data.AddressBookCollection
import ali.paf.contacts.databinding.ActivityLoginBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()
    private var authenticatorResponse: AccountAuthenticatorResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setTitle(R.string.login_title)

        authenticatorResponse = intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
        authenticatorResponse?.onRequestContinued()

        binding.btnLogin.setOnClickListener {
            viewModel.login(
                rawUrl = binding.etUrl.text.toString(),
                username = binding.etUsername.text.toString(),
                password = binding.etPassword.text.toString()
            )
        }

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is LoginViewModel.LoginState.Idle -> showIdle()
                    is LoginViewModel.LoginState.Loading -> showLoading()
                    is LoginViewModel.LoginState.Success -> onLoginSuccess(state.mainAccount.name, state.addressBooks)
                    is LoginViewModel.LoginState.Error -> showError(state.message)
                }
            }
        }
    }

    private fun showIdle() { binding.progressBar.visibility = View.GONE; binding.btnLogin.isEnabled = true }
    private fun showLoading() { binding.progressBar.visibility = View.VISIBLE; binding.btnLogin.isEnabled = false }

    private fun onLoginSuccess(accountName: String, addressBooks: List<AddressBookCollection>) {
        authenticatorResponse?.onResult(Bundle().apply {
            putString(AccountManager.KEY_ACCOUNT_NAME, accountName)
            putString(AccountManager.KEY_ACCOUNT_TYPE, "ali.paf.contacts")
        })
        startActivity(Intent(this, AddressBookActivity::class.java).apply {
            putExtra(AddressBookActivity.EXTRA_ACCOUNT_NAME, accountName)
            putParcelableArrayListExtra(AddressBookActivity.EXTRA_ADDRESS_BOOKS,
                ArrayList(addressBooks.map { ab ->
                    Bundle().apply {
                        putString("url", ab.url); putString("displayName", ab.displayName)
                        putString("description", ab.description); putBoolean("readOnly", ab.readOnly)
                    }
                })
            )
        })
        finish()
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE; binding.btnLogin.isEnabled = true
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        viewModel.reset()
    }
}
