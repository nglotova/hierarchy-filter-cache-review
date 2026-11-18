import kotlin.random.Random
import kotlin.test.*

class FilterTest {
  @Test
  fun testFilter() {
    val unfiltered: Hierarchy = ArrayBasedHierarchy(
      intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
      intArrayOf(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2))
    val filteredActual: Hierarchy = unfiltered.filter { nodeId -> nodeId % 3 != 0 }
    val filteredExpected: Hierarchy = ArrayBasedHierarchy(
      intArrayOf(1, 2, 5, 8, 10, 11),
      intArrayOf(0, 1, 1, 0, 1, 2))
    assertEquals(filteredExpected.formatString(), filteredActual.formatString())
  }

  // ---------------------------------------------------------------------------------------------
  // Edge cases
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `empty hierarchy yields empty result without invoking the predicate`() {
    val result = hierarchy().filter { fail("predicate must not be called for an empty hierarchy") }

    assertEquals(0, result.size)
  }

  @Test
  fun `single node is kept when it passes`() {
    assertFiltered(hierarchy(1 to 0), keep = { true }, expected = hierarchy(1 to 0))
  }

  @Test
  fun `single node is removed when it fails`() {
    assertFiltered(hierarchy(1 to 0), keep = { false }, expected = hierarchy())
  }

  @Test
  fun `all nodes passing yields an identical hierarchy`() {
    val input = exampleForest()

    assertFiltered(input, keep = { true }, expected = input)
  }

  @Test
  fun `no nodes passing yields an empty hierarchy`() {
    assertFiltered(exampleForest(), keep = { false }, expected = hierarchy())
  }

  // ---------------------------------------------------------------------------------------------
  // Subtree removal semantics
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `rejected leaf removes only that leaf`() {
    val input = hierarchy(
      1 to 0,
      2 to 1,
      3 to 1,
      4 to 1,
    )

    assertFiltered(input, keep = { it != 3 }, expected = hierarchy(1 to 0, 2 to 1, 4 to 1))
  }

  @Test
  fun `rejected inner node removes its whole subtree but keeps the following sibling`() {
    val input = hierarchy(
      1 to 0,
      2 to 1, // rejected
      3 to 2,
      4 to 3,
      5 to 2,
      6 to 1, // sibling of 2, must survive
      7 to 2,
    )

    assertFiltered(input, keep = { it != 2 }, expected = hierarchy(1 to 0, 6 to 1, 7 to 2))
  }

  @Test
  fun `rejected root removes only its own tree from the forest`() {
    val input = hierarchy(
      1 to 0,
      2 to 1,
      3 to 0, // rejected root
      4 to 1,
      5 to 2,
      6 to 0,
      7 to 1,
    )

    assertFiltered(input, keep = { it != 3 }, expected = hierarchy(1 to 0, 2 to 1, 6 to 0, 7 to 1))
  }

  @Test
  fun `depth dropping several levels at once ends the skipped subtree`() {
    val input = hierarchy(
      1 to 0,
      2 to 1,
      3 to 2, // rejected
      4 to 3,
      5 to 4,
      6 to 1, // jumps from depth 4 back to 1
      7 to 0, // and back to a new root
    )

    assertFiltered(input, keep = { it != 3 }, expected = hierarchy(1 to 0, 2 to 1, 6 to 1, 7 to 0))
  }

  @Test
  fun `consecutive rejected siblings are each skipped together with their subtrees`() {
    val input = hierarchy(
      1 to 0,
      2 to 1, // rejected
      3 to 2,
      4 to 1, // rejected
      5 to 2,
      6 to 1,
      7 to 2,
    )

    assertFiltered(input, keep = { it != 2 && it != 4 }, expected = hierarchy(1 to 0, 6 to 1, 7 to 2))
  }

  @Test
  fun `rejection nested inside a kept subtree only cuts the deeper part`() {
    val input = hierarchy(
      1 to 0,
      2 to 1,
      3 to 2,
      4 to 3, // rejected
      5 to 4,
      6 to 3,
      7 to 2,
    )

    assertFiltered(
      input,
      keep = { it != 4 },
      expected = hierarchy(1 to 0, 2 to 1, 3 to 2, 6 to 3, 7 to 2),
    )
  }

  @Test
  fun `passing descendants of a rejected node are removed`() {
    // Only node 1 fails; every descendant passes on its own but must still be removed.
    val input = hierarchy(1 to 0, 2 to 1, 3 to 2, 4 to 1, 5 to 0)

    assertFiltered(input, keep = { it != 1 }, expected = hierarchy(5 to 0))
  }

  // ---------------------------------------------------------------------------------------------
  // Predicate invocation contract
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `predicate is called once per reachable node and never for descendants of rejected nodes`() {
    val calls = mutableListOf<Int>()

    exampleForest().filter { nodeId ->
      calls += nodeId
      nodeId % 3 != 0
    }

    // 4 is a child of rejected 3; 7 is a child of rejected 6.
    assertEquals(listOf(1, 2, 3, 5, 6, 8, 9, 10, 11), calls)
  }

