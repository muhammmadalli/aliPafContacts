package ali.paf.contacts.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import ali.paf.contacts.AliPafContactsApp
import ali.paf.contacts.R
import ali.paf.contacts.account.AccountConfig
import ali.paf.contacts.util.HttpClientFactory
import ali.paf.contacts.ui.MainActivity

class ContactsSyncAdapterService : Service() {

    private lateinit var syncAdapter: ContactsSyncAdapter

    override fun onCreate() {
        super.onCreate()
        syncAdapter = ContactsSyncAdapter(applicationContext, true)
    }

    override fun onBind(intent: android.content.Intent?) = syncAdapter.syncAdapterBinder!!

    class ContactsSyncAdapter(context: Context, autoInitialize: Boolean) :
        AbstractThreadedSyncAdapter(context, autoInitialize) {

        companion object {
            private const val TAG = "ContactsSyncAdapter"
            private const val NOTIFICATION_ID_SYNC_ERROR = 10
        }

        override fun onPerformSync(
            account: Account, extras: Bundle, authority: String,
            provider: ContentProviderClient, syncResult: SyncResult
        ) {
            Log.i(TAG, "onPerformSync for ${account.name}")
            val am = AccountManager.get(context)

            val mainAccountName = am.getUserData(account, AccountConfig.KEY_MAIN_ACCOUNT_NAME)
                ?: run { syncResult.stats.numAuthExceptions++; return }
            val mainAccountType = am.getUserData(account, AccountConfig.KEY_MAIN_ACCOUNT_TYPE)
                ?: AccountConfig.ACCOUNT_TYPE
            val mainAccount = Account(mainAccountName, mainAccountType)

            val baseUrl = am.getUserData(mainAccount, AccountConfig.KEY_BASE_URL)
                ?: run { syncResult.stats.numAuthExceptions++; return }
            val username = am.getUserData(mainAccount, AccountConfig.KEY_USERNAME)
                ?: run { syncResult.stats.numAuthExceptions++; return }
            val password = am.getPassword(mainAccount)
                ?: run { syncResult.stats.numAuthExceptions++; return }
            val collectionUrl = am.getUserData(account, AccountConfig.KEY_COLLECTION_URL)
                ?: run { syncResult.databaseError = true; return }

            val httpClient = HttpClientFactory.create(context, username, password)
            try {
                ContactsSyncManager(context, account, provider, httpClient, collectionUrl, extras).performSync()
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed for ${account.name}", e)
                syncResult.stats.numIoExceptions++
                notifySyncError(account.name, e.message ?: "Unknown error")
            }
        }

        private fun notifySyncError(accountName: String, message: String) {
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, AliPafContactsApp.NOTIFICATION_CHANNEL_SYNC)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(context.getString(R.string.notification_sync_error_title))
                .setContentText(context.getString(R.string.notification_sync_error_text, accountName))
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID_SYNC_ERROR, notification)
        }
    }
}
