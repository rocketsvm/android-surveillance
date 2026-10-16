package com.surveil.core

import com.surveil.core.settings.Keys
import com.surveil.core.settings.PasswordHash
import com.surveil.core.settings.SettingsStore
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
    private fun store(dir: File = Files.createTempDirectory("settings").toFile()) = SettingsStore(File(dir, "settings.json"))

    @Test
    fun defaultsAndCoercion() {
        val s = store()
        assertEquals(20, s.current.int(Keys.FPS))
        assertEquals("motion", s.current.string(Keys.REC_MODE))
        val r = s.update(mapOf(
            Keys.FPS to JsonPrimitive(500),              // clamped to 60
            Keys.REC_MODE to JsonPrimitive("sometimes"), // invalid choice
            Keys.ZOOM to JsonPrimitive("2,37"),         // comma decimal, stepped to 0.1
            "no.such" to JsonPrimitive(1),
        ))
        assertEquals(60, s.current.int(Keys.FPS))
        assertEquals("motion", s.current.string(Keys.REC_MODE))
        assertEquals(2.4, s.current.double(Keys.ZOOM))
        assertTrue(Keys.REC_MODE in r.errors && "no.such" in r.errors)
        assertEquals(setOf(Keys.FPS, Keys.ZOOM), r.changed)
    }

    @Test
    fun persistsAndHashesPassword() {
        val dir = Files.createTempDirectory("settings").toFile()
        val s = store(dir)
        s.set(Keys.PASSWORD, "secret123")
        s.set(Keys.NAME, "Дача")
        val stored = s.current.passwordHash
        assertTrue(stored.startsWith("pbkdf2$"))
        assertFalse(File(dir, "settings.json").readText().contains("secret123"))
        assertFalse(s.current.publicJson().containsKey(Keys.PASSWORD))
        val reloaded = store(dir)
        assertEquals("Дача", reloaded.current.name)
        assertTrue(PasswordHash.verify("secret123", reloaded.current.passwordHash))
        assertFalse(PasswordHash.verify("wrong", reloaded.current.passwordHash))
        s.set(Keys.PASSWORD, "")
        assertFalse(s.current.authEnabled)
    }
}
