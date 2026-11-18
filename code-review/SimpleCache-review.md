# Code review: `SimpleCache`

**Context:** an item cache in a highly concurrent application. Expected load: thousands of reads/s, hundreds of writes/s, tens of concurrent threads.

**Verdict:** not ready for production. Individual `put`/`get` calls are thread-safe, but the cache never removes anything, has no size bound, and gives callers no atomic way to load a missing value. Under the stated load, that means memory growth until the process dies and backend load spikes every time a hot key expires. There are also time-source, API, and testability problems.

Recommended fix: replace the hand-rolled map with a thin wrapper over [Caffeine](https://github.com/ben-manes/caffeine) ([`ItemCache.kt`](src/main/kotlin/ItemCache.kt), tests in [`ItemCacheTest.kt`](src/test/kotlin/ItemCacheTest.kt)).

## What is already correct

- `ConcurrentHashMap` makes each `put`/`get` atomic per key. Reads take no lock, and the map guarantees safe publication: a reader never sees a partially constructed `CacheEntry`.
- `CacheEntry` is immutable (`val` fields), so no extra synchronisation is needed for the entry itself.
- Expired values are never returned (apart from the clock problem in finding 3).

## Summary

| #  | Severity | Issue                                                                     |
|----|----------|---------------------------------------------------------------------------|
| 1  | Blocker  | Expired entries are never removed and there is no size bound: memory grows without limit |
| 2  | Blocker  | No atomic get-or-load: a cache stampede on every miss or expiry of a hot key |
| 3  | High     | TTL uses wall-clock time (`System.currentTimeMillis()`)                   |
| 4  | High     | Nullable type parameters: NPE at runtime on a null key; a cached null looks like a miss |
| 5  | High     | No invalidation: data can be stale for up to 60 s on every pod            |
| 6  | Medium   | Lost update: a slow loader can overwrite a newer value                    |
| 7  | Medium   | `size()` counts expired entries                                           |
| 8  | Medium   | TTL and clock are hard-coded, so expiry can't be configured or tested     |
| 9  | Medium   | Entries written together expire together (no jitter or refresh-ahead)     |
| 10 | Low      | No metrics (hit rate, load time, evictions)                               |
| 11 | Low      | `CacheEntry` is a public `data class`: leaks into the API and compares by value |
| 12 | Low      | Values are shared by reference; mutable values are unsafe across threads  |

## Findings

### 1. Unbounded memory growth (Blocker)

`get()` checks expiry but never removes the expired entry, and nothing else removes entries either. A key that is written once and never read again stays in the map forever. There is also no maximum size or weight.

**Impact.** At hundreds of writes/s over a growing key space (per user, per issue, per worklog), 300 new keys/s is about 26 million entries a day. Each entry costs roughly 60 bytes before the key and value (a `ConcurrentHashMap.Node`, the `CacheEntry`, and the table slot). With modest keys and values that is several GB a day. Before the actual `OutOfMemoryError`, the old generation fills up, GC pauses get longer, and p99 latency degrades. Then Kubernetes OOM-kills the pod at its memory limit. The pod restarts with a cold cache, which triggers finding 2 across every key at once. Even with a bounded key space, expired values stay on the heap until they happen to be overwritten.

**Fix.** Bound the cache by entry count or by weight (`maximumSize` / `maximumWeight`), using an eviction policy that keeps the hot set: Caffeine's W-TinyLFU beats plain LRU on hit rate. Remove expired entries proactively (Caffeine uses a timer wheel with amortised O(1) cost). In a hand-rolled version, at minimum:
- remove expired entries on read with `cache.remove(key, entry)`. The two-argument form only removes that exact entry, so a fresh value put concurrently survives;
- add a periodic sweep;
- add a size bound.

### 2. Cache stampede (Blocker)

The API only offers `get` and `put`, so every caller has to write check-then-act code:

```kotlin
val value = cache.get(key) ?: loadFromBackend(key).also { cache.put(key, it) }
```

This isn't atomic. While a key is missing (cold start, eviction, or every 60 s when it expires), every concurrent reader misses and starts its own load.

**Impact.** With thousands of reads/s and tens of threads, a hot key's expiry sends up to every request thread to the backend at once for the same value, once a minute, per hot key. For a Jira Cloud app the backend is often the Jira REST API, which rate-limits: the stampede turns into HTTP 429 responses and user-visible errors, exactly when the cache should be absorbing load. Threads pile up waiting on the slow backend, and thread-pool exhaustion follows.

**Fix.** Offer `get(key, loader)` with per-key single-flight loading: only one caller loads a key, and the others wait for its result.
- Caffeine's `Cache.get(key, mappingFunction)` does this atomically.
- For slow loads, prefer `AsyncCache` / `AsyncLoadingCache`, which store a `CompletableFuture`. A synchronous compute holds a `ConcurrentHashMap` bin lock for the whole load. That blocks unrelated keys hashed to the same bin, and touching the same cache from inside the loader fails with `IllegalStateException: Recursive update`.
- For hot keys, use `refreshAfterWrite`. Readers keep getting the current value while one background reload runs, so there is no latency cliff at expiry.

### 3. Wall-clock time for TTL (High)

`System.currentTimeMillis()` is wall-clock time. It can jump: NTP step corrections, manual changes, VM pause or live migration.

**Impact.** If the clock moves back by Δ, every entry lives for TTL + Δ, so stale data can be served for hours. If it moves forward, every entry expires at once, which is a stampede (finding 2) across the whole key space.

**Fix.** Measure elapsed time with a monotonic source, `System.nanoTime()`, behind an injectable ticker (see finding 8).

### 4. Nullable type parameters (High)

`class SimpleCache<K, V>` gives both parameters the implicit upper bound `Any?`, so `SimpleCache<String?, Foo?>` compiles.

**Impact.**
- **Null keys:** `put(null, v)` compiles but throws an NPE at runtime, because `ConcurrentHashMap` rejects null keys. The type system could have caught it at compile time.
- **Null values:** they are stored without error (wrapped in `CacheEntry`), but `get()` returns `null` for a cached null, a miss, and an expired entry alike. Callers treat the cached null as a miss and reload every time, so caching "not found" results silently does nothing. That pattern is often what protects the backend from lookups of missing IDs.

**Fix.** Declare `class SimpleCache<K : Any, V : Any>`. If "not found" results need caching, model them explicitly with a sealed type or `Optional`.

### 5. No invalidation (High)

There is no `invalidate`, `remove` or `clear`.

**Impact.** When the underlying data changes (a user edits a worklog), the cache keeps serving the old value for up to 60 s, and there is no way to react to change events such as webhooks. With N replicas in Kubernetes, each pod holds its own copy, so users see different values depending on which pod serves the request.

**Fix.** Add `invalidate(key)` and `invalidateAll()`. Agree on an acceptable staleness per use case. Across pods, either broadcast invalidations (Redis pub/sub, SNS) or use a shared cache (ElastiCache) where consistency matters more than latency.

### 6. Lost update (Medium)

`put` is unconditional: the last writer wins, whatever the age of its data.

**Impact.**
1. Thread T1 misses and reads v1 from the database, slowly.
2. Thread T2 updates the database to v2 and caches v2, or invalidates the key.
3. T1 finishes and puts v1.

v1 is now served for a full TTL even though v2 is current.

**Fix.** Load through the cache's atomic loader (finding 2) and invalidate after the database commit. In Caffeine, an `invalidate` that arrives during an in-flight load of the same key waits for the load to finish and then removes the value, which closes the window on a single node. Alternatively, store a version from the source and replace an entry only when the incoming version is newer (`compute`).

### 7. `size()` counts expired entries (Medium)

`size()` returns `ConcurrentHashMap.size`, which includes expired entries. Under concurrent updates it is also only a weakly consistent estimate, and it is capped at `Int.MAX_VALUE` (`mappingCount()` returns a `Long`).

**Impact.** Dashboards and capacity alerts based on it are wrong, and it can't be used to judge the cache's sizing or effectiveness.

**Fix.** Expose an `estimatedSize()` that excludes expired entries, or count only live ones, and document that it is approximate.

### 8. Hard-coded TTL and clock (Medium)

`ttlMs = 60000` is a private constant, and the clock is called directly.

**Impact.** TTL can't be tuned per use case or per environment. Expiry can't be tested without sleeping 60 s, so in practice it goes untested. No tests were submitted.

**Fix.** Pass constructor parameters (`ttl: Duration`, `maximumSize`, a `ticker`) and use a fake ticker in tests.

### 9. Synchronised expiry (Medium)

Entries written together expire together: cache warm-up after a deploy, or a batch import.

**Impact.** Backend load spikes every 60 s; combined with finding 2, a burst of stampedes.

**Fix.** Add random jitter to each entry's TTL (Caffeine's `expireAfter(Expiry)`), or use `refreshAfterWrite` so hot entries are reloaded in the background before they expire.

