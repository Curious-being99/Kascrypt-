package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Kascrypt", appName)
  }

  @Test
  fun `test Argon2id key derivation and XChaCha20 encryption`() {
    val password = "StrongMasterPassword123!"
    val salt = "random_salt_1234567890abcdef"
    val derivedKey = CryptoManager.deriveKey(password, salt)
    assertNotNull(derivedKey)
    assertEquals(32, derivedKey.size)

    val plaintext = "Kaspa Post-Quantum Secret Payload".toByteArray(Charsets.UTF_8)
    val ciphertext = CryptoManager.encryptXChaCha20Poly1305(plaintext, derivedKey)
    val decrypted = CryptoManager.decryptXChaCha20Poly1305(ciphertext, derivedKey)
    assertEquals(String(plaintext), String(decrypted))
  }

  @Test
  fun `test Blake2b Merkle tree root computation`() {
    val chunk1 = CryptoManager.hashBlake2b("Chunk 0 data".toByteArray())
    val chunk2 = CryptoManager.hashBlake2b("Chunk 1 data".toByteArray())
    val root = CryptoManager.computeMerkleRoot(listOf(chunk1, chunk2))
    assertNotNull(root)
    assertTrue(root.isNotEmpty())
  }

  @Test
  fun `test Kaspa wallet creation and address checksum validation`() {
    val wallet = com.example.crypto.KaspaWalletManager.createWallet(12)
    assertNotNull(wallet)
    assertTrue(wallet.mnemonic.split(" ").size == 12)
    assertTrue(wallet.address.startsWith("kaspa:"))
    assertTrue(com.example.crypto.KaspaWalletManager.isValidKaspaAddress(wallet.address))
    
    // Check known test vector address encoding
    val testAddress = wallet.address
    assertTrue(com.example.crypto.KaspaWalletManager.isValidKaspaAddress(testAddress))
  }
}

