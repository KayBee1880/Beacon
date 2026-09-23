package com.beacon.data

import android.util.Base64
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.beacon.crypto.CryptoService
import com.beacon.crypto.IdentityKeyStore

// Milestone 14 (D-065): real migrations for every schema version this project has ever
// had, replacing the destructive fallback every version bump used until now (D-023). Each
// migration's SQL is cross-checked against the real historical entity shape at that
// version (the current entity file plus the decisions log entry that introduced the
// change), not reconstructed from memory.

// Milestone 4 (D-022): Message.retryCount/nextRetryAt.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE message ADD COLUMN nextRetryAt INTEGER")
    }
}

// Milestone 6 (D-029/D-030): Identity's/Peer's encryption-key columns, and the new
// RelayEnvelope table.
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE peer ADD COLUMN encryptionPublicKey TEXT")

        // D-065: these three are genuinely NOT NULL, no default leaves an identity in a
        // valid state, so the placeholder default here exists only to satisfy SQLite's
        // own ALTER TABLE requirement; backfillIdentityEncryptionKey overwrites it with a
        // real keypair immediately below, for whichever identity row already exists.
        db.execSQL("ALTER TABLE identity ADD COLUMN encryptionPublicKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE identity ADD COLUMN encryptionPrivateKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE identity ADD COLUMN encryptionPublicKeySignature TEXT NOT NULL DEFAULT ''")
        backfillIdentityEncryptionKey(db)

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS relay_envelope (
                messageId TEXT NOT NULL PRIMARY KEY,
                originSenderId TEXT NOT NULL,
                originDisplayName TEXT NOT NULL,
                finalRecipientId TEXT NOT NULL,
                senderEphemeralPublicKey BLOB NOT NULL,
                senderEphemeralPublicKeySignature BLOB NOT NULL,
                ciphertext BLOB NOT NULL,
                hopCount INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                receivedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_relay_envelope_finalRecipientId ON relay_envelope(finalRecipientId)")
    }

    // D-065: a real backfill, not a placeholder. An identity created before this
    // migration ever existed has never had an encryption keypair, this generates and
    // signs one exactly the way IdentityRepository.createIdentity already does for a
    // brand-new identity, the same first-ever-value event simply happening later. There
    // is at most one row (Identity.SINGLETON_ID); if none exists yet, there is nothing
    // to backfill.
    private fun backfillIdentityEncryptionKey(db: SupportSQLiteDatabase) {
        db.query("SELECT id, keystoreAlias FROM identity").use { cursor ->
            if (!cursor.moveToFirst()) return

            val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
            val keystoreAlias = cursor.getString(cursor.getColumnIndexOrThrow("keystoreAlias"))

            val cryptoService = CryptoService()
            val keyPair = cryptoService.generateEphemeralKeyPair()
            val encryptionPublicKey = cryptoService.encodePublicKey(keyPair.public)
            val encryptionPrivateKey = cryptoService.encodePrivateKey(keyPair.private)
            val signature = Base64.encodeToString(
                IdentityKeyStore.sign(keystoreAlias, keyPair.public.encoded),
                Base64.NO_WRAP
            )

            db.execSQL(
                "UPDATE identity SET encryptionPublicKey = ?, encryptionPrivateKey = ?, encryptionPublicKeySignature = ? WHERE id = ?",
                arrayOf(encryptionPublicKey, encryptionPrivateKey, signature, id)
            )
        }
    }
}

// Milestone 7 (D-037): Message's attachment columns, every one nullable, an existing
// message simply has no attachment.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN attachmentFileName TEXT")
        db.execSQL("ALTER TABLE message ADD COLUMN attachmentMimeType TEXT")
        db.execSQL("ALTER TABLE message ADD COLUMN attachmentSizeBytes INTEGER")
        db.execSQL("ALTER TABLE message ADD COLUMN attachmentContentHash BLOB")
        db.execSQL("ALTER TABLE message ADD COLUMN attachmentLocalPath TEXT")
        db.execSQL("ALTER TABLE message ADD COLUMN attachmentState TEXT")
    }
}

// Milestone 12 (D-059/D-060): RelayEnvelope's origin-key and ack columns.
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // D-066: originEncryptionPublicKey/Signature are NOT NULL with no honest
        // backfill value, this data belongs to an envelope's original creator and was
        // never captured by a device only ever carrying it for someone else. Relay
        // envelopes are already bounded, best-effort, self-healing data by design
        // (docs/07 §7/§10); wiping whatever this device happens to be carrying at
        // upgrade time is a narrower instance of a property that design never promised
        // to survive indefinitely, not a new one.
        db.execSQL("DELETE FROM relay_envelope")

        db.execSQL("ALTER TABLE relay_envelope ADD COLUMN originEncryptionPublicKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE relay_envelope ADD COLUMN originEncryptionPublicKeySignature TEXT NOT NULL DEFAULT ''")
        // Default value is irrelevant, the table is already empty from the DELETE above;
        // still required syntactically for a NOT NULL column added via ALTER TABLE.
        db.execSQL("ALTER TABLE relay_envelope ADD COLUMN kind TEXT NOT NULL DEFAULT 'MESSAGE'")
        db.execSQL("ALTER TABLE relay_envelope ADD COLUMN ackedMessageId TEXT")
    }
}
