package ali.paf.contacts.sync

import android.accounts.Account
import android.content.Context

/** Stores the most recent fully successful sync for each main account. */
class SyncStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "sync_status",
        Context.MODE_PRIVATE
    )

    fun lastSuccessfulSync(account: Account): Long =
        preferences.getLong(key(account), 0L)

    fun recordSuccessfulSync(account: Account) {
        preferences.edit().putLong(key(account), System.currentTimeMillis()).apply()
    }

    private fun key(account: Account): String = "${account.type}:${account.name}"
}
