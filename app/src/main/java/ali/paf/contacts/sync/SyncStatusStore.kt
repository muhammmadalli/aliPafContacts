package ali.paf.contacts.sync

import android.accounts.Account
import android.content.Context
import at.bitfire.dav4jvm.exception.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Stores the most recent successful sync and the outcome of the latest sync attempt. */
class SyncStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "sync_status",
        Context.MODE_PRIVATE
    )

    fun lastSuccessfulSync(account: Account): Long =
        preferences.getLong(key(account), 0L)

    fun recordSuccessfulSync(account: Account) {
        preferences.edit()
            .putLong(key(account), System.currentTimeMillis())
            .putBoolean(KEY_ATTEMPT_IN_PROGRESS, false)
            .putString(KEY_LAST_ATTEMPT_MESSAGE, "Update completed successfully.")
            .apply()
    }

    fun recordAttemptStarted(account: Account) {
        preferences.edit()
            .putBoolean(KEY_ATTEMPT_IN_PROGRESS, true)
            .putString(KEY_LAST_ATTEMPT_MESSAGE, "Updating ${account.name}…")
            .apply()
    }

    fun recordFailedSync(account: Account, error: Throwable) {
        preferences.edit()
            .putBoolean(KEY_ATTEMPT_IN_PROGRESS, false)
            .putString(KEY_LAST_ATTEMPT_MESSAGE, describeFailure(error))
            .apply()
    }

    /**
     * Returns the latest attempt message. If the app was terminated mid-sync, there is no
     * callback from Android; keep the persisted in-progress marker and explain that outcome.
     */
    fun lastAttemptStatus(): String {
        if (preferences.getBoolean(KEY_ATTEMPT_IN_PROGRESS, false)) {
            val interruptedMessage = "Previous update was interrupted. Android may have stopped it because of battery restrictions."
            preferences.edit()
                .putBoolean(KEY_ATTEMPT_IN_PROGRESS, false)
                .putString(KEY_LAST_ATTEMPT_MESSAGE, interruptedMessage)
                .apply()
            return interruptedMessage
        }
        return preferences.getString(KEY_LAST_ATTEMPT_MESSAGE, null)
            ?: "No update attempts yet."
    }

    private fun key(account: Account): String = "${account.type}:${account.name}"

    companion object {
        private const val KEY_ATTEMPT_IN_PROGRESS = "last_attempt_in_progress"
        private const val KEY_LAST_ATTEMPT_MESSAGE = "last_attempt_message"

        fun describeFailure(error: Throwable): String {
            val cause = generateSequence(error) { it.cause }.firstOrNull { it is HttpException }
            if (cause is HttpException) {
                return when (cause.code) {
                    403 -> "HTTP 403: Access forbidden. Check your credentials and permissions."
                    404 -> "HTTP 404: Address book was not found."
                    500 -> "HTTP 500: Server error. Try again later."
                    else -> "HTTP ${cause.code}: Server request failed."
                }
            }
            val networkCause = generateSequence(error) { it.cause }.firstOrNull {
                it is UnknownHostException || it is ConnectException || it is SocketTimeoutException
            }
            return when (networkCause) {
                is UnknownHostException -> "Could not connect to the server: host not found."
                is ConnectException -> "Could not connect to the server."
                is SocketTimeoutException -> "Could not connect to the server: connection timed out."
                else -> "Update failed: ${error.message ?: "Unknown error"}"
            }
        }
    }
}
