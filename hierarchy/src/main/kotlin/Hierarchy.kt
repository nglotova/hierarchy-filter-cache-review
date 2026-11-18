/**
 * A `Hierarchy` stores an arbitrary _forest_ (an ordered collection of ordered trees)
 * as an array of node IDs in the order of DFS traversal, combined with a parallel array of node depths.
 *
 * Parent-child relationships are identified by the position in the array and the associated depth.
 * Each tree root has depth 0, its children have depth 1 and follow it in the array, their children have depth 2 and follow them, etc.
 *
 * Example:
 * ```
 * nodeIds: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
 * depths:  0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2
 * ```
 *
 * the forest can be visualized as follows:
 * ```
 * 1
 * - 2
 * - - 3
 * - - - 4
 * - 5
 * 6
 * - 7
 * 8
 * - 9
 * - 10
 * - - 11
 *```
 * 1 is a parent of 2 and 5, 2 is a parent of 3, etc. Note that depth is equal to the number of hyphens for each node.
 *
 * Invariants on the depths array:
 *  * Depth of the first element is 0.
 *  * If the depth of a node is `D`, the depth of the next node in the array can be:
 *      * `D + 1` if the next node is a child of this node;
 *      * `D` if the next node is a sibling of this node;
 *      * `d < D` - in this case the next node is not related to this node.
 */
interface Hierarchy {
  /** The number of nodes in the hierarchy. */
  val size: Int

  /**
   * Returns the unique ID of the node identified by the hierarchy index. The depth for this node will be `depth(index)`.
   * @param index must be non-negative and less than [size]
   * */
  fun nodeId(index: Int): Int

  /**
   * Returns the depth of the node identified by the hierarchy index. The unique ID for this node will be `nodeId(index)`.
   * @param index must be non-negative and less than [size]
   * */
  fun depth(index: Int): Int

  fun formatString(): String {
    return (0 until size).joinToString(
      separator = ", ",
      prefix = "[",
      postfix = "]"
    ) { i -> "${nodeId(i)}:${depth(i)}" }
  }
}

/**
 * A node is present in the filtered hierarchy iff its node ID passes the predicate and all of its ancestors pass it as well.
 *
 * Implementation notes:
 *  * Single forward pass, O(n) time and O(n) extra space, no recursion (safe for arbitrarily deep trees).
 *  * In DFS order, the subtree of a node with depth `D` is exactly the contiguous run of nodes that follow it
 *    with depth `> D`. Once a node is rejected, everything after it is skipped until the depth drops back
 *    to `D` or lower.
 *  * A kept node has all of its ancestors kept, so its depth in the result equals its original depth,
 *    and the result satisfies the same depth invariants as the input.
 *  * The predicate is evaluated at most once per node and never for descendants of a rejected node.
 */
fun Hierarchy.filter(nodeIdPredicate: (Int) -> Boolean): Hierarchy {
  val n = size
  val nodeIds = IntArray(n)
  val depths = IntArray(n)
  var count = 0

  // Depth of the rejected node whose subtree is currently being skipped, or NOT_SKIPPING.
  var skipDeeperThan = NOT_SKIPPING

  for (i in 0 until n) {
    val depth = depth(i)
    if (depth > skipDeeperThan) continue // inside a rejected subtree

    val nodeId = nodeId(i)
    if (nodeIdPredicate(nodeId)) {
      nodeIds[count] = nodeId
      depths[count] = depth
      count++
      skipDeeperThan = NOT_SKIPPING
    } else {
      skipDeeperThan = depth
    }
  }

  return if (count == n) {
    ArrayBasedHierarchy(nodeIds, depths)
  } else {
    ArrayBasedHierarchy(nodeIds.copyOf(count), depths.copyOf(count))
  }
}

private const val NOT_SKIPPING = Int.MAX_VALUE

class ArrayBasedHierarchy(
  private val myNodeIds: IntArray,
  private val myDepths: IntArray,
) : Hierarchy {
  override val size: Int = myDepths.size

  override fun nodeId(index: Int): Int = myNodeIds[index]

  override fun depth(index: Int): Int = myDepths[index]
}
