package com.example

import com.example.crypto.CryptoManager
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun cryptographicPasswordGenerator_createsStrongPassword() {
        val password = CryptoManager.generateCryptographicPassword(16)
        assertEquals(16, password.length)
        assertTrue("Must have at least 8 characters", password.length >= 8)
        
        val strength = CryptoManager.detectPasswordStrength(password)
        assertTrue("Must satisfy >= 8 characters", strength.isValidMinLength)
        assertTrue("Score must be >= 3", strength.score >= 3)
        assertTrue("Entropy must be high (>60 bits)", strength.entropyBits > 60.0)
    }

    @Test
    fun passwordStrengthDetector_identifiesWeakAndStrongPasswords() {
        val weak = CryptoManager.detectPasswordStrength("abc")
        assertEquals(0, weak.score)
        assertFalse(weak.isValidMinLength)

        val weak8 = CryptoManager.detectPasswordStrength("abcdefgh")
        assertTrue(weak8.isValidMinLength)
        assertTrue(weak8.score <= 2)

        val strong = CryptoManager.detectPasswordStrength("K@sp4!2026SecureKey")
        assertTrue(strong.isValidMinLength)
        assertTrue(strong.hasUppercase)
        assertTrue(strong.hasLowercase)
        assertTrue(strong.hasDigits)
        assertTrue(strong.hasSymbols)
        assertEquals(4, strong.score)
    }
}