### 10. No observability (Low)

There are no hit, miss, load-time, load-failure or eviction counts.

**Impact.** There is no way to tell whether the cache helps, whether it's sized correctly, or whether the TTL is right.

**Fix.** Caffeine's `recordStats()`, exported through Micrometer's `CaffeineCacheMetrics`.

### 11. `CacheEntry` is a public `data class` (Low)

A nested class in Kotlin is public by default, so `SimpleCache.CacheEntry` becomes part of the public API. `data` also generates `equals`/`hashCode` over `value`. Any conditional map operation (`remove(key, entry)`, `replace(key, old, new)`) would then call `V.equals`, which may be expensive or ill-defined, when identity comparison is what's needed.

**Fix.** Make it a `private class` (no `data`).

### 12. Mutable values are shared (Low)

`get` returns the stored instance. `ConcurrentHashMap` guarantees visibility of the object as it was at `put` time, not of later changes. If `V` is mutable and a caller modifies the returned object, other threads see unsynchronised partial updates, and the cached value is corrupted for everyone.

**Fix.** Require immutable values (document it, or use immutable types), or copy on read and write.

## Recommended implementation

Caffeine already covers all of the above under this load profile: bounded W-TinyLFU eviction, amortised O(1) expiration, per-key single-flight loading, asynchronous refresh, a pluggable ticker and statistics. Reimplementing those correctly (a concurrent eviction policy, a timer wheel, single-flight futures) is a large and bug-prone task for no product benefit. [`ItemCache.kt`](src/main/kotlin/ItemCache.kt) is the suggested replacement. The core of it (excerpt; see the file for the full class):

