package ali.paf.contacts.sync

import android.accounts.Account
import android.content.*
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import at.bitfire.dav4jvm.DavAddressBook
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.exception.ConflictException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.*
import at.bitfire.vcard4android.Contact
import at.bitfire.vcard4android.GroupMethod
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import ezvcard.VCardVersion
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.UUID

class ContactsSyncManager(
    private val context: Context,
    private val account: Account,
    private val provider: ContentProviderClient,
    private val httpClient: OkHttpClient,
    private val collectionUrl: String,
    private val extras: Bundle
) {
    companion object {
        private const val TAG = "ContactsSyncManager"
        private const val SYNC_STATE_CTAG = "ctag"
        private const val SYNC_STATE_SYNC_TOKEN = "sync_token"
        private const val CHUNK_SIZE = 15
    }

    private val davAddressBook = DavAddressBook(httpClient, collectionUrl.toHttpUrl())
    private var localSyncState: SyncState = loadSyncState()

    fun performSync() {
        Log.i(TAG, "Starting sync for ${account.name}")
        val forceResync = extras.getBoolean("force_resync", false)
        if (forceResync) {
            Log.i(TAG, "Force resync — clearing local sync state")
            localSyncState = SyncState()
        }
        try {
            uploadDirty()
            if (!forceResync && localSyncState.syncToken != null) syncWithToken()
            else syncWithPropfind()
            saveSyncState(localSyncState)
            Log.i(TAG, "Sync completed for ${account.name}")
        } catch (e: HttpException) {
            Log.e(TAG, "HTTP error during sync (code=${e.code})", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            throw e
        }
    }

    // ── Upload dirty ─────────────────────────────────────────────────────────

    private fun uploadDirty() {
        val dirtyContacts = queryDirtyContacts()
        Log.d(TAG, "Dirty contacts: ${dirtyContacts.size}")
        for (lc in dirtyContacts) {
            try {
                if (lc.deleted) deleteRemote(lc) else uploadContact(lc)
            } catch (e: ConflictException) {
                Log.w(TAG, "Conflict on ${lc.remoteFileName}, server wins")
                clearLocalDirtyFlag(lc.rawContactId)
            }
        }
    }

    private fun uploadContact(lc: LocalContactInfo) {
        val contact = readContactFromProvider(lc.rawContactId) ?: return
        val vcardBytes = ByteArrayOutputStream().also { contact.writeVCard(VCardVersion.V4_0, it) }.toByteArray()
        val fileName = lc.remoteFileName ?: "${UUID.randomUUID()}.vcf"
        val url = collectionUrl.toHttpUrl().newBuilder().addPathSegment(fileName).build()
        var newETag: String? = null
        val body = vcardBytes.toRequestBody()
        davAddressBook.put(body, ifETag = lc.eTag, ifNoneMatch = lc.remoteFileName == null, callback = { resp ->
            newETag = resp.header("ETag")?.trim('\"')
        })
        updateLocalContactMeta(lc.rawContactId, url.pathSegments.last(), newETag ?: lc.eTag, dirty = false, deleted = false)
    }

    private fun deleteRemote(lc: LocalContactInfo) {
        if (lc.remoteFileName == null) { deleteLocalContact(lc.rawContactId); return }
        try {
            davAddressBook.delete(ifETag = lc.eTag) { /* nothing to do */ }
        } catch (e: HttpException) {
            if (e.code != 404) throw e
        }
        deleteLocalContact(lc.rawContactId)
    }

    // ── Sync with collection-sync token ──────────────────────────────────────

    private fun syncWithToken() {
        Log.d(TAG, "Sync-token: ${localSyncState.syncToken}")
        val changedHrefs = mutableListOf<String>()
        val deletedHrefs = mutableListOf<String>()
        try {
            davAddressBook.reportChanges(localSyncState.syncToken!!, false, null, GetETag.NAME) { response, _ ->
                val href = response.href.toString()
                if (response.status?.code == 404 || response[GetETag::class.java] == null) deletedHrefs += href
                else changedHrefs += href
            }
        } catch (e: HttpException) {
            if (e.code == 410) {
                Log.w(TAG, "Sync token expired, falling back to PROPFIND")
                localSyncState = localSyncState.copy(syncToken = null, cTag = null)
                syncWithPropfind(); return
            }
            throw e
        }
        deletedHrefs.forEach { deleteLocalContactByRemoteFileName(it.substringAfterLast('/')) }
        downloadAndApplyContacts(changedHrefs)
        refreshSyncToken()
    }

    // ── Sync with PROPFIND ────────────────────────────────────────────────────

    private fun syncWithPropfind() {
        Log.d(TAG, "Using PROPFIND sync")
        val remoteEtags = mutableMapOf<String, String>()
        davAddressBook.propfind(1, GetETag.NAME, ResourceType.NAME) { response, relation ->
            if (relation == Response.HrefRelation.SELF) return@propfind
            val eTag = response[GetETag::class.java]?.eTag ?: return@propfind
            val fileName = response.href.pathSegments.lastOrNull { it.isNotEmpty() } ?: return@propfind
            remoteEtags[fileName] = eTag
        }
        val localEtags = getLocalContactEtags()
        val toDownload = remoteEtags.filter { (f, e) -> localEtags[f] != e }
            .keys.map { collectionUrl.toHttpUrl().newBuilder().addPathSegment(it).build().toString() }
        localEtags.keys.filter { it !in remoteEtags.keys }.forEach { deleteLocalContactByRemoteFileName(it) }
        downloadAndApplyContacts(toDownload)
        refreshCTag()
    }

    // ── Download contacts ─────────────────────────────────────────────────────

    private fun downloadAndApplyContacts(hrefs: List<String>) {
        if (hrefs.isEmpty()) return
        Log.d(TAG, "Downloading ${hrefs.size} contacts")
        hrefs.chunked(CHUNK_SIZE).forEach { chunk ->
            davAddressBook.multiget(chunk.map { it.toHttpUrl() }) { response, _ ->
                val eTag = response[GetETag::class.java]?.eTag
                val addressData = response[AddressData::class.java]
                val vcardData = addressData?.card
                val fileName = response.href.pathSegments.lastOrNull { it.isNotEmpty() }
                if (vcardData == null || fileName == null) return@multiget
                try {
                    val contacts = Contact.fromReader(StringReader(vcardData), false, null)
                    if (contacts.isNotEmpty()) applyContactToProvider(contacts.first(), fileName, eTag)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse/store $fileName", e)
                }
            }
        }
    }

    // ── Provider helpers ──────────────────────────────────────────────────────

    private data class LocalContactInfo(
        val rawContactId: Long,
        val remoteFileName: String?,
        val eTag: String?,
        val dirty: Boolean,
        val deleted: Boolean
    )

    private fun queryDirtyContacts(): List<LocalContactInfo> {
        val results = mutableListOf<LocalContactInfo>()
        provider.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.DIRTY,
                ContactsContract.RawContacts.DELETED, ContactsContract.RawContacts.SOURCE_ID,
                ContactsContract.RawContacts.SYNC1),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
            "(${ContactsContract.RawContacts.DIRTY}=1 OR ${ContactsContract.RawContacts.DELETED}=1)",
            arrayOf(account.type, account.name), null
        )?.use { cursor ->
            while (cursor.moveToNext()) results += LocalContactInfo(
                rawContactId = cursor.getLong(0), dirty = cursor.getInt(1) != 0,
                deleted = cursor.getInt(2) != 0, remoteFileName = cursor.getString(3), eTag = cursor.getString(4)
            )
        }
        return results
    }

    private fun getLocalContactEtags(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        provider.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.SOURCE_ID, ContactsContract.RawContacts.SYNC1),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
            "${ContactsContract.RawContacts.DELETED}=0",
            arrayOf(account.type, account.name), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val f = cursor.getString(0) ?: continue
                val e = cursor.getString(1) ?: continue
                map[f] = e
            }
        }
        return map
    }

    private fun readContactFromProvider(rawContactId: Long): Contact? = try {
        val values = ContentValues().apply { put(ContactsContract.RawContacts._ID, rawContactId) }
        val ac = at.bitfire.vcard4android.AndroidContact(buildAndroidAddressBook(), values)
        ac.getContact()
    } catch (e: Exception) { Log.e(TAG, "Could not read contact $rawContactId", e); null }

    private fun applyContactToProvider(contact: Contact, fileName: String, eTag: String?) {
        var existingId: Long? = null
        provider.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
            "${ContactsContract.RawContacts.SOURCE_ID}=?",
            arrayOf(account.type, account.name, fileName), null
        )?.use { if (it.moveToFirst()) existingId = it.getLong(0) }

        val ab = buildAndroidAddressBook()
        if (existingId != null) {
            val values = ContentValues().apply { put(ContactsContract.RawContacts._ID, existingId) }
            val ac = at.bitfire.vcard4android.AndroidContact(ab, values)
            ac.update(contact)
        } else {
            val ac = at.bitfire.vcard4android.AndroidContact(ab, contact, fileName, eTag)
            ac.add()
            updateLocalContactMeta(ac.id!!, fileName, eTag, dirty = false, deleted = false)
        }
    }

    private fun updateLocalContactMeta(rawContactId: Long, remoteFileName: String?, eTag: String?, dirty: Boolean, deleted: Boolean) {
        val values = ContentValues().apply {
            put(ContactsContract.RawContacts.SOURCE_ID, remoteFileName)
            put(ContactsContract.RawContacts.SYNC1, eTag)
            put(ContactsContract.RawContacts.DIRTY, if (dirty) 1 else 0)
            if (deleted) put(ContactsContract.RawContacts.DELETED, 1)
        }
        provider.update(ContactsContract.RawContacts.CONTENT_URI, values,
            "${ContactsContract.RawContacts._ID}=?", arrayOf(rawContactId.toString()))
    }

    private fun clearLocalDirtyFlag(rawContactId: Long) {
        provider.update(ContactsContract.RawContacts.CONTENT_URI,
            ContentValues().apply { put(ContactsContract.RawContacts.DIRTY, 0) },
            "${ContactsContract.RawContacts._ID}=?", arrayOf(rawContactId.toString()))
    }

    private fun deleteLocalContact(rawContactId: Long) {
        provider.delete(ContactsContract.RawContacts.CONTENT_URI,
            "${ContactsContract.RawContacts._ID}=?", arrayOf(rawContactId.toString()))
    }

    private fun deleteLocalContactByRemoteFileName(fileName: String) {
        provider.delete(ContactsContract.RawContacts.CONTENT_URI,
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
            "${ContactsContract.RawContacts.SOURCE_ID}=?",
            arrayOf(account.type, account.name, fileName))
    }

    // ── Sync state ────────────────────────────────────────────────────────────

    private data class SyncState(val cTag: String? = null, val syncToken: String? = null)

    private fun loadSyncState(): SyncState {
        var cTag: String? = null; var syncToken: String? = null
        provider.query(ContactsContract.SyncState.CONTENT_URI, arrayOf(ContactsContract.SyncState.DATA),
            "${ContactsContract.SyncState.ACCOUNT_TYPE}=? AND ${ContactsContract.SyncState.ACCOUNT_NAME}=?",
            arrayOf(account.type, account.name), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.split("\n")?.forEach { line ->
                val parts = line.split("=", limit = 2).takeIf { it.size == 2 } ?: return@forEach
                when (parts[0]) { SYNC_STATE_CTAG -> cTag = parts[1]; SYNC_STATE_SYNC_TOKEN -> syncToken = parts[1] }
            }
        }
        return SyncState(cTag, syncToken)
    }

    private fun saveSyncState(state: SyncState) {
        val raw = buildString {
            state.cTag?.let { append("$SYNC_STATE_CTAG=$it\n") }
            state.syncToken?.let { append("$SYNC_STATE_SYNC_TOKEN=$it\n") }
        }
        provider.insert(ContactsContract.SyncState.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.SyncState.ACCOUNT_TYPE, account.type)
            put(ContactsContract.SyncState.ACCOUNT_NAME, account.name)
            put(ContactsContract.SyncState.DATA, raw)
        })
    }

    private fun refreshSyncToken() {
        davAddressBook.propfind(0, SyncToken.NAME, GetCTag.NAME) { response, _ ->
            localSyncState = localSyncState.copy(
                syncToken = response[SyncToken::class.java]?.token,
                cTag = response[GetCTag::class.java]?.cTag
            )
        }
    }

    private fun refreshCTag() {
        davAddressBook.propfind(0, GetCTag.NAME, SyncToken.NAME) { response, _ ->
            localSyncState = localSyncState.copy(
                cTag = response[GetCTag::class.java]?.cTag,
                syncToken = response[SyncToken::class.java]?.token
            )
        }
    }

    private fun buildAndroidAddressBook() = at.bitfire.vcard4android.AndroidAddressBook<at.bitfire.vcard4android.AndroidContact, at.bitfire.vcard4android.AndroidGroup>(
        account, provider,
        object : at.bitfire.vcard4android.AndroidContactFactory<at.bitfire.vcard4android.AndroidContact> {
            override fun fromProvider(addressBook: at.bitfire.vcard4android.AndroidAddressBook<at.bitfire.vcard4android.AndroidContact, out at.bitfire.vcard4android.AndroidGroup>, values: ContentValues) =
                at.bitfire.vcard4android.AndroidContact(addressBook, values)
        },
        object : at.bitfire.vcard4android.AndroidGroupFactory<at.bitfire.vcard4android.AndroidGroup> {
            override fun fromProvider(addressBook: at.bitfire.vcard4android.AndroidAddressBook<out at.bitfire.vcard4android.AndroidContact, at.bitfire.vcard4android.AndroidGroup>, values: ContentValues) =
                at.bitfire.vcard4android.AndroidGroup(addressBook, values)
        }
    )
}
