import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import com.github.benmanes.caffeine.cache.stats.CacheStats
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * Suggested replacement for [SimpleCache]: a bounded, time-expiring cache with single-flight loading.
 *
 * Values should be immutable: they are shared by reference between all callers.
 *
 * @param ttl how long an entry stays valid after it was written
 * @param maximumSize upper bound on the number of entries; least valuable entries are evicted beyond it
 * @param ticker monotonic time source (nanoseconds); replaceable in tests
 * @param executor runs eviction and removal work; replaceable in tests to make it synchronous
 */
class ItemCache<K : Any, V : Any>(
  ttl: Duration = Duration.ofMinutes(1),
  maximumSize: Long,
  ticker: Ticker = Ticker.systemTicker(),
  executor: Executor = ForkJoinPool.commonPool(),
) {
  init {
    require(!ttl.isNegative && !ttl.isZero) { "ttl must be positive: $ttl" }
    require(maximumSize > 0) { "maximumSize must be positive: $maximumSize" }
  }

  private val cache: Cache<K, V> = Caffeine.newBuilder()
    .expireAfterWrite(ttl)
    .maximumSize(maximumSize)
    .ticker(ticker)
    .executor(executor)
    .recordStats()
    .build<K, V>()

  /**
   * Returns the cached value, or loads it with [loader] and caches it.
   * Concurrent callers for the same missing key wait for a single load instead of each calling [loader].
   * If [loader] throws, nothing is cached and the exception propagates to the callers.
   */
  fun get(key: K, loader: (K) -> V): V = cache.get(key) { loader(it) }

  /** Returns the cached value, or `null` if it is absent or expired. */
  fun getIfPresent(key: K): V? = cache.getIfPresent(key)

  fun put(key: K, value: V) = cache.put(key, value)

  fun invalidate(key: K) = cache.invalidate(key)

  fun invalidateAll() = cache.invalidateAll()

  /** Approximate number of entries; may briefly include entries that are expired but not yet removed. */
  fun estimatedSize(): Long = cache.estimatedSize()

  fun stats(): CacheStats = cache.stats()

  /** Runs pending eviction and expiration work immediately. Used by tests. */
  internal fun cleanUp() = cache.cleanUp()
}
