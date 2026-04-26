package ali.paf.contacts.sync

import android.accounts.Account
import android.content.*
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Phone
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
    private data class CustomPhoneLabel(val number: String, val label: String)
    private data class CustomImEntry(val handle: String, val type: Int, val label: String?)
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
        private const val CHUNK_SIZE = 15
        private const val LEGACY_JABBER_PROPERTY = "X-JABBER"
        private val STANDARD_PHONE_TYPES = setOf(
            "HOME", "WORK", "CELL", "VOICE", "FAX", "PAGER", "CAR", "ISDN",
            "PREF", "MSG", "BBS", "MODEM", "PCS", "VIDEO", "TEXTPHONE", "TEXTPHONE"
        )
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
        val vCard = buildUploadVCard(contact, lc.rawContactId)
        val fileName = lc.remoteFileName ?: "${UUID.randomUUID()}.vcf"
        val url = collectionUrl.toHttpUrl().newBuilder().addPathSegment(fileName).build()
        var newETag: String? = null
        val body = vCard.toByteArray(Charsets.UTF_8).toRequestBody()
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
                    if (contacts.isNotEmpty()) applyContactToProvider(contacts.first(), fileName, eTag, vcardData)
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
            updateLocalContactMeta(ac.id!!, fileName, eTag, dirty = false, deleted = false)
            ac.id!!
        }
        applyCustomPhoneLabels(rawContactId, extractCustomPhoneLabels(rawVCard))
        applyCustomImEntries(rawContactId, extractCustomImEntries(rawVCard))
    }

    private fun buildUploadVCard(contact: Contact, rawContactId: Long): String {
        val jabberEntries = queryJabberImEntries(rawContactId)
        val baseVCard = ByteArrayOutputStream()
            .also { contact.writeVCard(VCardVersion.V4_0, it) }
            .toString(Charsets.UTF_8.name())

        return appendLegacyJabberProperties(baseVCard, jabberEntries)
    }

    private fun appendLegacyJabberProperties(baseVCard: String, jabberEntries: List<CustomImEntry>): String {
        if (jabberEntries.isEmpty()) return baseVCard
        if (unfoldVCard(baseVCard).any { it.startsWith("$LEGACY_JABBER_PROPERTY:", ignoreCase = true) || it.startsWith("$LEGACY_JABBER_PROPERTY;", ignoreCase = true) }) {
            return baseVCard
        }

        val customLines = jabberEntries.joinToString(separator = "\r\n") { entry ->
            buildString {
                append(LEGACY_JABBER_PROPERTY)
                when (entry.type) {
                    Im.TYPE_HOME -> append(";TYPE=HOME")
                    Im.TYPE_WORK -> append(";TYPE=WORK")
                    Im.TYPE_CUSTOM -> entry.label?.takeIf { it.isNotBlank() }?.let { append(";TYPE=").append(escapeVCardParam(it)) }
                }
                append(':')
                append(escapeVCardValue(entry.handle))
            }
        }

        val marker = "\r\nEND:VCARD"
        return if (baseVCard.contains(marker, ignoreCase = true)) {
            baseVCard.replace(marker, "\r\n$customLines$marker", ignoreCase = true)
        } else {
            "$baseVCard\r\n$customLines"
        }
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

    private fun queryJabberImEntries(rawContactId: Long): List<CustomImEntry> {
        return queryExistingImRows(rawContactId)
            .asSequence()
            .filter { row -> isJabberRow(row.protocol, row.customProtocol) }
            .map { row ->
                CustomImEntry(
                    handle = row.handle,
                    type = row.type,
                    label = row.label
                )
            }
            .filter { entry -> entry.handle.isNotBlank() }
            .distinctBy { normalizeImHandle(it.handle) }
            .toList()
    }

    private fun isJabberRow(protocol: Int?, customProtocol: String?): Boolean {
        if (protocol == Im.PROTOCOL_JABBER) return true
        return customProtocol?.equals("xmpp", ignoreCase = true) == true ||
            customProtocol?.equals("jabber", ignoreCase = true) == true
    }

    private fun escapeVCardValue(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")

    private fun escapeVCardParam(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(";", "\\;")
            .replace(",", "\\,")

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
