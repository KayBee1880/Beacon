package com.beacon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identity")
data class Identity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val publicKey: String,
    val keystoreAlias: String,
    val displayName: String,
    val createdAt: Long,
    // Milestone 6 (D-029): a second, long-term, software-generated EC keypair, distinct
    // from the Keystore-backed signing key above, used only for end-to-end ECDH to a
    // relay message's final recipient. Base64 like publicKey; encryptionPrivateKey is
    // real key material sitting in an otherwise unencrypted database, an accepted risk
    // already carried by every other row here (docs/02, SQLCipher deferred), not a new
    // one opened for this key specifically.
    val encryptionPublicKey: String,
    val encryptionPrivateKey: String,
    val encryptionPublicKeySignature: String
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
