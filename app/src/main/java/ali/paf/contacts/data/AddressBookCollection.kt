package ali.paf.contacts.data

data class AddressBookCollection(
    val url: String,
    val displayName: String,
    val description: String? = null,
    val readOnly: Boolean = false
)
