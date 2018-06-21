package misk.hibernate

import com.google.common.collect.ImmutableSet
import misk.backoff.ExponentialBackoff
import misk.backoff.retry
import misk.jdbc.DataSourceConfig
import misk.jdbc.DataSourceType
import org.hibernate.SessionFactory
import org.hibernate.StaleObjectStateException
import org.hibernate.exception.LockAcquisitionException
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import javax.persistence.OptimisticLockException
import kotlin.reflect.KClass

internal class RealTransacter private constructor(
  private val sessionFactory: SessionFactory,
  private val config: DataSourceConfig,
  private val threadLocalSession: ThreadLocal<Session>,
  private val options: TransacterOptions
) : Transacter {

  constructor(sessionFactory: SessionFactory, config: DataSourceConfig) :
      this(sessionFactory, config, ThreadLocal(), TransacterOptions())

  override val inTransaction: Boolean
    get() = threadLocalSession.get() != null

  override fun <T> transaction(lambda: (session: Session) -> T): T {
    return when {
      options.maxAttempts > 1 -> {
        val backoff = ExponentialBackoff(Duration.ZERO, Duration.ZERO)
        retry(options.maxAttempts, backoff, ::isRetryable) { transactionInternal(lambda) }
      }
      options.maxAttempts == 1 -> transactionInternal(lambda)
      else -> throw IllegalArgumentException()
    }
  }

  private fun <T> transactionInternal(lambda: (session: Session) -> T): T {
    return withSession { session ->
      val transaction = session.hibernateSession.beginTransaction()!!
      val result: T
      try {
        result = lambda(session)
        transaction.commit()
        return@withSession result
      } catch (e: Throwable) {
        if (transaction.isActive) {
          try {
            transaction.rollback()
          } catch (suppressed: Exception) {
            e.addSuppressed(suppressed)
          }
        }
        throw e
      }
    }
  }

  override fun retries(numRetries: Int): Transacter {
    require(numRetries >= 0)
    return RealTransacter(
      sessionFactory,
      config,
      threadLocalSession,
      options.copy(maxAttempts = numRetries + 1)
    )
  }

  private fun <T> withSession(lambda: (session: Session) -> T): T {
    check(threadLocalSession.get() == null) { "Attempted to start a nested session" }

    val realSession = RealSession(sessionFactory.openSession(), config)
    threadLocalSession.set(realSession)

    try {
      return lambda(realSession)
    } finally {
      closeSession()
    }
  }

  private fun closeSession() {
    try {
      threadLocalSession.get().hibernateSession.close()
    } finally {
      threadLocalSession.remove()
    }
  }

  private fun isRetryable(e: Exception): Boolean {
    // TODO(jmuia): check the causal chain.
    return when (e) {
      is RetryTransactionException,
      is StaleObjectStateException,
      is LockAcquisitionException,
      is OptimisticLockException -> true
      else -> false
    }
  }

  // NB: all options should be immutable types as copy() is shallow.
  internal data class TransacterOptions(
    val maxAttempts: Int = 3
  )

  internal class RealSession(
    val session: org.hibernate.Session,
    val config: DataSourceConfig
  ) : Session {
    override val hibernateSession = session

    override fun <T : DbEntity<T>> save(entity: T): Id<T> {
      @Suppress("UNCHECKED_CAST") // Entities always use Id<T> as their ID type.
      return session.save(entity) as Id<T>
    }

    override fun <T : DbEntity<T>> load(id: Id<T>, type: KClass<T>): T {
      return session.get(type.java, id)
    }

    override fun shards(): Set<Shard> {
      if (config.type == DataSourceType.VITESS) {
        return useConnection { connection ->
          connection.createStatement().use {
            it.executeQuery("SHOW VITESS_SHARDS")
                .map { parseShard(it.getString(1)) }
                .toSet()
          }
        }
      } else {
        return SINGLE_SHARD_SET
      }
    }

    private fun parseShard(string: String): Shard {
      val (keyspace, shard) = string.split('/', limit = 2)
      return Shard(Keyspace(keyspace), shard)
    }

    override fun <T> target(shard: Shard, function: () -> T): T {
      if (config.type == DataSourceType.VITESS) {
        return useConnection { connection ->
          val previousTarget = connection.createStatement().use { statement ->
            val rs = statement.executeQuery("SELECT database()")
            check(rs.next())
            val previousTarget = rs.getString(1)
            statement.execute("USE `$shard`")
            previousTarget
          }
          try {
            function()
          } finally {
            val sql = if (previousTarget.isBlank()) {
              "USE"
            } else {
              "USE `$previousTarget`"
            }
            connection.createStatement().use { it.execute(sql) }
          }
        }
      } else {
        return function();
      }
    }

    override fun <T> useConnection(work: (Connection) -> T): T {
      return session.doReturningWork(work)
    }

    companion object {
      val SINGLE_KEYSPACE = Keyspace("keyspace")
      val SINGLE_SHARD = Shard(SINGLE_KEYSPACE, "0")
      val SINGLE_SHARD_SET = ImmutableSet.of(SINGLE_SHARD)
    }
  }
}

private fun <T> ResultSet.map(function: (ResultSet) -> T): List<T> {
  val result = mutableListOf<T>()
  while (this.next()) {
    result.add(function(this))
  }
  return result
}
