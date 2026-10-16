package com.surveil.core.web

import com.surveil.core.http.HttpRequest
import com.surveil.core.settings.PasswordHash
import com.surveil.core.settings.SettingsStore
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional password protection. Browsers use a session cookie (via the login form);
 * players such as VLC/ffplay can use HTTP Basic auth (`http://user:pass@phone:8080/live.mp4`).
 */
class AuthManager(private val settings: SettingsStore, private val file: File) {
    private val sessions = ConcurrentHashMap<String, Long>()
    private val basicCache = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, IntArray>()
    private val lockedUntil = ConcurrentHashMap<String, Long>()
    private var credentialsStamp = settings.current.passwordHash + "|" + settings.current.string(com.surveil.core.settings.Keys.USERNAME)

    init {
        runCatching {
            if (file.exists()) file.readLines().forEach { line ->
                val (t, exp) = line.split(' ', limit = 2)
                exp.toLongOrNull()?.takeIf { it > System.currentTimeMillis() }?.let { sessions[t] = it }
            }
        }
    }

    val required: Boolean get() = settings.current.authEnabled

    fun isAuthorized(req: HttpRequest): Boolean {
        if (!required) return true
        checkCredentialsChanged()
        val now = System.currentTimeMillis()
        req.cookie(COOKIE)?.let { t -> sessions[t]?.let { if (it > now) return true } }
        return checkBasic(req.header("authorization"), req.remote.hostAddress ?: "")
    }

    /** HTTP Basic check for non-browser clients (RTSP, VLC, NVRs); true if no password is set. */
    fun checkBasic(header: String?, ip: String): Boolean {
        if (!required) return true
        checkCredentialsChanged()
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        val now = System.currentTimeMillis()
        val digest = sha256(header)
        basicCache[digest]?.let { if (it > now) return true }
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8) }.getOrNull() ?: return false
        val colon = decoded.indexOf(':')
        if (colon < 0) return false
        val ok = runCatching { verify(decoded.substring(0, colon), decoded.substring(colon + 1), ip) }.getOrDefault(false)
        if (ok) basicCache[digest] = now + 10 * 60_000L
        return ok
    }

    /** Returns a session token on success, or null. Throws [LockedOut] while an address is locked out. */
    fun login(username: String, password: String, ip: String): String? {
        checkCredentialsChanged()
        if (!verify(username, password, ip)) return null
        val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sessions[token] = System.currentTimeMillis() + SESSION_MS
        persist()
        return token
    }

    fun logout(req: HttpRequest) {
        req.cookie(COOKIE)?.let { sessions.remove(it); persist() }
    }

    class LockedOut(val seconds: Long) : Exception()

    private fun verify(username: String, password: String, ip: String): Boolean {
        val now = System.currentTimeMillis()
        lockedUntil[ip]?.let { if (it > now) throw LockedOut((it - now) / 1000 + 1) }
        val s = settings.current
        val ok = username.trim().equals(s.string(com.surveil.core.settings.Keys.USERNAME).trim(), ignoreCase = true) &&
            PasswordHash.verify(password, s.passwordHash)
        if (ok) {
            failures.remove(ip)
        } else {
            val f = failures.getOrPut(ip) { IntArray(1) }
            f[0]++
            if (f[0] >= 5) {
                lockedUntil[ip] = now + 30_000L
                f[0] = 0
            }
        }
        return ok
    }

    /** A new password (or username) invalidates every existing session. */
    private fun checkCredentialsChanged() {
        val s = settings.current
        val stamp = s.passwordHash + "|" + s.string(com.surveil.core.settings.Keys.USERNAME)
        if (stamp != credentialsStamp) {
            credentialsStamp = stamp
            sessions.clear()
            basicCache.clear()
            persist()
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(sessions.entries.joinToString("") { "${it.key} ${it.value}\n" })
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val COOKIE = "scsid"
        const val SESSION_MS = 30L * 86_400_000L
    }
}
