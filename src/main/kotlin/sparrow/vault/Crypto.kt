package sparrow.vault

import com.google.gson.Gson
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : Exception("Неверный мастер-пароль или файл повреждён")
class FormatException(message: String) : Exception(message)

data class KdfParams(val memoryKib: Int = 65536, val iterations: Int = 3, val parallelism: Int = 1) {
    companion object {
        val DEFAULT = KdfParams()
        val FAST_FOR_TESTS = KdfParams(1024, 1, 1)
    }
}

// File: "SPVAULT1" | header length (u32) | header JSON | nonce (12) | AES-256-GCM(ciphertext + tag)
// The header and magic are authenticated (AAD), so changing KDF settings invalidates the file.
internal data class Header(val v: Int, val kdf: String, val m: Int, val t: Int, val p: Int, val salt: String)

private val MAGIC = "SPVAULT1".toByteArray(Charsets.US_ASCII)
private val gson = Gson()
private val rng = SecureRandom()

private fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
private fun u32(n: Int) = ByteBuffer.allocate(4).putInt(n).array()

private fun utf8(password: CharArray): ByteArray {
    val bb = Charsets.UTF_8.encode(CharBuffer.wrap(password))
    val out = ByteArray(bb.remaining())
    bb.get(out)
    if (bb.hasArray()) bb.array().fill(0)
    return out
}

private fun deriveKey(password: CharArray, salt: ByteArray, p: KdfParams): ByteArray {
    val pw = utf8(password)
    try {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(p.memoryKib)
                .withIterations(p.iterations)
                .withParallelism(p.parallelism)
                .build(),
        )
        val out = ByteArray(32)
        gen.generateBytes(pw, out)
        return out
    } finally {
        pw.fill(0)
    }
}

private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
    }

class VaultSession private constructor(
    val path: Path,
    private var header: Header,
    private var key: ByteArray,
    var data: VaultData,
) {
    fun save() {
        val hBytes = gson.toJson(header).toByteArray(Charsets.UTF_8)
        val aad = MAGIC + u32(hBytes.size) + hBytes
        val nonce = randomBytes(12)
        val plain = gson.toJson(data).toByteArray(Charsets.UTF_8)
        val ct = try {
            cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)
        } finally {
            plain.fill(0)
        }
        val out = aad + nonce + ct
        val tmp = Files.createTempFile(path.toAbsolutePath().parent, ".sparrow-", ".tmp")
        try {
            Files.write(tmp, out)
            if (Files.exists(path)) {
                Files.copy(path, path.resolveSibling(path.fileName.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** New salt and key; the whole vault is re-encrypted. */
    fun changePassword(newPassword: CharArray, params: KdfParams = KdfParams.DEFAULT) {
        val salt = randomBytes(16)
        val newKey = deriveKey(newPassword, salt, params)
        key.fill(0)
        key = newKey
        header = Header(1, "argon2id", params.memoryKib, params.iterations, params.parallelism, Base64.getEncoder().encodeToString(salt))
        save()
    }

    fun close() = key.fill(0)

    companion object {
        fun create(path: Path, password: CharArray, params: KdfParams = KdfParams.DEFAULT, data: VaultData = VaultData()): VaultSession {
            if (Files.exists(path)) throw FormatException("Файл уже существует")
            val salt = randomBytes(16)
            val key = deriveKey(password, salt, params)
            val header = Header(1, "argon2id", params.memoryKib, params.iterations, params.parallelism, Base64.getEncoder().encodeToString(salt))
            return VaultSession(path, header, key, data).also { it.save() }
        }

        fun open(path: Path, password: CharArray): VaultSession {
            val file = try {
                Files.readAllBytes(path)
            } catch (e: java.io.IOException) {
                throw FormatException("Не удалось прочитать файл: ${e.message}")
            }
            val hStart = MAGIC.size + 4
            if (file.size < hStart + 12 + 16 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw FormatException("Это не файл хранилища Sparrow")
            }
            val hl = ByteBuffer.wrap(file, MAGIC.size, 4).int
            if (hl < 2 || hl > 1024 || hStart + hl + 12 + 16 > file.size) throw FormatException("Повреждённый заголовок")
            val header = try {
                gson.fromJson(String(file, hStart, hl, Charsets.UTF_8), Header::class.java)
            } catch (e: Exception) {
                null
            } ?: throw FormatException("Повреждённый заголовок")
            if (header.v != 1 || header.kdf != "argon2id") throw FormatException("Неподдерживаемая версия файла")
            if (header.m !in 1024..1_048_576 || header.t !in 1..20 || header.p !in 1..16) {
                throw FormatException("Недопустимые параметры ключа")
            }
            val salt = try {
                Base64.getDecoder().decode(header.salt)
            } catch (e: IllegalArgumentException) {
                throw FormatException("Повреждённый заголовок")
            }
            val key = deriveKey(password, salt, KdfParams(header.m, header.t, header.p))
            val nStart = hStart + hl
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, key, file.copyOfRange(nStart, nStart + 12), file.copyOfRange(0, nStart))
                    .doFinal(file, nStart + 12, file.size - nStart - 12)
            } catch (e: AEADBadTagException) {
                key.fill(0)
                throw WrongPasswordException()
            }
            val data = try {
                gson.fromJson(String(plain, Charsets.UTF_8), VaultData::class.java)
            } catch (e: Exception) {
                null
            } finally {
                plain.fill(0)
            } ?: throw FormatException("Не удалось прочитать данные хранилища")
            return VaultSession(path, header, key, data)
        }
    }
}
