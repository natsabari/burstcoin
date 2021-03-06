package brs.db.sql;

import brs.DigitalGoodsStore;
import brs.Burst;
import brs.crypto.EncryptedData;
import brs.db.BurstIterator;
import brs.db.BurstKey;
import brs.db.VersionedEntityTable;
import brs.db.VersionedValuesTable;
import brs.db.store.DigitalGoodsStoreStore;

import org.jooq.DSLContext;
import static brs.schema.Tables.PURCHASE;
import static brs.schema.Tables.PURCHASE_FEEDBACK;
import static brs.schema.Tables.PURCHASE_PUBLIC_FEEDBACK;
import static brs.schema.Tables.GOODS;

import java.sql.*;

public class SqlDigitalGoodsStoreStore implements DigitalGoodsStoreStore {

  private static final DbKey.LongKeyFactory<DigitalGoodsStore.Purchase> feedbackDbKeyFactory
    = new DbKey.LongKeyFactory<DigitalGoodsStore.Purchase>("id") {
        @Override
        public BurstKey newKey(DigitalGoodsStore.Purchase purchase) {
          return purchase.dbKey;
        }
      };

  protected final BurstKey.LongKeyFactory<DigitalGoodsStore.Purchase> purchaseDbKeyFactory
    = new DbKey.LongKeyFactory<DigitalGoodsStore.Purchase>("id") {
        @Override
        public BurstKey newKey(DigitalGoodsStore.Purchase purchase) {
          return purchase.dbKey;
        }
      };

  private final VersionedEntityTable<DigitalGoodsStore.Purchase> purchaseTable
    = new VersionedEntitySqlTable<DigitalGoodsStore.Purchase>("purchase", brs.schema.Tables.PURCHASE, purchaseDbKeyFactory) {
        @Override
        protected DigitalGoodsStore.Purchase load(DSLContext ctx, ResultSet rs) throws SQLException {
          return new SQLPurchase(rs);
        }

        @Override
        protected void save(DSLContext ctx, DigitalGoodsStore.Purchase purchase) throws SQLException {
          savePurchase(ctx, purchase);
        }

        @Override
        protected String defaultSort() {
          return " ORDER BY timestamp DESC, id ASC ";
        }
      };

  @Deprecated
  private final VersionedValuesTable<DigitalGoodsStore.Purchase, EncryptedData> feedbackTable
    = new VersionedValuesSqlTable<DigitalGoodsStore.Purchase, EncryptedData>("purchase_feedback", brs.schema.Tables.PURCHASE_FEEDBACK, feedbackDbKeyFactory) {

        @Override
        protected EncryptedData load(DSLContext ctx, ResultSet rs) throws SQLException {
          byte[] data = rs.getBytes("feedback_data");
          byte[] nonce = rs.getBytes("feedback_nonce");
          return new EncryptedData(data, nonce);
        }

        @Override
        protected void save(DSLContext ctx, DigitalGoodsStore.Purchase purchase, EncryptedData encryptedData) throws SQLException {
          byte[] data  = null;
          byte[] nonce = null;
          if ( encryptedData.getData() != null ) {
            data  = encryptedData.getData();
            nonce = encryptedData.getNonce();
          }
          ctx.insertInto(
            PURCHASE_FEEDBACK,
            PURCHASE_FEEDBACK.ID,
            PURCHASE_FEEDBACK.FEEDBACK_DATA, PURCHASE_FEEDBACK.FEEDBACK_NONCE,
            PURCHASE_FEEDBACK.HEIGHT, PURCHASE_FEEDBACK.LATEST
          ).values(
            purchase.getId(),
            data, nonce,
            brs.Burst.getBlockchain().getHeight(), true
          ).execute();
        }
      };

  protected final DbKey.LongKeyFactory<DigitalGoodsStore.Purchase> publicFeedbackDbKeyFactory
    = new DbKey.LongKeyFactory<DigitalGoodsStore.Purchase>("id") {
        @Override
        public BurstKey newKey(DigitalGoodsStore.Purchase purchase) {
          return purchase.dbKey;
        }
      };

