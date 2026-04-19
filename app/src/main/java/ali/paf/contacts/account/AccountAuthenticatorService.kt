package ali.paf.contacts.account

import android.accounts.*
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import ali.paf.contacts.ui.LoginActivity

class AccountAuthenticatorService : Service() {

    private lateinit var authenticator: AccountAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = AccountAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder

    class AccountAuthenticator(private val context: Context) :
        AbstractAccountAuthenticator(context) {

        override fun addAccount(
            response: AccountAuthenticatorResponse,
            accountType: String,
            authTokenType: String?,
            requiredFeatures: Array<String>?,
            options: Bundle?
        ): Bundle {
            val intent = Intent(context, LoginActivity::class.java).apply {
                putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            }
            return Bundle().apply {
                putParcelable(AccountManager.KEY_INTENT, intent)
            }
        }

        override fun editProperties(response: AccountAuthenticatorResponse, accountType: String): Bundle =
            throw UnsupportedOperationException()
        override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, options: Bundle?): Bundle? = null
        override fun getAuthToken(response: AccountAuthenticatorResponse, account: Account, authTokenType: String, options: Bundle?): Bundle =
            throw UnsupportedOperationException()
        override fun getAuthTokenLabel(authTokenType: String): String = throw UnsupportedOperationException()
        override fun updateCredentials(response: AccountAuthenticatorResponse, account: Account, authTokenType: String?, options: Bundle?): Bundle =
            throw UnsupportedOperationException()
        override fun hasFeatures(response: AccountAuthenticatorResponse, account: Account, features: Array<String>): Bundle =
            Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