```kotlin
class ItemCache<K : Any, V : Any>(                 // non-null keys and values (finding 4)
  ttl: Duration = Duration.ofMinutes(1),          // configurable (finding 8)
  maximumSize: Long,
  ticker: Ticker = Ticker.systemTicker(),          // monotonic, fake in tests (findings 3, 8)
  executor: Executor = ForkJoinPool.commonPool(),
) {
  private val cache: Cache<K, V> = Caffeine.newBuilder()
    .expireAfterWrite(ttl)
    .maximumSize(maximumSize)      // finding 1
    .ticker(ticker)
    .executor(executor)
    .recordStats()                 // finding 10
    .build<K, V>()

  // Single-flight: concurrent callers for the same missing key share one load (findings 2, 6).
  fun get(key: K, loader: (K) -> V): V = cache.get(key) { loader(it) }

  fun invalidate(key: K) = cache.invalidate(key)  // finding 5

  // ...
}
```

For loads that take more than a few milliseconds, switch to `Caffeine.newBuilder()...buildAsync()` and add `refreshAfterWrite` for hot keys (see finding 2).

## Tests

[`ItemCacheTest.kt`](src/test/kotlin/ItemCacheTest.kt) uses a fake ticker and a same-thread executor, so it runs fast and gives the same result every time:

- a value is returned before the TTL and not after it, without sleeping;
- expired entries are actually removed from the cache, not just hidden;
- the cache stays within `maximumSize` after many distinct writes;
- 32 threads requesting the same missing key cause exactly one load, and all of them get the same value;
- a failed load is not cached, and the next call retries it;
- `invalidate` removes the key;
- hits and misses are counted in `stats()`.