  private final VersionedValuesTable<DigitalGoodsStore.Purchase, String> publicFeedbackTable
    = new VersionedValuesSqlTable<DigitalGoodsStore.Purchase, String>("purchase_public_feedback", brs.schema.Tables.PURCHASE_PUBLIC_FEEDBACK, publicFeedbackDbKeyFactory) {

        @Override
        protected String load(DSLContext ctx, ResultSet rs) throws SQLException {
          return rs.getString("public_feedback");
        }

        @Override
        protected void save(DSLContext ctx, DigitalGoodsStore.Purchase purchase, String publicFeedback) throws SQLException {
          ctx.mergeInto(
            PURCHASE_PUBLIC_FEEDBACK,
            PURCHASE_PUBLIC_FEEDBACK.ID, PURCHASE_PUBLIC_FEEDBACK.PUBLIC_FEEDBACK,
            PURCHASE_PUBLIC_FEEDBACK.HEIGHT, PURCHASE_PUBLIC_FEEDBACK.LATEST
          )
          .key(PURCHASE_PUBLIC_FEEDBACK.ID, PURCHASE_PUBLIC_FEEDBACK.HEIGHT)
          .values(
            purchase.getId(), publicFeedback,
            brs.Burst.getBlockchain().getHeight(),
            true
          )
          .execute();
        }
      };

  private final BurstKey.LongKeyFactory<DigitalGoodsStore.Goods> goodsDbKeyFactory = new DbKey.LongKeyFactory<DigitalGoodsStore.Goods>("id") {
      @Override
      public BurstKey newKey(DigitalGoodsStore.Goods goods) {
        return goods.dbKey;
      }
    };

  private final VersionedEntityTable<DigitalGoodsStore.Goods> goodsTable
    = new VersionedEntitySqlTable<DigitalGoodsStore.Goods>("goods", brs.schema.Tables.GOODS, goodsDbKeyFactory) {

        @Override
        protected DigitalGoodsStore.Goods load(DSLContext ctx, ResultSet rs) throws SQLException {
          return new SQLGoods(rs);
        }

        @Override
        protected void save(DSLContext ctx, DigitalGoodsStore.Goods goods) throws SQLException {
          saveGoods(ctx, goods);
        }

        @Override
        protected String defaultSort() {
          return " ORDER BY timestamp DESC, id ASC ";
        }
      };

  @Override
  public BurstIterator<DigitalGoodsStore.Purchase> getExpiredPendingPurchases(final int timestamp) {
    return getPurchaseTable().getManyBy(PURCHASE.DEADLINE.gt(timestamp).and(PURCHASE.PENDING.isTrue()), 0, -1);
  }

  private EncryptedData loadEncryptedData(ResultSet rs, String dataColumn, String nonceColumn) throws SQLException {
    byte[] data = rs.getBytes(dataColumn);
    if (data == null) {
      return null;
    }
    return new EncryptedData(data, rs.getBytes(nonceColumn));
  }

  @Override
  public BurstKey.LongKeyFactory<DigitalGoodsStore.Purchase> getFeedbackDbKeyFactory() {
    return feedbackDbKeyFactory;
  }

  @Override
  public BurstKey.LongKeyFactory<DigitalGoodsStore.Purchase> getPurchaseDbKeyFactory() {
    return purchaseDbKeyFactory;
  }

  @Override
  public VersionedEntityTable<DigitalGoodsStore.Purchase> getPurchaseTable() {
    return purchaseTable;
  }

  @Override
  public VersionedValuesTable<DigitalGoodsStore.Purchase, EncryptedData> getFeedbackTable() {
    return feedbackTable;
  }

  @Override
  public DbKey.LongKeyFactory<DigitalGoodsStore.Purchase> getPublicFeedbackDbKeyFactory() {
    return publicFeedbackDbKeyFactory;
  }