  // ---------------------------------------------------------------------------------------------
  // Structural properties
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `source hierarchy is not modified`() {
    val input = exampleForest()
    val before = input.formatString()

    input.filter { it % 2 == 0 }

    assertEquals(before, input.formatString())
  }

  @Test
  fun `works on non-array Hierarchy implementations`() {
    val input = object : Hierarchy {
      override val size = 4
      override fun nodeId(index: Int) = (index + 1) * 10
      override fun depth(index: Int) = index // a single chain 10 -> 20 -> 30 -> 40
    }

    assertFiltered(input, keep = { it != 30 }, expected = hierarchy(10 to 0, 20 to 1))
  }

  @Test
  fun `very deep chain is handled without recursion`() {
    val depth = 1_000_000
    val input = ArrayBasedHierarchy(IntArray(depth) { it }, IntArray(depth) { it })

    val result = input.filter { it != depth / 2 }

    assertEquals(depth / 2, result.size)
    assertEquals(depth / 2 - 1, result.nodeId(result.size - 1))
    assertEquals(depth / 2 - 1, result.depth(result.size - 1))
  }

  @Test
  fun `matches reference implementation on random forests`() {
    repeat(500) { seed ->
      val random = Random(seed)
      val input = randomForest(random, size = random.nextInt(0, 200))
      val rejected = input.nodeIds().filter { random.nextInt(4) == 0 }.toSet()
      val keep = { id: Int -> id !in rejected }

      val actual = input.filter(keep)

      assertEquals(referenceFilter(input, keep).formatString(), actual.formatString(), "seed=$seed")
      assertValidHierarchy(actual, "seed=$seed")
    }
  }

  @Test
  fun `filtering twice equals filtering once with the combined predicate`() {
    repeat(200) { seed ->
      val random = Random(seed)
      val input = randomForest(random, size = random.nextInt(0, 200))
      val a = { id: Int -> id % 3 != 0 }
      val b = { id: Int -> id % 5 != 0 }

      assertEquals(
        input.filter { a(it) && b(it) }.formatString(),
        input.filter(a).filter(b).formatString(),
        "seed=$seed",
      )
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** Builds a hierarchy from `nodeId to depth` pairs listed in DFS order. */
  private fun hierarchy(vararg nodes: Pair<Int, Int>): Hierarchy =
    ArrayBasedHierarchy(
      IntArray(nodes.size) { nodes[it].first },
      IntArray(nodes.size) { nodes[it].second },
    ).also { assertValidHierarchy(it, "test fixture") }

  /** The forest from the [Hierarchy] KDoc. */
  private fun exampleForest(): Hierarchy = ArrayBasedHierarchy(
    intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
    intArrayOf(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2),
  )

  private fun assertFiltered(input: Hierarchy, keep: (Int) -> Boolean, expected: Hierarchy) {
    val actual = input.filter(keep)
    assertEquals(expected.formatString(), actual.formatString())
    assertValidHierarchy(actual, "filter result")
  }

  private fun assertValidHierarchy(h: Hierarchy, context: String) {
    if (h.size == 0) return
    assertEquals(0, h.depth(0), "$context: first node must have depth 0")
    for (i in 1 until h.size) {
      val prev = h.depth(i - 1)
      val curr = h.depth(i)
      assertTrue(curr in 0..prev + 1, "$context: depth $curr at index $i after depth $prev")
    }
    assertEquals(h.size, h.nodeIds().toSet().size, "$context: node IDs must be unique")
  }

  private fun Hierarchy.nodeIds(): List<Int> = (0 until size).map(::nodeId)

  /**
   * Straightforward oracle: a node is visible iff it passes and its parent (the nearest preceding node
   * at depth - 1, tracked per depth level) is visible. Evaluates the predicate for every node.
   */
  private fun referenceFilter(h: Hierarchy, keep: (Int) -> Boolean): Hierarchy {
    val visibleAtDepth = BooleanArray(h.size + 1)
    val result = mutableListOf<Pair<Int, Int>>()
    for (i in 0 until h.size) {
      val d = h.depth(i)
      val visible = keep(h.nodeId(i)) && (d == 0 || visibleAtDepth[d - 1])
      visibleAtDepth[d] = visible
      if (visible) result += h.nodeId(i) to d
    }
    return hierarchy(*result.toTypedArray())
  }

  /** Random valid forest: each next depth is uniformly chosen from `0..previousDepth + 1`. */
  private fun randomForest(random: Random, size: Int): Hierarchy {
    val depths = IntArray(size)
    for (i in 1 until size) depths[i] = random.nextInt(0, depths[i - 1] + 2)
    return ArrayBasedHierarchy(IntArray(size) { it + 1 }, depths)
  }
}
