package com.beacon.data;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class BeaconDatabase_Impl extends BeaconDatabase {
  private volatile IdentityDao _identityDao;

  private volatile PeerDao _peerDao;

  private volatile ConversationDao _conversationDao;

  private volatile MessageDao _messageDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(1) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `identity` (`id` INTEGER NOT NULL, `publicKey` TEXT NOT NULL, `keystoreAlias` TEXT NOT NULL, `displayName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `peer` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `conversation` (`id` TEXT NOT NULL, `peerId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastMessageAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`peerId`) REFERENCES `peer`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_peerId` ON `conversation` (`peerId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `message` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `direction` TEXT NOT NULL, `content` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `deliveredAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversation`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_conversationId` ON `message` (`conversationId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '40393002e24ce5d6e70c0e4b4b88eafa')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `identity`");
        db.execSQL("DROP TABLE IF EXISTS `peer`");
        db.execSQL("DROP TABLE IF EXISTS `conversation`");
        db.execSQL("DROP TABLE IF EXISTS `message`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        db.execSQL("PRAGMA foreign_keys = ON");
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsIdentity = new HashMap<String, TableInfo.Column>(5);
        _columnsIdentity.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsIdentity.put("publicKey", new TableInfo.Column("publicKey", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsIdentity.put("keystoreAlias", new TableInfo.Column("keystoreAlias", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsIdentity.put("displayName", new TableInfo.Column("displayName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsIdentity.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysIdentity = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesIdentity = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoIdentity = new TableInfo("identity", _columnsIdentity, _foreignKeysIdentity, _indicesIdentity);
        final TableInfo _existingIdentity = TableInfo.read(db, "identity");
        if (!_infoIdentity.equals(_existingIdentity)) {
          return new RoomOpenHelper.ValidationResult(false, "identity(com.beacon.data.Identity).\n"
                  + " Expected:\n" + _infoIdentity + "\n"
                  + " Found:\n" + _existingIdentity);
        }
        final HashMap<String, TableInfo.Column> _columnsPeer = new HashMap<String, TableInfo.Column>(4);
        _columnsPeer.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPeer.put("displayName", new TableInfo.Column("displayName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPeer.put("firstSeenAt", new TableInfo.Column("firstSeenAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPeer.put("lastSeenAt", new TableInfo.Column("lastSeenAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysPeer = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesPeer = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoPeer = new TableInfo("peer", _columnsPeer, _foreignKeysPeer, _indicesPeer);
        final TableInfo _existingPeer = TableInfo.read(db, "peer");
        if (!_infoPeer.equals(_existingPeer)) {
          return new RoomOpenHelper.ValidationResult(false, "peer(com.beacon.data.Peer).\n"
                  + " Expected:\n" + _infoPeer + "\n"
                  + " Found:\n" + _existingPeer);
        }
        final HashMap<String, TableInfo.Column> _columnsConversation = new HashMap<String, TableInfo.Column>(4);
        _columnsConversation.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversation.put("peerId", new TableInfo.Column("peerId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversation.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsConversation.put("lastMessageAt", new TableInfo.Column("lastMessageAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysConversation = new HashSet<TableInfo.ForeignKey>(1);
        _foreignKeysConversation.add(new TableInfo.ForeignKey("peer", "CASCADE", "NO ACTION", Arrays.asList("peerId"), Arrays.asList("id")));
        final HashSet<TableInfo.Index> _indicesConversation = new HashSet<TableInfo.Index>(1);
        _indicesConversation.add(new TableInfo.Index("index_conversation_peerId", true, Arrays.asList("peerId"), Arrays.asList("ASC")));
        final TableInfo _infoConversation = new TableInfo("conversation", _columnsConversation, _foreignKeysConversation, _indicesConversation);
        final TableInfo _existingConversation = TableInfo.read(db, "conversation");
        if (!_infoConversation.equals(_existingConversation)) {
          return new RoomOpenHelper.ValidationResult(false, "conversation(com.beacon.data.Conversation).\n"
                  + " Expected:\n" + _infoConversation + "\n"
                  + " Found:\n" + _existingConversation);
        }
        final HashMap<String, TableInfo.Column> _columnsMessage = new HashMap<String, TableInfo.Column>(7);
        _columnsMessage.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessage.put("conversationId", new TableInfo.Column("conversationId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessage.put("direction", new TableInfo.Column("direction", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessage.put("content", new TableInfo.Column("content", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessage.put("status", new TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessage.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsMessage.put("deliveredAt", new TableInfo.Column("deliveredAt", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysMessage = new HashSet<TableInfo.ForeignKey>(1);
        _foreignKeysMessage.add(new TableInfo.ForeignKey("conversation", "CASCADE", "NO ACTION", Arrays.asList("conversationId"), Arrays.asList("id")));
        final HashSet<TableInfo.Index> _indicesMessage = new HashSet<TableInfo.Index>(1);
        _indicesMessage.add(new TableInfo.Index("index_message_conversationId", false, Arrays.asList("conversationId"), Arrays.asList("ASC")));
        final TableInfo _infoMessage = new TableInfo("message", _columnsMessage, _foreignKeysMessage, _indicesMessage);
        final TableInfo _existingMessage = TableInfo.read(db, "message");
        if (!_infoMessage.equals(_existingMessage)) {
          return new RoomOpenHelper.ValidationResult(false, "message(com.beacon.data.Message).\n"
                  + " Expected:\n" + _infoMessage + "\n"
                  + " Found:\n" + _existingMessage);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "40393002e24ce5d6e70c0e4b4b88eafa", "3b130ce4aedc941a5ff0ad4714d98954");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "identity","peer","conversation","message");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    final boolean _supportsDeferForeignKeys = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP;
    try {
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = FALSE");
      }
      super.beginTransaction();
      if (_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA defer_foreign_keys = TRUE");
      }
      _db.execSQL("DELETE FROM `identity`");
      _db.execSQL("DELETE FROM `peer`");
      _db.execSQL("DELETE FROM `conversation`");
      _db.execSQL("DELETE FROM `message`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = TRUE");
      }
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(IdentityDao.class, IdentityDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(PeerDao.class, PeerDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(ConversationDao.class, ConversationDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(MessageDao.class, MessageDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public IdentityDao identityDao() {
    if (_identityDao != null) {
      return _identityDao;
    } else {
      synchronized(this) {
        if(_identityDao == null) {
          _identityDao = new IdentityDao_Impl(this);
        }
        return _identityDao;
      }
    }
  }

  @Override
  public PeerDao peerDao() {
    if (_peerDao != null) {
      return _peerDao;
    } else {
      synchronized(this) {
        if(_peerDao == null) {
          _peerDao = new PeerDao_Impl(this);
        }
        return _peerDao;
      }
    }
  }

  @Override
  public ConversationDao conversationDao() {
    if (_conversationDao != null) {
      return _conversationDao;
    } else {
      synchronized(this) {
        if(_conversationDao == null) {
          _conversationDao = new ConversationDao_Impl(this);
        }
        return _conversationDao;
      }
    }
  }

  @Override
  public MessageDao messageDao() {
    if (_messageDao != null) {
      return _messageDao;
    } else {
      synchronized(this) {
        if(_messageDao == null) {
          _messageDao = new MessageDao_Impl(this);
        }
        return _messageDao;
      }
    }
  }
}
