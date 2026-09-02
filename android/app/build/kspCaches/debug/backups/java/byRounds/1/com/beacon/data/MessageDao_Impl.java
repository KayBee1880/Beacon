package com.beacon.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
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
public final class MessageDao_Impl implements MessageDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<Message> __insertionAdapterOfMessage;

  private final Converters __converters = new Converters();

  private final EntityDeletionOrUpdateAdapter<Message> __updateAdapterOfMessage;

  public MessageDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfMessage = new EntityInsertionAdapter<Message>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `message` (`id`,`conversationId`,`direction`,`content`,`status`,`createdAt`,`deliveredAt`) VALUES (?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Message entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getConversationId());
        final String _tmp = __converters.fromMessageDirection(entity.getDirection());
        statement.bindString(3, _tmp);
        statement.bindString(4, entity.getContent());
        final String _tmp_1 = __converters.fromMessageStatus(entity.getStatus());
        statement.bindString(5, _tmp_1);
        statement.bindLong(6, entity.getCreatedAt());
        if (entity.getDeliveredAt() == null) {
          statement.bindNull(7);
        } else {
          statement.bindLong(7, entity.getDeliveredAt());
        }
      }
    };
    this.__updateAdapterOfMessage = new EntityDeletionOrUpdateAdapter<Message>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `message` SET `id` = ?,`conversationId` = ?,`direction` = ?,`content` = ?,`status` = ?,`createdAt` = ?,`deliveredAt` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Message entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getConversationId());
        final String _tmp = __converters.fromMessageDirection(entity.getDirection());
        statement.bindString(3, _tmp);
        statement.bindString(4, entity.getContent());
        final String _tmp_1 = __converters.fromMessageStatus(entity.getStatus());
        statement.bindString(5, _tmp_1);
        statement.bindLong(6, entity.getCreatedAt());
        if (entity.getDeliveredAt() == null) {
          statement.bindNull(7);
        } else {
          statement.bindLong(7, entity.getDeliveredAt());
        }
        statement.bindString(8, entity.getId());
      }
    };
  }

  @Override
  public Object insert(final Message message, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfMessage.insert(message);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final Message message, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfMessage.handle(message);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<Message>> observeForConversation(final String conversationId) {
    final String _sql = "SELECT * FROM message WHERE conversationId = ? ORDER BY createdAt ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, conversationId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"message"}, new Callable<List<Message>>() {
      @Override
      @NonNull
      public List<Message> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversationId");
          final int _cursorIndexOfDirection = CursorUtil.getColumnIndexOrThrow(_cursor, "direction");
          final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfDeliveredAt = CursorUtil.getColumnIndexOrThrow(_cursor, "deliveredAt");
          final List<Message> _result = new ArrayList<Message>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Message _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpConversationId;
            _tmpConversationId = _cursor.getString(_cursorIndexOfConversationId);
            final MessageDirection _tmpDirection;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfDirection);
            _tmpDirection = __converters.toMessageDirection(_tmp);
            final String _tmpContent;
            _tmpContent = _cursor.getString(_cursorIndexOfContent);
            final MessageStatus _tmpStatus;
            final String _tmp_1;
            _tmp_1 = _cursor.getString(_cursorIndexOfStatus);
            _tmpStatus = __converters.toMessageStatus(_tmp_1);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final Long _tmpDeliveredAt;
            if (_cursor.isNull(_cursorIndexOfDeliveredAt)) {
              _tmpDeliveredAt = null;
            } else {
              _tmpDeliveredAt = _cursor.getLong(_cursorIndexOfDeliveredAt);
            }
            _item = new Message(_tmpId,_tmpConversationId,_tmpDirection,_tmpContent,_tmpStatus,_tmpCreatedAt,_tmpDeliveredAt);
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
  public Object getUndelivered(final MessageStatus deliveredStatus, final MessageDirection outgoing,
      final Continuation<? super List<Message>> $completion) {
    final String _sql = "SELECT * FROM message WHERE status != ? AND direction = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    final String _tmp = __converters.fromMessageStatus(deliveredStatus);
    _statement.bindString(_argIndex, _tmp);
    _argIndex = 2;
    final String _tmp_1 = __converters.fromMessageDirection(outgoing);
    _statement.bindString(_argIndex, _tmp_1);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<Message>>() {
      @Override
      @NonNull
      public List<Message> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversationId");
          final int _cursorIndexOfDirection = CursorUtil.getColumnIndexOrThrow(_cursor, "direction");
          final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfDeliveredAt = CursorUtil.getColumnIndexOrThrow(_cursor, "deliveredAt");
          final List<Message> _result = new ArrayList<Message>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Message _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpConversationId;
            _tmpConversationId = _cursor.getString(_cursorIndexOfConversationId);
            final MessageDirection _tmpDirection;
            final String _tmp_2;
            _tmp_2 = _cursor.getString(_cursorIndexOfDirection);
            _tmpDirection = __converters.toMessageDirection(_tmp_2);
            final String _tmpContent;
            _tmpContent = _cursor.getString(_cursorIndexOfContent);
            final MessageStatus _tmpStatus;
            final String _tmp_3;
            _tmp_3 = _cursor.getString(_cursorIndexOfStatus);
            _tmpStatus = __converters.toMessageStatus(_tmp_3);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final Long _tmpDeliveredAt;
            if (_cursor.isNull(_cursorIndexOfDeliveredAt)) {
              _tmpDeliveredAt = null;
            } else {
              _tmpDeliveredAt = _cursor.getLong(_cursorIndexOfDeliveredAt);
            }
            _item = new Message(_tmpId,_tmpConversationId,_tmpDirection,_tmpContent,_tmpStatus,_tmpCreatedAt,_tmpDeliveredAt);
            _result.add(_item);
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
