package ali.paf.contacts.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import ali.paf.contacts.sync.ContactsSyncManager
import ali.paf.contacts.sync.SyncStatusStore
import ali.paf.contacts.util.HttpClientFactory
import java.util.Random
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
        val abName = "${mainAccount.name} $displayName"
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
        // Some Android builds keep sync disabled in Settings until the account/authority
        // pair is explicitly marked syncable.
        ContentResolver.setIsSyncable(abAccount, ContactsContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(abAccount, ContactsContract.AUTHORITY, true)
        scheduleRandomPeriodicSync(abAccount)
        ensureContactsAreVisible(abAccount)
        return abAccount
    }

    fun removeAddressBookAccount(abAccount: Account) {
        ContentResolver.removePeriodicSync(abAccount, ContactsContract.AUTHORITY, Bundle.EMPTY)
        ContentResolver.setSyncAutomatically(abAccount, ContactsContract.AUTHORITY, false)
        ContentResolver.setIsSyncable(abAccount, ContactsContract.AUTHORITY, 0)
        accountManager.removeAccountExplicitly(abAccount)
        Log.i(TAG, "Removed address-book account: ${abAccount.name}")
    }

    fun requestSync(mainAccount: Account, forceResync: Boolean = false) {
        getAddressBookAccounts(mainAccount).forEach { ab ->
            ensureContactsAreVisible(ab)
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                if (forceResync) {
                    putBoolean(AccountConfig.SYNC_EXTRA_FORCE_RESYNC, true)
                }
            }
            ContentResolver.requestSync(ab, ContactsContract.AUTHORITY, extras)
        }
    }

    fun syncNowDirect(mainAccount: Account, forceResync: Boolean = false): Result<Int> {
        val am = AccountManager.get(context)
        val syncStatusStore = SyncStatusStore(context)
        syncStatusStore.recordAttemptStarted(mainAccount)

        return runCatching {
            val addressBooks = getAddressBookAccounts(mainAccount)
            check(addressBooks.isNotEmpty()) { "No address books found for ${mainAccount.name}" }

            val baseUrl = requireNotNull(am.getUserData(mainAccount, AccountConfig.KEY_BASE_URL)) {
                "Missing base URL for ${mainAccount.name}"
            }
            val username = requireNotNull(am.getUserData(mainAccount, AccountConfig.KEY_USERNAME)) {
                "Missing username for ${mainAccount.name}"
            }
            val password = requireNotNull(am.getPassword(mainAccount)) {
                "Missing password for ${mainAccount.name}"
            }
            var syncedCount = 0
            addressBooks.forEach { ab ->
                ensureContactsAreVisible(ab)
                val collectionUrl = am.getUserData(ab, AccountConfig.KEY_COLLECTION_URL)
                    ?: error("Missing collection URL for ${ab.name}")
                val extras = Bundle().apply {
                    if (forceResync) putBoolean(AccountConfig.SYNC_EXTRA_FORCE_RESYNC, true)
                }
                val provider = requireNotNull(
                    context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
                ) { "Could not acquire contacts provider" }
                provider.use {
                    val httpClient = HttpClientFactory.create(context, username, password)
                    ContactsSyncManager(context, ab, it, httpClient, collectionUrl, extras).performSync()
                    syncedCount++
                }
            }
            syncStatusStore.recordSuccessfulSync(mainAccount)
            syncedCount
        }.onFailure { syncStatusStore.recordFailedSync(mainAccount, it) }
    }

    fun scheduleRandomPeriodicSync(account: Account) {
        val minSeconds = AccountConfig.SYNC_MIN_DAYS * 24 * 60 * 60L
        val maxSeconds = AccountConfig.SYNC_MAX_DAYS * 24 * 60 * 60L
        val intervalSeconds = minSeconds + (Random().nextDouble() * (maxSeconds - minSeconds)).toLong()

        Log.i(TAG, "Scheduling periodic sync for ${account.name} in ${intervalSeconds / (24 * 60 * 60)} days ($intervalSeconds s)")
        ContentResolver.addPeriodicSync(account, ContactsContract.AUTHORITY, Bundle.EMPTY, intervalSeconds)
    }

    private fun ensureContactsAreVisible(account: Account) {
        // Double check permissions to avoid crash if called prematurely
        val hasRead = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasWrite = context.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasRead || !hasWrite) {
            Log.w(TAG, "Cannot ensure contacts visibility: Missing permissions")
            return
        }

        try {
            val values = ContentValues().apply {
                put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
                put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
                put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
                put(ContactsContract.Settings.SHOULD_SYNC, 1)
            }

            val where = "${ContactsContract.Settings.ACCOUNT_NAME}=? AND ${ContactsContract.Settings.ACCOUNT_TYPE}=?"
            val args = arrayOf(account.name, account.type)
            val updated = context.contentResolver.update(ContactsContract.Settings.CONTENT_URI, values, where, args)
            if (updated == 0) {
                context.contentResolver.insert(ContactsContract.Settings.CONTENT_URI, values)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while ensuring contacts visibility", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring contacts visibility", e)
        }
    }
}
