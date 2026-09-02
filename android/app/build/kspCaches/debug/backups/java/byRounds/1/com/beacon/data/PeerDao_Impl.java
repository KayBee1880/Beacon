package com.beacon.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class PeerDao_Impl implements PeerDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<Peer> __insertionAdapterOfPeer;

  public PeerDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfPeer = new EntityInsertionAdapter<Peer>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `peer` (`id`,`displayName`,`firstSeenAt`,`lastSeenAt`) VALUES (?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Peer entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getDisplayName());
        statement.bindLong(3, entity.getFirstSeenAt());
        statement.bindLong(4, entity.getLastSeenAt());
      }
    };
  }

  @Override
  public Object upsert(final Peer peer, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfPeer.insert(peer);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<Peer>> observeAll() {
    final String _sql = "SELECT * FROM peer ORDER BY lastSeenAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"peer"}, new Callable<List<Peer>>() {
      @Override
      @NonNull
      public List<Peer> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfDisplayName = CursorUtil.getColumnIndexOrThrow(_cursor, "displayName");
          final int _cursorIndexOfFirstSeenAt = CursorUtil.getColumnIndexOrThrow(_cursor, "firstSeenAt");
          final int _cursorIndexOfLastSeenAt = CursorUtil.getColumnIndexOrThrow(_cursor, "lastSeenAt");
          final List<Peer> _result = new ArrayList<Peer>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Peer _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpDisplayName;
            _tmpDisplayName = _cursor.getString(_cursorIndexOfDisplayName);
            final long _tmpFirstSeenAt;
            _tmpFirstSeenAt = _cursor.getLong(_cursorIndexOfFirstSeenAt);
            final long _tmpLastSeenAt;
            _tmpLastSeenAt = _cursor.getLong(_cursorIndexOfLastSeenAt);
            _item = new Peer(_tmpId,_tmpDisplayName,_tmpFirstSeenAt,_tmpLastSeenAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object get(final String peerId, final Continuation<? super Peer> $completion) {
    final String _sql = "SELECT * FROM peer WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, peerId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Peer>() {
      @Override
      @Nullable
      public Peer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfDisplayName = CursorUtil.getColumnIndexOrThrow(_cursor, "displayName");
          final int _cursorIndexOfFirstSeenAt = CursorUtil.getColumnIndexOrThrow(_cursor, "firstSeenAt");
          final int _cursorIndexOfLastSeenAt = CursorUtil.getColumnIndexOrThrow(_cursor, "lastSeenAt");
          final Peer _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpDisplayName;
            _tmpDisplayName = _cursor.getString(_cursorIndexOfDisplayName);
            final long _tmpFirstSeenAt;
            _tmpFirstSeenAt = _cursor.getLong(_cursorIndexOfFirstSeenAt);
            final long _tmpLastSeenAt;
            _tmpLastSeenAt = _cursor.getLong(_cursorIndexOfLastSeenAt);
            _result = new Peer(_tmpId,_tmpDisplayName,_tmpFirstSeenAt,_tmpLastSeenAt);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
