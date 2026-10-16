package com.surveil.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Immutable snapshot of all setting values. */
class Settings internal constructor(val values: Map<String, JsonElement>) {
    private fun raw(key: String): JsonPrimitive =
        (values[key] ?: Schema.byKey[key]?.defaultValue ?: error("unknown setting $key")) as JsonPrimitive

    fun int(key: String): Int = raw(key).intOrNull ?: (Schema.byKey[key]!!.defaultValue as JsonPrimitive).intOrNull ?: 0
    fun double(key: String): Double = raw(key).doubleOrNull ?: 0.0
    fun bool(key: String): Boolean = raw(key).booleanOrNull ?: false
    fun string(key: String): String = raw(key).content

    /** Values for clients: secrets are omitted. */
    fun publicJson(): JsonObject = JsonObject(values.filterKeys { k -> (Schema.byKey[k] as? TextSetting)?.secret != true })

    /** Keys whose value differs from [other]. */
    fun diff(other: Settings): Set<String> =
        Schema.byKey.keys.filterTo(HashSet()) { raw(it) != other.raw(it) }

    val name: String get() = string(Keys.NAME).ifBlank { "Камера" }
    val port: Int get() = int(Keys.PORT)
    val passwordHash: String get() = string(Keys.PASSWORD)
    val authEnabled: Boolean get() = passwordHash.isNotEmpty()
}

/**
 * Persistent settings backed by a JSON file. Thread-safe; every successful update is written
 * atomically and published through [flow], so the phone UI, the web UI and the engine stay in sync.
 */
class SettingsStore(private val file: File) {
    private val json = Json { prettyPrint = true }
    private val state = MutableStateFlow(load())

    val flow: StateFlow<Settings> = state.asStateFlow()
    val current: Settings get() = state.value

    class UpdateResult(val changed: Set<String>, val errors: Map<String, String>)

    /** Applies a batch of changes. Invalid entries are reported and skipped; valid ones are saved. */
    @Synchronized
    fun update(changes: Map<String, JsonElement>): UpdateResult {
        val errors = LinkedHashMap<String, String>()
        val next = LinkedHashMap(current.values)
        for ((key, value) in changes) {
            val def = Schema.byKey[key]
            if (def == null) {
                errors[key] = "неизвестный параметр"
                continue
            }
            val coerced = def.coerce(value)
            if (coerced == null) {
                errors[key] = "недопустимое значение"
                continue
            }
            next[key] = if (def is TextSetting && def.secret) {
                val plain = (coerced as JsonPrimitive).content
                JsonPrimitive(if (plain.isEmpty()) "" else PasswordHash.hash(plain))
            } else coerced
        }
        val updated = Settings(next)
        val changed = updated.diff(current)
        if (changed.isNotEmpty()) {
            save(updated)
            state.value = updated
        }
        return UpdateResult(changed, errors)
    }

    fun set(key: String, value: Any): UpdateResult = update(mapOf(key to when (value) {
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }))

    private fun load(): Settings {
        val stored = runCatching {
            if (file.exists()) json.parseToJsonElement(file.readText()) as JsonObject else null
        }.getOrNull() ?: JsonObject(emptyMap())
        val values = LinkedHashMap<String, JsonElement>()
        for ((key, def) in Schema.byKey) {
            val v = stored[key]
            values[key] = when {
                v == null -> def.defaultValue
                def is TextSetting && def.secret -> v // already hashed
                else -> def.coerce(v) ?: def.defaultValue
            }
        }
        return Settings(values)
    }

    private fun save(s: Settings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(s.values)))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** PBKDF2 password hashing: `pbkdf2$<iterations>$<salt>$<hash>`. */
object PasswordHash {
    private const val ITERATIONS = 20_000

    fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "pbkdf2\$$ITERATIONS\$${b64(salt)}\$${b64(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val p = stored.split('$')
        if (p.size != 4 || p[0] != "pbkdf2") return false
        val iterations = p[1].toIntOrNull() ?: return false
        val salt = Base64.getDecoder().decode(p[2])
        val expected = Base64.getDecoder().decode(p[3])
        val actual = derive(password, salt, iterations)
        return java.security.MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(b)
}
