package sparrow.vault

import java.security.SecureRandom

object PasswordGenerator {
    data class Options(
        val length: Int = 20,
        val lower: Boolean = true,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val noAmbiguous: Boolean = false,
    )

    private const val AMBIGUOUS = "Il1O0o"

    fun generate(o: Options = Options(), rnd: SecureRandom = SecureRandom()): String {
        val classes = buildList {
            if (o.lower) add("abcdefghijklmnopqrstuvwxyz")
            if (o.upper) add("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            if (o.digits) add("0123456789")
            if (o.symbols) add("!@#$%^&*()-_=+[]{};:,.<>?/")
        }.map { c -> if (o.noAmbiguous) c.filter { it !in AMBIGUOUS } else c }
        require(classes.isNotEmpty()) { "Выберите хотя бы один набор символов" }
        require(o.length in classes.size..256) { "Недопустимая длина пароля" }
        val all = classes.joinToString("")
        val chars = classes.map { it[rnd.nextInt(it.length)] }.toMutableList()
        while (chars.size < o.length) chars.add(all[rnd.nextInt(all.length)])
        for (i in chars.indices.reversed()) { // Fisher-Yates
            val j = rnd.nextInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        return chars.joinToString("")
    }
}
