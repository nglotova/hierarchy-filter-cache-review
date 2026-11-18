import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class ItemCacheTest {
  private val ticker = FakeTicker()
  private val ttl = Duration.ofMinutes(1)

  private fun cache(maximumSize: Long = 1_000) = ItemCache<String, String>(
    ttl = ttl,
    maximumSize = maximumSize,
    ticker = ticker,
    executor = Executor { it.run() }, // eviction/removal runs on the calling thread: deterministic
  )

  @Test
  fun `value is returned before ttl and not after`() {
    val cache = cache()
    cache.put("k", "v")

    ticker.advance(ttl.minusNanos(1))
    assertEquals("v", cache.getIfPresent("k"))

    ticker.advance(Duration.ofNanos(1))
    assertNull(cache.getIfPresent("k"))
  }

  @Test
  fun `expired entries are removed, not just hidden`() {
    val cache = cache()
    repeat(100) { cache.put("k$it", "v") }

    ticker.advance(ttl)
    cache.cleanUp()

    assertEquals(0, cache.estimatedSize())
  }

  @Test
  fun `size stays within maximumSize after many distinct writes`() {
    val cache = cache(maximumSize = 100)
    repeat(10_000) { cache.put("k$it", "v") }

    cache.cleanUp()

    assertTrue(cache.estimatedSize() <= 100, "size was ${cache.estimatedSize()}")
  }

  @Test
  fun `concurrent misses for the same key trigger a single load`() {
    val cache = cache()
    val threads = 32
    val loads = AtomicInteger()
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(threads)
    try {
      val results = (1..threads).map {
        pool.submit<String> {
          start.await()
          cache.get("hot") {
            loads.incrementAndGet()
            Thread.sleep(100) // slow backend: keeps the load in flight while the others arrive
            "loaded"
          }
        }
      }
      start.countDown()

      assertEquals(List(threads) { "loaded" }, results.map { it.get(5, TimeUnit.SECONDS) })
      assertEquals(1, loads.get())
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `failed load is not cached and is retried on the next call`() {
    val cache = cache()

    assertFailsWith<IllegalStateException> { cache.get("k") { error("backend unavailable") } }

    assertEquals("v", cache.get("k") { "v" })
  }

  @Test
  fun `expired value is reloaded through the loader`() {
    val cache = cache()
    cache.put("k", "old")

    ticker.advance(ttl)

    assertEquals("new", cache.get("k") { "new" })
  }

  @Test
  fun `invalidate removes the key`() {
    val cache = cache()
    cache.put("k", "v")

    cache.invalidate("k")

    assertNull(cache.getIfPresent("k"))
  }

  @Test
  fun `hits and misses are recorded`() {
    val cache = cache()

    cache.get("k") { "v" } // miss + load
    cache.getIfPresent("k") // hit
    cache.getIfPresent("absent") // miss

    val stats = cache.stats()
    assertEquals(1, stats.hitCount())
    assertEquals(2, stats.missCount())
    assertEquals(1, stats.loadSuccessCount())
  }

  @Test
  fun `invalid configuration is rejected`() {
    assertFailsWith<IllegalArgumentException> { ItemCache<String, String>(ttl = Duration.ZERO, maximumSize = 1) }
    assertFailsWith<IllegalArgumentException> { ItemCache<String, String>(maximumSize = 0) }
  }

  private class FakeTicker : Ticker {
    private val nanos = AtomicLong()
    override fun read(): Long = nanos.get()
    fun advance(duration: Duration) {
      nanos.addAndGet(duration.toNanos())
    }
  }
}
