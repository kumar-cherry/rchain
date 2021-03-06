package coop.rchain.rspace.history

import java.nio.ByteBuffer

import coop.rchain.rspace.LMDBStore
import coop.rchain.rspace.util.withResource
import coop.rchain.shared.AttemptOps._
import org.lmdbjava.DbiFlags.MDB_CREATE
import org.lmdbjava._
import scodec.Codec
import scodec.bits.BitVector

import scala.collection.JavaConverters._

class LMDBTrieStore[K, V] private (val env: Env[ByteBuffer], _dbTrie: Dbi[ByteBuffer])(
    implicit
    codecK: Codec[K],
    codecV: Codec[V])
    extends ITrieStore[Txn[ByteBuffer], K, V] {

  private[rspace] def createTxnRead(): Txn[ByteBuffer] = env.txnRead

  private[rspace] def createTxnWrite(): Txn[ByteBuffer] = env.txnWrite

  private[rspace] def withTxn[R](txn: Txn[ByteBuffer])(f: Txn[ByteBuffer] => R): R =
    try {
      val ret: R = f(txn)
      txn.commit()
      ret
    } catch {
      case ex: Throwable =>
        txn.abort()
        throw ex
    } finally {
      txn.close()
    }

  private[rspace] def put(txn: Txn[ByteBuffer], key: Blake2b256Hash, value: Trie[K, V]): Unit = {
    val encodedKey   = Codec[Blake2b256Hash].encode(key).get
    val encodedValue = Codec[Trie[K, V]].encode(value).get
    val keyBuff      = LMDBStore.toByteBuffer(encodedKey)
    val valBuff      = LMDBStore.toByteBuffer(encodedValue)
    _dbTrie.put(txn, keyBuff, valBuff)
  }

  private[rspace] def get(txn: Txn[ByteBuffer], key: Blake2b256Hash): Option[Trie[K, V]] = {
    val encodedKey = Codec[Blake2b256Hash].encode(key).get
    val keyBuff    = LMDBStore.toByteBuffer(encodedKey)
    Option(_dbTrie.get(txn, keyBuff)).map { (buffer: ByteBuffer) =>
      // ht: Yes, I want to throw an exception if deserialization fails
      Codec[Trie[K, V]].decode(BitVector(buffer)).map(_.value).get
    }
  }

  private[rspace] def toMap: Map[Blake2b256Hash, Trie[K, V]] =
    withTxn(createTxnRead()) { txn =>
      withResource(_dbTrie.iterate(txn)) { (it: CursorIterator[ByteBuffer]) =>
        it.asScala.foldLeft(Map.empty[Blake2b256Hash, Trie[K, V]]) {
          (map: Map[Blake2b256Hash, Trie[K, V]], x: CursorIterator.KeyVal[ByteBuffer]) =>
            val key   = Codec[Blake2b256Hash].decode(BitVector(x.key())).map(_.value).get
            val value = Codec[Trie[K, V]].decode(BitVector(x.`val`())).map(_.value).get
            map + ((key, value))
        }
      }
    }

  def close(): Unit =
    _dbTrie.close()
}

object LMDBTrieStore {

  private[this] val trieTableName: String = "Trie"

  def create[K, V](env: Env[ByteBuffer])(implicit
                                         codecK: Codec[K],
                                         codecV: Codec[V]): LMDBTrieStore[K, V] = {
    val dbTrie: Dbi[ByteBuffer] = env.openDbi(trieTableName, MDB_CREATE)
    new LMDBTrieStore[K, V](env, dbTrie)
  }
}
