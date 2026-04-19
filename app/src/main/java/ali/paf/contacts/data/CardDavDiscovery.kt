package ali.paf.contacts.data

import android.util.Log
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.property.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class CardDavDiscovery(private val httpClient: OkHttpClient) {

    companion object {
        private const val TAG = "CardDavDiscovery"
        private const val PRINCIPAL_PATH_TEMPLATE = "/remote.php/dav/principals/users/%s/"
    }

    fun discoverAddressBooks(baseUrl: String, username: String): List<AddressBookCollection> {
        val normalised = baseUrl.trimEnd('/')
        val homeSetUrl = resolveAddressBookHomeSet(normalised, username)
        Log.d(TAG, "Address-book home-set: $homeSetUrl")
        return listAddressBooks(homeSetUrl)
    }

    private fun resolveAddressBookHomeSet(baseUrl: String, username: String): HttpUrl {
        val principalUrl = "$baseUrl${PRINCIPAL_PATH_TEMPLATE.format(username)}".toHttpUrl()
        Log.d(TAG, "Querying principal: $principalUrl")
        var homeSetUrl: HttpUrl? = null
        DavResource(httpClient, principalUrl).propfind(
            depth = 0,
            reqProp = arrayOf(AddressbookHomeSet.NAME, CurrentUserPrincipal.NAME, DisplayName.NAME)
        ) { response: Response, _ ->
            response[AddressbookHomeSet::class.java]?.let { homeSet ->
                homeSet.hrefs.firstOrNull()?.let { href ->
                    homeSetUrl = principalUrl.resolve(href)
                }
            }
        }
        return homeSetUrl
            ?: throw IllegalStateException(
                "No addressbook-home-set found at $principalUrl. " +
                "Ensure the Nextcloud Contacts app is installed on the server."
            )
    }

    private fun listAddressBooks(homeSetUrl: HttpUrl): List<AddressBookCollection> {
        val collections = mutableListOf<AddressBookCollection>()
        DavResource(httpClient, homeSetUrl).propfind(
            depth = 1,
            reqProp = arrayOf(
                DisplayName.NAME,
                AddressbookDescription.NAME,
                ResourceType.NAME,
                CurrentUserPrivilegeSet.NAME
            )
        ) { response: Response, relation ->
            if (relation == Response.HrefRelation.SELF) return@propfind
            val resourceType = response[ResourceType::class.java]
            if (resourceType?.types?.contains(ResourceType.ADDRESSBOOK) != true) return@propfind
            val displayName = response[DisplayName::class.java]?.displayName
                ?: response.href.pathSegments.lastOrNull { it.isNotEmpty() }
                ?: "Address Book"
            val description = response[AddressbookDescription::class.java]?.description
            val readOnly = response[CurrentUserPrivilegeSet::class.java]?.mayWriteContent == false
            collections += AddressBookCollection(
                url = response.href.toString(),
                displayName = displayName,
                description = description,
                readOnly = readOnly
            )
            Log.d(TAG, "Found address book: $displayName @ ${response.href}")
        }
        return collections
    }
}
