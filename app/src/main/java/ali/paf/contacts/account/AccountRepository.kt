package ali.paf.contacts.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.os.Bundle
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(private val context: Context) {

    companion object { private const val TAG = "AccountRepository" }

    private val accountManager: AccountManager = AccountManager.get(context)

    fun getMainAccounts(): List<Account> =
        accountManager.getAccountsByType(AccountConfig.ACCOUNT_TYPE).toList()

    fun createMainAccount(name: String, baseUrl: String, username: String, password: String): Boolean {
        val account = Account(name, AccountConfig.ACCOUNT_TYPE)
        val userData = Bundle().apply {
            putString(AccountConfig.KEY_BASE_URL, baseUrl)
            putString(AccountConfig.KEY_USERNAME, username)
        }
        val added = accountManager.addAccountExplicitly(account, password, userData)
        if (!added) Log.w(TAG, "Could not add account $name — already exists?")
        return added
    }

    fun removeMainAccount(account: Account) {
        getAddressBookAccounts(account).forEach { removeAddressBookAccount(it) }
        accountManager.removeAccountExplicitly(account)
        Log.i(TAG, "Removed main account ${account.name}")
    }

    fun getBaseUrl(account: Account): String =
        accountManager.getUserData(account, AccountConfig.KEY_BASE_URL) ?: ""

    fun getUsername(account: Account): String =
        accountManager.getUserData(account, AccountConfig.KEY_USERNAME) ?: ""

    fun getPassword(account: Account): String =
        accountManager.getPassword(account) ?: ""

    fun getAddressBookAccounts(mainAccount: Account): List<Account> =
        accountManager.getAccountsByType(AccountConfig.ACCOUNT_TYPE_ADDRESS_BOOK)
            .filter { ab ->
                accountManager.getUserData(ab, AccountConfig.KEY_MAIN_ACCOUNT_NAME) == mainAccount.name &&
                accountManager.getUserData(ab, AccountConfig.KEY_MAIN_ACCOUNT_TYPE) == mainAccount.type
            }

    fun createOrUpdateAddressBook(mainAccount: Account, collectionUrl: String, displayName: String): Account {
        val abName = "$displayName (${mainAccount.name})"
        val abAccount = Account(abName, AccountConfig.ACCOUNT_TYPE_ADDRESS_BOOK)
        val userData = Bundle().apply {
            putString(AccountConfig.KEY_COLLECTION_URL, collectionUrl)
            putString(AccountConfig.KEY_MAIN_ACCOUNT_NAME, mainAccount.name)
            putString(AccountConfig.KEY_MAIN_ACCOUNT_TYPE, mainAccount.type)
            putString(AccountConfig.KEY_DISPLAY_NAME, displayName)
        }
        if (!accountManager.addAccountExplicitly(abAccount, null, userData)) {
            accountManager.setUserData(abAccount, AccountConfig.KEY_COLLECTION_URL, collectionUrl)
            accountManager.setUserData(abAccount, AccountConfig.KEY_DISPLAY_NAME, displayName)
            Log.d(TAG, "Updated existing address-book account: $abName")
        } else {
            Log.i(TAG, "Created address-book account: $abName")
        }
        ContentResolver.setSyncAutomatically(abAccount, "com.android.contacts", true)
        ContentResolver.addPeriodicSync(abAccount, "com.android.contacts", Bundle.EMPTY, 4 * 60 * 60L)
        return abAccount
    }

    fun removeAddressBookAccount(abAccount: Account) {
        ContentResolver.removePeriodicSync(abAccount, "com.android.contacts", Bundle.EMPTY)
        accountManager.removeAccountExplicitly(abAccount)
        Log.i(TAG, "Removed address-book account: ${abAccount.name}")
    }

    fun requestSync(mainAccount: Account) {
        getAddressBookAccounts(mainAccount).forEach { ab ->
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            ContentResolver.requestSync(ab, "com.android.contacts", extras)
        }
    }
}
