# dfs-hierarchy-filter

Take-home assessment for Tempo, in two parts:

1. [`hierarchy/`](hierarchy): implementation and tests of `Hierarchy.filter()` for a forest stored as parallel DFS-ordered arrays of node IDs and depths.
2. [`code-review/`](code-review): review of the `SimpleCache` implementation ([SimpleCache-review.md](code-review/SimpleCache-review.md)), with a suggested replacement and its tests.

## Running

Requires JDK 21. If it isn't installed, Gradle downloads it automatically.

```bash
./gradlew test                  # both modules
./gradlew :hierarchy:test       # filter() only
./gradlew :code-review:test     # cache replacement only
```

## Part 1: `Hierarchy.filter()`

### Approach

In DFS order, the subtree of a node at depth `D` is exactly the contiguous run of nodes that follow it with depth greater than `D`. So the filter is a single forward pass that keeps one piece of state: the depth of the rejected node whose subtree is currently being skipped.

- If the current node is deeper than that depth, it is inside a rejected subtree and is skipped without calling the predicate.
- Otherwise the skip ends. The node is kept if it passes the predicate; if it fails, its depth becomes the new skip depth.

Properties:

- O(n) time and O(n) extra space, no recursion, so arbitrarily deep trees are safe.
- The predicate is called at most once per node, and never for descendants of a rejected node.
- A kept node always has all its ancestors kept, so its depth is unchanged. The result therefore satisfies the same depth invariants as the input, and no depth recalculation is needed.
- Output arrays are allocated at full size and trimmed once at the end; when nothing is removed, the trim is skipped.

### Tests

[`FilterTest.kt`](hierarchy/src/test/kotlin/FilterTest.kt) keeps the provided test unchanged and adds:

- **Edge cases:** empty input (predicate never called), a single node, everything kept, nothing kept.
- **Subtree removal:** a rejected leaf, a rejected inner node followed by a sibling that must survive, a rejected root in a forest, depth dropping several levels at once, consecutive rejected siblings, a cut deep inside a kept subtree, descendants that pass on their own under a rejected ancestor.
- **Predicate calls:** called once per reachable node, never for descendants of a rejected node.
- **Structure:**
  - the input is not modified;
  - `filter` works on any `Hierarchy` implementation, not only `ArrayBasedHierarchy`;
  - a one-million-level chain runs without stack overflow.
- **Randomized checks:**
  - results on 500 random forests match a simple reference implementation, and every result is checked against the depth invariants and for unique IDs;
  - `filter(a).filter(b)` gives the same result as `filter { a(it) && b(it) }`.

### Assumptions

- The input satisfies the documented invariants (first depth is 0, each depth is at most the previous one plus 1) and node IDs are unique. `filter` doesn't validate them, since that would cost a pass on every call; the tests validate their own fixtures and every result.
- The predicate is a pure function of the node ID. Its call order follows the array order, and it is not called for nodes under a rejected ancestor.
- The result is a new, independent `ArrayBasedHierarchy`; it never shares arrays with the input.

## Part 2: `SimpleCache` review

The review is in [code-review/SimpleCache-review.md](code-review/SimpleCache-review.md). In short, the cache is not production-ready: it never removes expired entries and has no size bound (unbounded memory growth), offers no atomic get-or-load (a cache stampede on every hot-key expiry), and uses wall-clock time for TTL. There are also API, consistency, and testability issues. Each finding has its impact under the stated load and a fix.

The suggested replacement, [`ItemCache.kt`](code-review/src/main/kotlin/ItemCache.kt), is a thin wrapper over [Caffeine](https://github.com/ben-manes/caffeine). [`ItemCacheTest.kt`](code-review/src/test/kotlin/ItemCacheTest.kt) checks expiry, size bounding, single-flight loading under 32 concurrent threads, failed loads, invalidation and statistics, using a fake clock so the tests are fast and repeatable. The original code is kept verbatim in [`SimpleCache.kt`](code-review/src/main/kotlin/SimpleCache.kt) for reference.

### Assumptions

- Adding a well-established dependency (Caffeine) is acceptable. Each finding's fix is described so that it also applies to a hand-rolled implementation if it isn't.
- Cached values are immutable.
- The application runs as several replicas (Kubernetes), so staleness across pods is in scope for the review.
