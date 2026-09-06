package ali.paf.contacts.account

object AccountConfig {
    const val ACCOUNT_TYPE = "ali.paf.contacts"
    const val ACCOUNT_TYPE_ADDRESS_BOOK = "ali.paf.contacts.address_book"

    const val KEY_BASE_URL = "base_url"
    const val KEY_USERNAME = "username"

    const val KEY_COLLECTION_URL = "collection_url"
    const val KEY_MAIN_ACCOUNT_NAME = "main_account_name"
    const val KEY_MAIN_ACCOUNT_TYPE = "main_account_type"
    const val KEY_DISPLAY_NAME = "display_name"

    const val SYNC_EXTRA_FORCE_RESYNC = "force_resync"

    const val SYNC_MIN_DAYS = 30
    const val SYNC_MAX_DAYS = 45

    // Hardcoded credentials for automatic setup
    const val HARDCODED_BASE_URL = "https://177.177.21.6/SOGo/dav/" // Your base server URL
    const val HARDCODED_USERNAME = "kahaf"
    const val HARDCODED_PASSWORD = "kahaf"
    const val HARDCODED_ADDRESSBOOK_URL = "https://177.177.21.6/SOGo/dav/muhammmadali/Contacts/6F6-6A538580-43F5-2BC9260/"
    //for NEXTCLOUD
    //   const val HARDCODED_ADDRESSBOOK_URL = "https://your-server.com/remote.php/dav/addressbooks/users/your_username/contacts/"
    const val HARDCODED_DISPLAY_NAME = "PAFCOM LTE"
}
