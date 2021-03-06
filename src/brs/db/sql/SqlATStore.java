package brs.db.sql;

import brs.Burst;
import brs.at.AT_API_Helper;
import brs.at.AT_Constants;
import brs.db.BurstKey;
import brs.db.VersionedEntityTable;
import brs.db.store.ATStore;
import brs.schema.tables.records.AtRecord;
import brs.schema.tables.records.AtStateRecord;
import org.jooq.Cursor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Record1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import static brs.schema.Tables.AT;
import static brs.schema.Tables.AT_STATE;
import static brs.schema.Tables.TRANSACTION;
import static brs.schema.Tables.ACCOUNT;

import org.jooq.DSLContext;

public class SqlATStore implements ATStore {

  private static final Logger logger = LoggerFactory.getLogger(DbUtils.class);

  private final BurstKey.LongKeyFactory<brs.AT> atDbKeyFactory = new DbKey.LongKeyFactory<brs.AT>("id") {
      @Override
      public BurstKey newKey(brs.AT at) {
        return at.dbKey;
      }
    };
  private final VersionedEntityTable<brs.AT> atTable = new VersionedEntitySqlTable<brs.AT>("at", brs.schema.Tables.AT, atDbKeyFactory) {
      @Override
      protected brs.AT load(DSLContext ctx, ResultSet rs) throws SQLException {
        //return new AT(rs);
        throw new RuntimeException("AT attempted to be created with atTable.load");
      }

      @Override
      protected void save(DSLContext ctx, brs.AT at) throws SQLException {
        saveAT(ctx, at);
      }

      @Override
      protected String defaultSort() {
        return " ORDER BY id ";
      }
    };
  private final BurstKey.LongKeyFactory<brs.AT.ATState> atStateDbKeyFactory = new DbKey.LongKeyFactory<brs.AT.ATState>("at_id") {
      @Override
      public BurstKey newKey(brs.AT.ATState atState) {
        return atState.dbKey;
      }
    };

  private final VersionedEntityTable<brs.AT.ATState> atStateTable = new VersionedEntitySqlTable<brs.AT.ATState>("at_state", brs.schema.Tables.AT_STATE, atStateDbKeyFactory) {
      @Override
      protected brs.AT.ATState load(DSLContext ctx, ResultSet rs) throws SQLException {
        return new SqlATState(rs);
      }

      @Override
      protected void save(DSLContext ctx, brs.AT.ATState atState) throws SQLException {
        saveATState(ctx, atState);
      }

      @Override
      protected String defaultSort() {
        return " ORDER BY prev_height, height, at_id ";
      }
    };

  protected void saveATState(DSLContext ctx, brs.AT.ATState atState) throws SQLException {
    ctx.mergeInto(
      AT_STATE,
      AT_STATE.AT_ID, AT_STATE.STATE, AT_STATE.PREV_HEIGHT, AT_STATE.NEXT_HEIGHT, AT_STATE.SLEEP_BETWEEN,
      AT_STATE.PREV_BALANCE, AT_STATE.FREEZE_WHEN_SAME_BALANCE, AT_STATE.MIN_ACTIVATE_AMOUNT, AT_STATE.HEIGHT, AT_STATE.LATEST
    )
    .key(AT_STATE.AT_ID, AT_STATE.HEIGHT)
    .values(
      atState.getATId(), brs.AT.compressState(atState.getState()), atState.getPrevHeight(), atState.getNextHeight(), atState.getSleepBetween(),
      atState.getPrevBalance(), atState.getFreezeWhenSameBalance(), atState.getMinActivationAmount(), Burst.getBlockchain().getHeight(), true
    )
    .execute();
  }

  protected void saveAT(DSLContext ctx, brs.AT at) throws SQLException {
    ctx.insertInto(
      AT,
      AT.ID, AT.CREATOR_ID, AT.NAME, AT.DESCRIPTION,
      AT.VERSION, AT.CSIZE, AT.DSIZE, AT.C_USER_STACK_BYTES,
      AT.C_CALL_STACK_BYTES, AT.CREATION_HEIGHT,
      AT.AP_CODE, AT.HEIGHT
    ).values(
      AT_API_Helper.getLong(at.getId()), AT_API_Helper.getLong(at.getCreator()), at.getName(), at.getDescription(),
      at.getVersion(), at.getCsize(), at.getDsize(), at.getC_user_stack_bytes(),
      at.getC_call_stack_bytes(), at.getCreationBlockHeight(),
      brs.AT.compressState(at.getApCode()), Burst.getBlockchain().getHeight()
    ).execute();
  }

