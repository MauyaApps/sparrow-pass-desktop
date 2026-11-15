package sparrow.vault

import java.util.UUID

enum class Kind { LOGIN, NOTE, CARD }

data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind = Kind.LOGIN,
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val cardHolder: String = "",
    val cardNumber: String = "",
    val expiry: String = "",
    val cvv: String = "",
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class Prefs(
    val accent: String = "#3DDC84",
    val background: String = "#06110A",
    val tiles: Boolean = false,
)

data class VaultData(
    val entries: List<Entry> = emptyList(),
    val prefs: Prefs = Prefs(),
)
