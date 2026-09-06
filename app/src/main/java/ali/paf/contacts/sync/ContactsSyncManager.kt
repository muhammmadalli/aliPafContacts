package ali.paf.contacts.sync

import android.accounts.Account
import android.content.*
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import at.bitfire.dav4jvm.DavAddressBook
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.*
import at.bitfire.vcard4android.Contact
import at.bitfire.vcard4android.GroupMethod
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.StringReader

class ContactsSyncManager(
    private val context: Context,
    private val account: Account,
    private val provider: ContentProviderClient,
    private val httpClient: OkHttpClient,
    private val collectionUrl: String,
    private val extras: Bundle
) {
    private data class CustomPhoneLabel(val number: String, val label: String)
    private data class CustomImEntry(val handle: String, val type: Int, val label: String?)
    private data class LocalContactMeta(val eTag: String, val dirty: Boolean)
    private data class ExistingImRow(
        val dataId: Long,
        val handle: String,
        val type: Int,
        val label: String?,
        val protocol: Int?,
        val customProtocol: String?
    )

    companion object {
        private const val TAG = "ContactsSyncManager"
        private const val SYNC_STATE_CTAG = "ctag"
        private const val SYNC_STATE_SYNC_TOKEN = "sync_token"
        private const val SYNC_STATE_REMOTE_INDEX = "remote_index"
        private const val SYNC_STATE_REMOTE_FILE = "remote_file"
        private const val CHUNK_SIZE = 15
        private const val LEGACY_JABBER_PROPERTY = "X-JABBER"
        private val STANDARD_PHONE_TYPES = setOf(
            "HOME", "WORK", "CELL", "VOICE", "FAX", "PAGER", "CAR", "ISDN",
            "PREF", "MSG", "BBS", "MODEM", "PCS", "VIDEO", "TEXTPHONE", "TEXTPHONE"
        )
    }

    private val accountGroups = mutableMapOf<String, Long>()
    private val davAddressBook = DavAddressBook(httpClient, collectionUrl.toHttpUrl())
    private var localSyncState: SyncState = loadSyncState()

    fun performSync() {
        Log.i(TAG, "Starting sync for ${account.name}")
        loadAccountGroups()
        val forceResync = extras.getBoolean("force_resync", false)
        if (forceResync) {
            Log.i(TAG, "Force resync — clearing local sync state")
            localSyncState = SyncState()
        }
        try {
            // Older installations have a sync token but no remote-file index.
            // Do one metadata-only collection scan to create the index; this lets
            // us restore contacts deleted while the app was not running.
            if (!forceResync && localSyncState.syncToken != null && localSyncState.remoteIndexAvailable) {
                syncWithToken()
            } else {
                syncWithPropfind()
            }
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

    // ── Sync with collection-sync token ──────────────────────────────────────

    private fun syncWithToken() {
        Log.d(TAG, "Sync-token: ${localSyncState.syncToken}")
        val changedHrefs = mutableListOf<Pair<String, String?>>()
        val deletedHrefs = mutableListOf<String>()
        try {
            davAddressBook.reportChanges(localSyncState.syncToken!!, false, null, GetETag.NAME) { response, _ ->
                val href = response.href.toString()
                if (response.status?.code == 404 || response[GetETag::class.java] == null) deletedHrefs += href
                else changedHrefs += href to response[GetETag::class.java]?.eTag
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
        val localMeta = getLocalContactMeta()
        val knownRemoteFiles = localSyncState.remoteFiles.toMutableSet()
        val deletedFileNames = deletedHrefs.map { it.substringAfterLast('/') }
        knownRemoteFiles.removeAll(deletedFileNames.toSet())
        changedHrefs.mapNotNullTo(knownRemoteFiles) { (href, _) ->
            href.toHttpUrl().pathSegments.lastOrNull { it.isNotEmpty() }
        }
        val contactsToDownload = changedHrefs
            .filter { (href, eTag) ->
                val fileName = href.toHttpUrl().pathSegments.lastOrNull { it.isNotEmpty() }
                // Some servers report an unchanged resource in a sync response. Do
                // not rewrite its contact card when its stored ETag already matches.
                eTag == null || fileName == null || localMeta[fileName]?.eTag != eTag
            }
            .map { it.first }
            .toMutableList()

        // A deletion in the Contacts app can remove the raw-contact row outright.
        // The server has no change to report in that case, so compare the retained
        // remote index with the current provider rows and fetch just the missing
        // cards.
        knownRemoteFiles
            .filter { it !in localMeta }
            .forEach { fileName ->
                val href = collectionUrl.toHttpUrl().newBuilder().addPathSegment(fileName).build().toString()
                if (href !in contactsToDownload) {
                    Log.d(TAG, "Locally missing contact found: $fileName. Restoring from server.")
                    contactsToDownload.add(href)
                }
            }

        // Supplement with contacts that have been locally modified or deleted to enforce server state
        getLocalDirtyOrDeletedContactFileNames().forEach { fileName ->
            val href = collectionUrl.toHttpUrl().newBuilder().addPathSegment(fileName).build().toString()
            if (href !in contactsToDownload) {
                Log.d(TAG, "Locally dirty/deleted contact found: $fileName. Forcing re-sync from server.")
                contactsToDownload.add(href)
            }
        }

        downloadAndApplyContacts(contactsToDownload)
        localSyncState = localSyncState.copy(remoteFiles = knownRemoteFiles, remoteIndexAvailable = true)
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
        val localMeta = getLocalContactMeta()
        val toDownload = remoteEtags.filter { (f, e) ->
            val meta = localMeta[f]
            meta == null || meta.eTag != e || meta.dirty
        }
            .keys.map { collectionUrl.toHttpUrl().newBuilder().addPathSegment(it).build().toString() }
        localMeta.keys.filter { it !in remoteEtags.keys }.forEach { deleteLocalContactByRemoteFileName(it) }
        downloadAndApplyContacts(toDownload)
        localSyncState = localSyncState.copy(remoteFiles = remoteEtags.keys, remoteIndexAvailable = true)
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
                    if (contacts.isNotEmpty()) applyContactToProvider(contacts.first(), fileName, eTag, vcardData)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse/store $fileName", e)
                }
            }
        }
    }

    // ── Provider helpers ──────────────────────────────────────────────────────

    private fun getLocalContactMeta(): Map<String, LocalContactMeta> {
        val map = mutableMapOf<String, LocalContactMeta>()
        provider.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.SOURCE_ID, ContactsContract.RawContacts.SYNC1, ContactsContract.RawContacts.DIRTY),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
            "${ContactsContract.RawContacts.DELETED}=0",
            arrayOf(account.type, account.name), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val f = cursor.getString(0) ?: continue
                val e = cursor.getString(1) ?: continue
                val d = cursor.getInt(2) != 0
                map[f] = LocalContactMeta(e, d)
            }
        }
        return map
    }

    private fun getLocalDirtyOrDeletedContactFileNames(): Set<String> {
        val fileNames = mutableSetOf<String>()
        provider.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.SOURCE_ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
            "(${ContactsContract.RawContacts.DIRTY}=1 OR ${ContactsContract.RawContacts.DELETED}=1)",
            arrayOf(account.type, account.name), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { fileNames.add(it) }
            }
        }
        return fileNames
    }

    private fun applyContactToProvider(contact: Contact, fileName: String, eTag: String?, rawVCard: String) {
        var existingId: Long? = null
        provider.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
            "${ContactsContract.RawContacts.SOURCE_ID}=?",
            arrayOf(account.type, account.name, fileName), null
        )?.use { if (it.moveToFirst()) existingId = it.getLong(0) }

        val ab = buildAndroidAddressBook()
        val rawContactId = if (existingId != null) {
            val values = ContentValues().apply { put(ContactsContract.RawContacts._ID, existingId) }
            val ac = at.bitfire.vcard4android.AndroidContact(ab, values)
            ac.update(contact)
            existingId!!
        } else {
            val ac = at.bitfire.vcard4android.AndroidContact(ab, contact, fileName, eTag)
            ac.add()
            ac.id!!
        }
        applyCustomPhoneLabels(rawContactId, extractCustomPhoneLabels(rawVCard))
        applyCustomImEntries(rawContactId, extractCustomImEntries(rawVCard))
        applyContactGroups(rawContactId, contact.categories)
        // All provider writes above can mark the parent raw contact DIRTY. Clear that
        // marker last, after the card and its custom fields are fully applied, so an
        // unchanged remote contact is not selected for download on every next sync.
        // AndroidContact.update() also does not manage our CardDAV metadata.
        updateLocalContactMeta(rawContactId, fileName, eTag, dirty = false, deleted = false)
    }

    private fun applyCustomPhoneLabels(rawContactId: Long, customPhoneLabels: List<CustomPhoneLabel>) {
        if (customPhoneLabels.isEmpty()) return

        val byNumber = customPhoneLabels.associateBy { normalizePhoneNumber(it.number) }
        provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                Phone.NUMBER,
                Phone.TYPE,
                Phone.LABEL
            ),
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(rawContactId.toString(), Phone.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val dataId = cursor.getLong(0)
                val currentNumber = cursor.getString(1) ?: continue
                val currentType = cursor.getInt(2)
                val currentLabel = cursor.getString(3)
                val target = byNumber[normalizePhoneNumber(currentNumber)] ?: continue

                if (currentType == Phone.TYPE_CUSTOM && currentLabel == target.label) continue

                provider.update(
                    ContactsContract.Data.CONTENT_URI,
                    ContentValues().apply {
                        put(Phone.TYPE, Phone.TYPE_CUSTOM)
                        put(Phone.LABEL, target.label)
                    },
                    "${ContactsContract.Data._ID}=?",
                    arrayOf(dataId.toString())
                )
            }
        }
    }

    private fun applyCustomImEntries(rawContactId: Long, customImEntries: List<CustomImEntry>) {
        val existingRows = queryExistingImRows(rawContactId)
        val managedRows = existingRows.filter { isJabberRow(it.protocol, it.customProtocol) }.toMutableList()
        val targetKeys = customImEntries.map { normalizeImHandle(it.handle) }.toSet()
        val seenKeys = mutableSetOf<String>()

        customImEntries.forEach { entry ->
            val normalizedHandle = normalizeImHandle(entry.handle)
            if (!seenKeys.add(normalizedHandle)) return@forEach

            val matchingRow = existingRows.firstOrNull { normalizeImHandle(it.handle) == normalizedHandle }
                ?: managedRows.firstOrNull()

            val values = ContentValues().apply {
                put(Im.DATA, entry.handle)
                put(Im.TYPE, entry.type)
                put(Im.LABEL, entry.label)
                put(Im.PROTOCOL, Im.PROTOCOL_JABBER)
                put(Im.CUSTOM_PROTOCOL, null as String?)
            }

            if (matchingRow != null) {
                provider.update(
                    ContactsContract.Data.CONTENT_URI,
                    values,
                    "${ContactsContract.Data._ID}=?",
                    arrayOf(matchingRow.dataId.toString())
                )
                managedRows.removeAll { it.dataId == matchingRow.dataId }
            } else {
                values.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                values.put(ContactsContract.Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
                provider.insert(ContactsContract.Data.CONTENT_URI, values)
            }
        }

        managedRows
            .filter { normalizeImHandle(it.handle) !in targetKeys }
            .forEach { row ->
                provider.delete(
                    ContactsContract.Data.CONTENT_URI,
                    "${ContactsContract.Data._ID}=?",
                    arrayOf(row.dataId.toString())
                )
            }
    }

    private fun extractCustomPhoneLabels(rawVCard: String): List<CustomPhoneLabel> {
        return unfoldVCard(rawVCard).mapNotNull { line ->
            if (!line.startsWith("TEL", ignoreCase = true)) return@mapNotNull null

            val separator = line.indexOf(':')
            if (separator < 0) return@mapNotNull null

            val params = line.substring(3, separator)
            val number = line.substring(separator + 1).trim()
            val label = extractCustomPhoneLabel(params) ?: return@mapNotNull null
            if (number.isEmpty()) return@mapNotNull null

            CustomPhoneLabel(number = number, label = label)
        }
    }

    private fun extractCustomImEntries(rawVCard: String): List<CustomImEntry> {
        return unfoldVCard(rawVCard)
            .mapNotNull { line -> parseCustomImEntry(line) }
            .distinctBy { entry -> normalizeImHandle(entry.handle) }
    }

    private fun parseCustomImEntry(line: String): CustomImEntry? {
        return when {
            line.startsWith(LEGACY_JABBER_PROPERTY, ignoreCase = true) -> parseLegacyJabberEntry(line)
            line.startsWith("IMPP", ignoreCase = true) -> parseImppEntry(line)
            else -> null
        }
    }

    private fun parseLegacyJabberEntry(line: String): CustomImEntry? {
        val separator = line.indexOf(':')
        if (separator < 0) return null

        val params = line.substring(LEGACY_JABBER_PROPERTY.length, separator)
        val handle = unescapeVCardValue(line.substring(separator + 1).trim())
        if (handle.isBlank()) return null

        val (type, label) = extractImTypeAndLabel(params)
        return CustomImEntry(handle = handle, type = type, label = label)
    }

    private fun parseImppEntry(line: String): CustomImEntry? {
        val separator = line.indexOf(':')
        if (separator < 0) return null

        val params = line.substring(4, separator)
        val rawValue = unescapeVCardValue(line.substring(separator + 1).trim())
        val scheme = rawValue.substringBefore(':', "").lowercase()
        if (scheme != "xmpp" && scheme != "jabber") return null

        val handle = rawValue.substringAfter(':', "").trim()
        if (handle.isBlank()) return null

        val (type, label) = extractImTypeAndLabel(params)
        return CustomImEntry(handle = handle, type = type, label = label)
    }

    private fun unfoldVCard(rawVCard: String): List<String> {
        val lines = mutableListOf<String>()
        rawVCard.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (lines.isNotEmpty()) lines[lines.lastIndex] += line.drop(1)
            } else {
                lines += line
            }
        }
        return lines
    }

    private fun extractCustomPhoneLabel(params: String): String? {
        if (params.isBlank()) return null

        val tokens = params
            .trimStart(';')
            .split(';')
            .flatMap { token ->
                val value = token.substringAfter('=', token)
                value.split(',')
            }
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

        val customType = tokens.firstOrNull { token ->
            token !in STANDARD_PHONE_TYPES && token != "TYPE"
        } ?: return null

        return customType.removePrefix("X-")
    }

    private fun extractImTypeAndLabel(params: String): Pair<Int, String?> {
        if (params.isBlank()) return Im.TYPE_OTHER to null

        val values = params
            .trimStart(';')
            .split(';')
            .filter { it.isNotBlank() }
            .map { token ->
                val key = token.substringBefore('=', "").trim()
                val value = token.substringAfter('=', "").trim()
                key.uppercase() to value
            }
            .filter { (key, value) -> key == "TYPE" && value.isNotBlank() }
            .flatMap { (_, value) -> value.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val work = values.firstOrNull { it.equals("WORK", ignoreCase = true) }
        if (work != null) return Im.TYPE_WORK to null

        val home = values.firstOrNull { it.equals("HOME", ignoreCase = true) }
        if (home != null) return Im.TYPE_HOME to null

        val custom = values.firstOrNull()
        return if (custom != null) Im.TYPE_CUSTOM to unescapeVCardValue(custom) else Im.TYPE_OTHER to null
    }

    private fun normalizePhoneNumber(number: String): String =
        number.filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' }

    private fun normalizeImHandle(handle: String): String =
        handle.trim().lowercase()

    private fun queryExistingImRows(rawContactId: Long): List<ExistingImRow> {
        val rows = mutableListOf<ExistingImRow>()
        provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                Im.DATA,
                Im.TYPE,
                Im.LABEL,
                Im.PROTOCOL,
                Im.CUSTOM_PROTOCOL
            ),
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(rawContactId.toString(), Im.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += ExistingImRow(
                    dataId = cursor.getLong(0),
                    handle = cursor.getString(1) ?: "",
                    type = cursor.getInt(2),
                    label = cursor.getString(3),
                    protocol = cursor.getInt(4),
                    customProtocol = cursor.getString(5)
                )
            }
        }
        return rows
    }

    private fun isJabberRow(protocol: Int?, customProtocol: String?): Boolean {
        if (protocol == Im.PROTOCOL_JABBER) return true
        return customProtocol?.equals("xmpp", ignoreCase = true) == true ||
            customProtocol?.equals("jabber", ignoreCase = true) == true
    }

    private fun unescapeVCardValue(value: String): String =
        value
            .replace("\\n", "\n", ignoreCase = true)
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")

    private fun updateLocalContactMeta(rawContactId: Long, remoteFileName: String?, eTag: String?, dirty: Boolean, deleted: Boolean) {
        val values = ContentValues().apply {
            put(ContactsContract.RawContacts.SOURCE_ID, remoteFileName)
            put(ContactsContract.RawContacts.SYNC1, eTag)
            put(ContactsContract.RawContacts.DIRTY, if (dirty) 1 else 0)
            put(ContactsContract.RawContacts.DELETED, if (deleted) 1 else 0)
        }
        provider.update(ContactsContract.RawContacts.CONTENT_URI, values,
            "${ContactsContract.RawContacts._ID}=?", arrayOf(rawContactId.toString()))
    }

    private fun deleteLocalContactByRemoteFileName(fileName: String) {
        provider.delete(ContactsContract.RawContacts.CONTENT_URI,
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
            "${ContactsContract.RawContacts.SOURCE_ID}=?",
            arrayOf(account.type, account.name, fileName))
    }

    // ── Sync state ────────────────────────────────────────────────────────────

    /**
     * [remoteIndexAvailable] distinguishes an empty address book from sync state
     * written by older app versions, which did not retain a remote-file index.
     */
    private data class SyncState(
        val cTag: String? = null,
        val syncToken: String? = null,
        val remoteFiles: Set<String> = emptySet(),
        val remoteIndexAvailable: Boolean = false
    )

    private fun loadSyncState(): SyncState {
        var cTag: String? = null; var syncToken: String? = null
        var remoteIndexAvailable = false
        val remoteFiles = mutableSetOf<String>()
        provider.query(ContactsContract.SyncState.CONTENT_URI, arrayOf(ContactsContract.SyncState.DATA),
            "${ContactsContract.SyncState.ACCOUNT_TYPE}=? AND ${ContactsContract.SyncState.ACCOUNT_NAME}=?",
            arrayOf(account.type, account.name), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.split("\n")?.forEach { line ->
                val parts = line.split("=", limit = 2).takeIf { it.size == 2 } ?: return@forEach
                when (parts[0]) {
                    SYNC_STATE_CTAG -> cTag = parts[1]
                    SYNC_STATE_SYNC_TOKEN -> syncToken = parts[1]
                    SYNC_STATE_REMOTE_INDEX -> remoteIndexAvailable = parts[1] == "1"
                    SYNC_STATE_REMOTE_FILE -> remoteFiles += Uri.decode(parts[1])
                }
            }
        }
        return SyncState(cTag, syncToken, remoteFiles, remoteIndexAvailable)
    }

    private fun saveSyncState(state: SyncState) {
        val raw = buildString {
            state.cTag?.let { append("$SYNC_STATE_CTAG=$it\n") }
            state.syncToken?.let { append("$SYNC_STATE_SYNC_TOKEN=$it\n") }
            append("$SYNC_STATE_REMOTE_INDEX=${if (state.remoteIndexAvailable) 1 else 0}\n")
            state.remoteFiles.sorted().forEach { append("$SYNC_STATE_REMOTE_FILE=${Uri.encode(it)}\n") }
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

    private fun loadAccountGroups() {
        accountGroups.clear()
        provider.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups.ACCOUNT_TYPE}=? AND ${ContactsContract.Groups.ACCOUNT_NAME}=? AND ${ContactsContract.Groups.DELETED}=0",
            arrayOf(account.type, account.name), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val title = cursor.getString(1) ?: continue
                accountGroups[title] = id
            }
        }
        Log.d(TAG, "Loaded ${accountGroups.size} groups for account ${account.name}")
    }

    private fun getOrCreateGroup(title: String): Long {
        accountGroups[title]?.let { return it }

        val values = ContentValues().apply {
            put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
            put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Groups.TITLE, title)
            put(ContactsContract.Groups.GROUP_VISIBLE, 1)
        }
        val uri = provider.insert(ContactsContract.Groups.CONTENT_URI, values)
            ?: throw Exception("Failed to create group: $title")
        val id = ContentUris.parseId(uri)
        accountGroups[title] = id
        return id
    }

    private fun applyContactGroups(rawContactId: Long, categories: List<String>) {
        if (categories.isEmpty()) {
            Log.d(TAG, "No categories for contact $rawContactId")
        } else {
            Log.d(TAG, "Applying categories for contact $rawContactId: $categories")
        }
        val targetGroupIds = categories.map { getOrCreateGroup(it) }.toSet()
        val currentAccountGroupIds = mutableSetOf<Long>()

        // 1. Find which groups of OUR account the contact is currently in
        provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, ContactsContract.Data._ID),
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val groupId = cursor.getLong(0)
                val dataId = cursor.getLong(1)

                // Only touch groups that belong to THIS account
                if (accountGroups.values.contains(groupId)) {
                    if (groupId in targetGroupIds) {
                        currentAccountGroupIds.add(groupId)
                    } else {
                        // Contact is in a group that's not in the VCard anymore -> Remove
                        provider.delete(ContactsContract.Data.CONTENT_URI, "${ContactsContract.Data._ID}=?", arrayOf(dataId.toString()))
                    }
                }
            }
        }

        // 2. Add missing groups
        targetGroupIds.filter { it !in currentAccountGroupIds }.forEach { groupId ->
            val values = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
            }
            provider.insert(ContactsContract.Data.CONTENT_URI, values)
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