  @Override
  public boolean isATAccountId(Long id) {
    try ( DSLContext ctx = Db.getDSLContext() ) {
      return ctx.fetchExists(ctx.selectOne().from(AT).where(AT.ID.eq(id)).and(AT.LATEST.isTrue()));
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public List<Long> getOrderedATs() {
    try ( DSLContext ctx = Db.getDSLContext() ) {
      return ctx.selectFrom(
        AT.join(AT_STATE).on(AT.ID.eq(AT_STATE.AT_ID)).join(ACCOUNT).on(AT.ID.eq(ACCOUNT.ID))
      ).where(
        AT.LATEST.isTrue()
      ).and(
        AT_STATE.LATEST.isTrue()
      ).and(
        AT_STATE.NEXT_HEIGHT.lessOrEqual( Burst.getBlockchain().getHeight() + 1)
      ).and(
        ACCOUNT.BALANCE.greaterOrEqual(
          AT_Constants.getInstance().STEP_FEE(Burst.getBlockchain().getHeight())
          * AT_Constants.getInstance().API_STEP_MULTIPLIER(Burst.getBlockchain().getHeight())
        )
      ).and(
        AT_STATE.FREEZE_WHEN_SAME_BALANCE.isFalse().or(
          "account.balance - at_state.prev_balance >= at_state.min_activate_amount"
        )
      ).orderBy(
        AT_STATE.PREV_HEIGHT.asc(), AT_STATE.NEXT_HEIGHT.asc(), AT.ID.asc()
      ).fetch().getValues(AT.ID);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public brs.AT getAT(Long id) {
    try (DSLContext ctx = Db.getDSLContext()) {
      Record record = ctx.select(AT.fields()).select(AT_STATE.fields()).from(AT.join(AT_STATE).on(AT.ID.eq(AT_STATE.AT_ID))).
              where(AT.LATEST.isTrue().
                      and(AT_STATE.LATEST.isTrue()).
                      and(AT.ID.eq(id))).fetchOne();

      if (record == null) {
        return null;
      }

      AtRecord at = record.into(AT);
      AtStateRecord atState = record.into(AT_STATE);

      return createAT(at, atState);
    } catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  private brs.AT createAT(AtRecord at, AtStateRecord atState) {
    return new brs.AT(AT_API_Helper.getByteArray(at.getId()), AT_API_Helper.getByteArray(at.getCreatorId()), at.getName(), at.getDescription(), at.getVersion(),
            atState.getState(), at.getCsize(), at.getDsize(), at.getCUserStackBytes(), at.getCCallStackBytes(), at.getCreationHeight(), atState.getSleepBetween(), atState.getNextHeight(),
            atState.getFreezeWhenSameBalance(), atState.getMinActivateAmount(), at.getApCode());
  }

  @Override
  public List<Long> getATsIssuedBy(Long accountId) {
    try ( DSLContext ctx = Db.getDSLContext() ) {
      return ctx.selectFrom(AT).where(AT.LATEST.isTrue()).and(AT.CREATOR_ID.eq(accountId)).orderBy(AT.CREATION_HEIGHT.desc(), AT.ID.asc()).fetch().getValues(AT.ID);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public Collection<Long> getAllATIds() {
    try ( DSLContext ctx = Db.getDSLContext() ) {
      return ctx.selectFrom(AT).where(AT.LATEST.isTrue()).fetch().getValues(AT.ID);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public BurstKey.LongKeyFactory<brs.AT> getAtDbKeyFactory() {
    return atDbKeyFactory;
  }

  @Override
  public VersionedEntityTable<brs.AT> getAtTable() {
    return atTable;
  }

  @Override
  public BurstKey.LongKeyFactory<brs.AT.ATState> getAtStateDbKeyFactory() {
    return atStateDbKeyFactory;
  }

  @Override
  public VersionedEntityTable<brs.AT.ATState> getAtStateTable() {
    return atStateTable;
  }

  @Override
  public Long findTransaction(int startHeight, int endHeight, Long atID, int numOfTx, long minAmount) {
    try ( DSLContext ctx = Db.getDSLContext() ) {
      return ctx.select(TRANSACTION.ID).from(TRANSACTION).where(
        TRANSACTION.HEIGHT.between(startHeight, endHeight - 1)
      ).and(
        TRANSACTION.RECIPIENT_ID.eq(atID)
      ).and(
        TRANSACTION.AMOUNT.greaterOrEqual(minAmount)
      ).orderBy(
        TRANSACTION.HEIGHT, TRANSACTION.ID
      ).limit(numOfTx).offset(numOfTx + 1).fetchOne(TRANSACTION.ID);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public int findTransactionHeight(Long transactionId, int height, Long atID, long minAmount) {
    Cursor<Record1<Long>> cursor = null;
    try ( DSLContext ctx = Db.getDSLContext() ) {
      cursor = ctx.select(TRANSACTION.ID).from(TRANSACTION).where(
        TRANSACTION.HEIGHT.eq(height)
      ).and(
        TRANSACTION.RECIPIENT_ID.eq(atID)
      ).and(
        TRANSACTION.AMOUNT.greaterOrEqual(minAmount)
      ).orderBy(
        TRANSACTION.HEIGHT, TRANSACTION.ID
      ).fetchLazy();

      int counter = 0;
      while (cursor.hasNext()) {
        counter++;
        if ( cursor.fetchOne().getValue(0) == transactionId ) {
          break;
        }
      }
      return counter;
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
    finally {
      if (cursor != null) {
        cursor.close();
      }
    }
  }

  protected class SqlATState extends brs.AT.ATState {
    private SqlATState(ResultSet rs) throws SQLException {
      super(
            rs.getLong("at_id"),
            rs.getBytes("state"),
            rs.getInt("prev_height"),
            rs.getInt("next_height"),
            rs.getInt("sleep_between"),
            rs.getLong("prev_balance"),
            rs.getBoolean("freeze_when_same_balance"),
            rs.getLong("min_activate_amount")
            );
    }
  }
}