  public VersionedValuesTable<DigitalGoodsStore.Purchase, String> getPublicFeedbackTable() {
    return publicFeedbackTable;
  }

  @Override
  public BurstKey.LongKeyFactory<DigitalGoodsStore.Goods> getGoodsDbKeyFactory() {
    return goodsDbKeyFactory;
  }

  @Override
  public VersionedEntityTable<DigitalGoodsStore.Goods> getGoodsTable() {
    return goodsTable;
  }

  protected void saveGoods(DSLContext ctx, DigitalGoodsStore.Goods goods) throws SQLException {
    ctx.mergeInto(
      GOODS,
      GOODS.ID, GOODS.SELLER_ID, GOODS.NAME, GOODS.DESCRIPTION, GOODS.TAGS,
      GOODS.TIMESTAMP, GOODS.QUANTITY, GOODS.PRICE, GOODS.DELISTED,
      GOODS.HEIGHT, GOODS.LATEST
    )
    .key(PURCHASE.ID, PURCHASE.HEIGHT)
    .values(
      goods.getId(), goods.getSellerId(), goods.getName(), goods.getDescription(), goods.getTags(),
      goods.getTimestamp(), goods.getQuantity(), goods.getPriceNQT(), goods.isDelisted(),
      brs.Burst.getBlockchain().getHeight(), true
    )
    .execute();
  }

  protected void savePurchase(DSLContext ctx, DigitalGoodsStore.Purchase purchase) throws SQLException {
    byte[] note        = null;
    byte[] nonce       = null;
    byte[] goods       = null;
    byte[] goodsNonce  = null;
    byte[] refundNote  = null;
    byte[] refundNonce = null;
    if ( purchase.getNote() != null ) {
      note  = purchase.getEncryptedGoods().getData();
      nonce = purchase.getEncryptedGoods().getNonce();
    }
    if ( purchase.getEncryptedGoods().getData() != null ) {
      goods      = purchase.getEncryptedGoods().getData();
      goodsNonce = purchase.getEncryptedGoods().getNonce();
    }
    if ( purchase.getRefundNote().getData() != null ) {
      refundNote  = purchase.getRefundNote().getData();
      refundNonce = purchase.getRefundNote().getNonce();
    }
    ctx.mergeInto(
      PURCHASE,
      PURCHASE.ID, PURCHASE.BUYER_ID, PURCHASE.GOODS_ID, PURCHASE.SELLER_ID,
      PURCHASE.QUANTITY, PURCHASE.PRICE, PURCHASE.DEADLINE,
      PURCHASE.NOTE, PURCHASE.NONCE,
      PURCHASE.TIMESTAMP, PURCHASE.PENDING,
      PURCHASE.GOODS, PURCHASE.GOODS_NONCE,
      PURCHASE.REFUND_NOTE, PURCHASE.REFUND_NONCE,
      PURCHASE.HAS_FEEDBACK_NOTES,
      PURCHASE.HAS_PUBLIC_FEEDBACKS,
      PURCHASE.DISCOUNT, PURCHASE.REFUND, PURCHASE.HEIGHT, PURCHASE.LATEST
    )
    .key(PURCHASE.ID, PURCHASE.HEIGHT)
    .values(
      purchase.getId(), purchase.getBuyerId(), purchase.getGoodsId(), purchase.getSellerId(),
      purchase.getQuantity(), purchase.getPriceNQT(), purchase.getDeliveryDeadlineTimestamp(),
      note, nonce,
      purchase.getTimestamp(), purchase.isPending(),
      goods, goodsNonce,
      refundNote, refundNonce,
      purchase.getFeedbackNotes() != null && purchase.getFeedbackNotes().size() > 0,
      purchase.getPublicFeedback() != null && purchase.getPublicFeedback().size() > 0,
      purchase.getDiscountNQT(), purchase.getRefundNQT(), Burst.getBlockchain().getHeight(), true

    )
    .execute();
  }

