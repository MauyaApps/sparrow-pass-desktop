package sparrow.vault

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object Importers {
    /** Bitwarden unencrypted JSON, or CSV (Chrome, Bitwarden CSV, generic). */
    fun detect(text: String): List<Entry> =
        if (text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{")) fromBitwardenJson(text) else fromCsv(text)

    private fun JsonObject.str(k: String): String = get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject

    fun fromBitwardenJson(text: String): List<Entry> {
        val root = try {
            JsonParser.parseString(text.removePrefix("\uFEFF")).asJsonObject
        } catch (e: Exception) {
            throw FormatException("Не похоже на экспорт Bitwarden (JSON)")
        }
        if (root.get("encrypted")?.asBoolean == true) {
            throw FormatException("Зашифрованный экспорт не поддерживается: выгрузите обычный .json")
        }
        val folders = root.getAsJsonArray("folders")?.associate {
            it.asJsonObject.str("id") to it.asJsonObject.str("name")
        } ?: emptyMap()
        val items = root.getAsJsonArray("items") ?: throw FormatException("В файле нет записей")
        return items.mapNotNull { el ->
            val o = el.asJsonObject
            val tags = folders[o.str("folderId")]?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            when (o.get("type")?.asInt) {
                1 -> {
                    val l = o.obj("login")
                    Entry(
                        kind = Kind.LOGIN, title = o.str("name"), notes = o.str("notes"), tags = tags,
                        username = l?.str("username") ?: "", password = l?.str("password") ?: "",
                        url = l?.getAsJsonArray("uris")?.firstOrNull()?.asJsonObject?.str("uri") ?: "",
                    )
                }
                2 -> Entry(kind = Kind.NOTE, title = o.str("name"), notes = o.str("notes"), tags = tags)
                3 -> {
                    val c = o.obj("card")
                    val m = c?.str("expMonth") ?: ""
                    val y = c?.str("expYear") ?: ""
                    Entry(
                        kind = Kind.CARD, title = o.str("name"), notes = o.str("notes"), tags = tags,
                        cardHolder = c?.str("cardholderName") ?: "", cardNumber = c?.str("number") ?: "",
                        expiry = if (m.isNotEmpty() || y.isNotEmpty()) "$m/$y" else "", cvv = c?.str("code") ?: "",
                    )
                }
                else -> null // identities are not supported
            }
        }
    }

    fun fromCsv(text: String): List<Entry> {
        val rows = parseCsv(text)
        if (rows.size < 2) return emptyList()
        val h = rows[0].map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> h.indexOf(n).takeIf { it >= 0 } }
        val cTitle = col("name", "title")
        val cUrl = col("url", "login_uri", "website", "uri")
        val cUser = col("username", "login_username", "user", "login")
        val cPass = col("password", "login_password")
        val cNote = col("note", "notes")
        val cTag = col("folder", "tags", "grouping")
        val cType = col("type")
        if (cPass == null && cUser == null && cUrl == null) throw FormatException("Не нашёл столбцов url, username, password")
        return rows.drop(1).mapNotNull { r ->
            fun g(i: Int?) = if (i != null && i < r.size) r[i].trim() else ""
            val note = g(cType).let { it.equals("note", true) || it.equals("securenote", true) }
            val e = Entry(
                kind = if (note) Kind.NOTE else Kind.LOGIN,
                title = g(cTitle).ifEmpty { g(cUrl) }, username = g(cUser), password = g(cPass),
                url = g(cUrl), notes = g(cNote),
                tags = g(cTag).split(',', ';').map { it.trim() }.filter { it.isNotEmpty() },
            )
            if (e.title.isEmpty() && e.username.isEmpty() && e.password.isEmpty() && e.notes.isEmpty()) null else e
        }
    }

    internal fun parseCsv(input: String): List<List<String>> {
        val text = input.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val f = StringBuilder()
        var quoted = false
        fun endField() { row.add(f.toString()); f.setLength(0) }
        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = mutableListOf()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ } else quoted = false
                } else f.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> endField()
                '\r' -> {}
                '\n' -> endRow()
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
