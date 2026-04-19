package ali.paf.contacts.account

import android.accounts.*
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

class AddressBookAuthenticatorService : Service() {

    private lateinit var authenticator: AddressBookAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = AddressBookAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder

    class AddressBookAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {

        override fun addAccount(
            response: AccountAuthenticatorResponse, accountType: String,
            authTokenType: String?, requiredFeatures: Array<String>?, options: Bundle?
        ): Bundle = throw UnsupportedOperationException("Address-book accounts are created programmatically.")

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