  @Override
  public BurstIterator<DigitalGoodsStore.Goods> getGoodsInStock(int from, int to) {
    return goodsTable.getManyBy(GOODS.DELISTED.isFalse().and(GOODS.QUANTITY.gt(0)), from, to);
  }

  @Override
  public BurstIterator<DigitalGoodsStore.Goods> getSellerGoods(final long sellerId, final boolean inStockOnly, int from, int to) {
    return getGoodsTable().getManyBy(
      (
        inStockOnly
          ? GOODS.SELLER_ID.eq(sellerId).and(GOODS.DELISTED.isFalse()).and(GOODS.QUANTITY.gt(0))
          : GOODS.SELLER_ID.eq(sellerId)
      ),
      from, to, " ORDER BY name ASC, timestamp DESC, id ASC "
    );
  }

  @Override
  public BurstIterator<DigitalGoodsStore.Purchase> getAllPurchases(int from, int to) {
    return purchaseTable.getAll(from, to);
  }

  @Override
  public BurstIterator<DigitalGoodsStore.Purchase> getSellerPurchases(long sellerId, int from, int to) {
    return purchaseTable.getManyBy(PURCHASE.SELLER_ID.eq(sellerId), from, to);
  }

  @Override
  public BurstIterator<DigitalGoodsStore.Purchase> getBuyerPurchases(long buyerId, int from, int to) {
    return purchaseTable.getManyBy(PURCHASE.BUYER_ID.eq(buyerId), from, to);
  }

  @Override
  public BurstIterator<DigitalGoodsStore.Purchase> getSellerBuyerPurchases(final long sellerId, final long buyerId, int from, int to) {
    return purchaseTable.getManyBy(PURCHASE.SELLER_ID.eq(sellerId).and(PURCHASE.BUYER_ID.eq(buyerId)), from, to);
  }

  @Override
  public BurstIterator<DigitalGoodsStore.Purchase> getPendingSellerPurchases(final long sellerId, int from, int to) {
    return purchaseTable.getManyBy(PURCHASE.SELLER_ID.eq(sellerId).and(PURCHASE.PENDING.isTrue()), from, to);
  }

  public DigitalGoodsStore.Purchase getPendingPurchase(long purchaseId) {
    DigitalGoodsStore.Purchase purchase =
        purchaseTable.get(purchaseDbKeyFactory.newKey(purchaseId));
    return purchase == null || !purchase.isPending() ? null : purchase;
  }



  private class SQLGoods extends DigitalGoodsStore.Goods {
    private SQLGoods(ResultSet rs) throws SQLException {
      super(
            rs.getLong("id"),
            goodsDbKeyFactory.newKey(rs.getLong("id")),
            rs.getLong("seller_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("tags"),
            rs.getInt("timestamp"),
            rs.getInt("quantity"),
            rs.getLong("price"),
            rs.getBoolean("delisted")
            );
    }
  }




  protected class SQLPurchase extends DigitalGoodsStore.Purchase {

    public SQLPurchase(ResultSet rs) throws SQLException {
      super(
            rs.getLong("id"),
            purchaseDbKeyFactory.newKey(rs.getLong("id")),
            rs.getLong("buyer_id"),
            rs.getLong("goods_id"),
            rs.getLong("seller_id"),
            rs.getInt("quantity"),
            rs.getLong("price"),
            rs.getInt("deadline"),
            loadEncryptedData(rs, "note", "nonce"),
            rs.getInt("timestamp"),
            rs.getBoolean("pending"),
            loadEncryptedData(rs, "goods", "goods_nonce"),
            loadEncryptedData(rs, "refund_note", "refund_nonce"),
            rs.getBoolean("has_feedback_notes"),
            rs.getBoolean("has_public_feedbacks"),
            rs.getLong("discount"),
            rs.getLong("refund")
            );
    }
  }

}
