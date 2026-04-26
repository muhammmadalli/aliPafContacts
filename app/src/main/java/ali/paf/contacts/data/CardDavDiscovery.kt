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
    }

    fun discoverAddressBooks(baseUrl: String, username: String): List<AddressBookCollection> {
        val normalised = baseUrl.trimEnd('/')
        val homeSetUrl = resolveAddressBookHomeSet(normalised, username)
        Log.d(TAG, "Address-book home-set: $homeSetUrl")
        return listAddressBooks(homeSetUrl)
    }

    private fun resolveAddressBookHomeSet(baseUrl: String, username: String): HttpUrl {
        val candidates = buildDiscoveryCandidates(baseUrl, username)
        candidates.forEach { candidateUrl ->
            Log.d(TAG, "Trying CardDAV discovery at $candidateUrl")
            discoverHomeSetFrom(candidateUrl)?.let { return it }
        }

        throw IllegalStateException(
            "No addressbook-home-set found for $baseUrl. " +
            "Enter the CardDAV server URL or principal URL for your server."
        )
    }

    private fun buildDiscoveryCandidates(baseUrl: String, username: String): List<HttpUrl> {
        val rootUrl = baseUrl.toHttpUrl()
        val candidates = linkedSetOf(
            rootUrl,
            rootUrl.resolve("/.well-known/carddav"),
            rootUrl.resolve("/dav"),
            rootUrl.resolve("/carddav"),
            rootUrl.resolve("/SOGo/dav/$username/"),
            rootUrl.resolve("/SOGo/dav/")
        )
        return candidates.filterNotNull()
    }

    private fun discoverHomeSetFrom(entryUrl: HttpUrl): HttpUrl? {
        var homeSetUrl: HttpUrl? = null
        var principalUrl: HttpUrl? = null

        DavResource(httpClient, entryUrl).propfind(
            depth = 0,
            reqProp = arrayOf(AddressbookHomeSet.NAME, CurrentUserPrincipal.NAME, DisplayName.NAME)
        ) { response: Response, _ ->
            response[AddressbookHomeSet::class.java]?.hrefs?.firstOrNull()?.let { href ->
                homeSetUrl = entryUrl.resolve(href)
            }
            response[CurrentUserPrincipal::class.java]?.href?.let { href ->
                principalUrl = entryUrl.resolve(href)
            }
        }

        if (homeSetUrl != null) {
            return homeSetUrl
        }

        val resolvedPrincipalUrl = principalUrl ?: return null
        Log.d(TAG, "Following principal URL: $resolvedPrincipalUrl")
        return discoverHomeSetAtPrincipal(resolvedPrincipalUrl)
    }

    private fun discoverHomeSetAtPrincipal(principalUrl: HttpUrl): HttpUrl? {
        var homeSetUrl: HttpUrl? = null
        DavResource(httpClient, principalUrl).propfind(
            depth = 0,
            reqProp = arrayOf(AddressbookHomeSet.NAME, DisplayName.NAME)
        ) { response: Response, _ ->
            response[AddressbookHomeSet::class.java]?.hrefs?.firstOrNull()?.let { href ->
                homeSetUrl = principalUrl.resolve(href)
            }
        }
        return homeSetUrl
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
