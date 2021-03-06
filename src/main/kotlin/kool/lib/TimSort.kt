package kool.lib

import kool.BYTES
import kool.IntBuffer
import kool.cap
import kool.set
import org.lwjgl.system.MemoryUtil
import java.nio.IntBuffer

/** A stable, adaptive, iterative mergesort that requires far fewer than n lg(n) comparisons when running on partially
 *  sorted arrays, while offering performance comparable to a traditional mergesort when run on random arrays.
 *  Like all proper mergesorts, this sort is stable and runs O(n log n) time (worst case).  In the worst case, this sort
 *  requires temporary storage space for n/2 object references; in the best case, it requires only a small constant
 *  amount of space.
 *
 *  This implementation was adapted from Tim Peters's list sort for Python, which is described in detail here:
 *      http://svn.python.org/projects/python/trunk/Objects/listsort.txt
 *  Tim's C code may be found here:
 *      http://svn.python.org/projects/python/trunk/Objects/listobject.c
 *
 *  The underlying techniques are described in this paper (and may have even earlier origins):
 *
 *  "Optimistic Sorting and Information Theoretic Complexity", Peter McIlroy
 *  SODA (Fourth Annual ACM-SIAM Symposium on Discrete Algorithms), pp 467-474, Austin, Texas, 25-27 January 1993.
 *
 *  While the API to this class consists solely of static methods, it is (privately) instantiable; a TimSort instance
 *  holds the state of an ongoing sort, assuming the input array is large enough to warrant the full-blown TimSort.
 *  Small arrays are sorted in place, using a binary insertion sort.
 *
 * @author Josh Bloch
 */
object TimSort {
    /** This is the minimum sized sequence that will be merged.  Shorter sequences will be lengthened by calling
     *  binarySort.  If the entire array is less than this length, no merges will be performed.
     *
     *  This constant should be a power of two.  It was 64 in Tim Peter's C implementation, but 32 was empirically
     *  determined to work better in this implementation.  In the unlikely event that you set this constant to be a
     *  number that's not a power of two, you'll need to change the {@link #minRunLength} computation.
     *
     *  If you decrease this constant, you must change the stackLen computation in the TimSort constructor, or you risk
     *  an ArrayOutOfBounds exception.  See listsort.txt for a discussion of the minimum stack length required as a
     *  function of the length of the array being sorted and the minimum merge sequence length. */
    private val MIN_MERGE = 32

    /** The array being sorted. */
    private lateinit var a: IntBuffer

    /** The comparator for this sort.   */
    private lateinit var c: Comparator<Int>

    /** When we get into galloping mode, we stay there until both runs win less often than MIN_GALLOP consecutive times.    */
    private val MIN_GALLOP = 7

    /** This controls when we get *into* galloping mode.  It is initialized to MIN_GALLOP.  The mergeLo and mergeHi
     *  methods nudge it higher for random data, and lower for highly structured data.  */
    private var minGallop = MIN_GALLOP

    /** Maximum initial size of tmp array, which is used for merging.  The array can grow to accommodate demand.
     *
     *  Unlike Tim's original C version, we do not allocate this much storage when sorting smaller arrays.
     *  This change was required for performance.   */
    private val INITIAL_TMP_STORAGE_LENGTH = 256

    /** Temp storage for merges. A workspace array may optionally be provided in constructor, and if so will be used as
     *  long as it is big enough.   */
    private lateinit var tmp: IntBuffer
    private var tmpBase = 0 // base of tmp array slice
    private var tmpLen = 0  // length of tmp array slice

    /** A stack of pending runs yet to be merged.  Run i starts at address base[i] and extends for len[i] elements.
     *  It's always true (so long as the indices are in bounds) that:
     *     runBase[i] + runLen[i] == runBase[i + 1]
     *
     *  so we could cut the storage for this, but it's a minor amount, and keeping all the info explicit simplifies
     *  the code.   */
    private var stackSize = 0   // Number of pending runs on stack
    private lateinit var runBase: IntArray
    private lateinit var runLen: IntArray

    /** Setup the state of an ongoing sort.
     *
     * @param a the array to be sorted
     * @param c the comparator to determine the order of the sort
     * @param work a workspace array (slice)
     * @param workBase origin of usable space in work array
     * @param workLen usable size of work array
     */
    private fun setupTimSort(a: IntBuffer, c: Comparator<Int>, work: IntBuffer?, workBase: Int, workLen: Int) {

        this.a = a
        this.c = c

        // Allocate temp storage (which may be increased later if necessary)
        val len = a.cap
        val tlen = if (len < 2 * INITIAL_TMP_STORAGE_LENGTH) len ushr 1 else INITIAL_TMP_STORAGE_LENGTH
        if (work == null || workLen < tlen || workBase + tlen > work.cap) {
            tmp = IntBuffer(tlen)
            tmpBase = 0
            tmpLen = tlen
        } else {
            tmp = work
            tmpBase = workBase
            tmpLen = workLen
        }

        /*  Allocate runs-to-be-merged stack (which cannot be expanded).  The stack length requirements are described in
            listsort.txt.  The C version always uses the same stack length (85), but this was measured to be too
            expensive when sorting "mid-sized" arrays (e.g., 100 elements) in Java.  Therefore, we use smaller (but
            sufficiently large) stack lengths for smaller arrays.  The "magic numbers" in the computation below must be
            changed if MIN_MERGE is decreased.  See the MIN_MERGE declaration above for more information. The maximum
            value of 49 allows for an array up to length Integer.MAX_VALUE-4, if array is filled by the worst case stack
            size increasing scenario. More explanations are given in section 4 of:
            http://envisage-project.eu/wp-content/uploads/2015/02/sorting.pdf   */
        val stackLen = when {
            len < 120 -> 5
            len < 1542 -> 10
            len < 119151 -> 24
            else -> 49
        }
        runBase = IntArray(stackLen)
        runLen = IntArray(stackLen)
    }

    /*  The next method (package private and static) constitutes the entire API of this class.  */

    /** Sorts the given range, using the given workspace array slice for temp storage when possible. This method is
     *  designed to be invoked from public methods (in class Arrays) after performing any necessary array bounds checks
     *  and expanding parameters into the required forms.
     *
     * @param a the array to be sorted
     * @param lo_ the index of the first element, inclusive, to be sorted
     * @param hi the index of the last element, exclusive, to be sorted
     * @param c the comparator to use
     * @param work a workspace array (slice)
     * @param workBase origin of usable space in work array
     * @param workLen usable size of work array
     * @since 1.8   */
    fun sort(a: IntBuffer, lo_: Int, hi: Int, c: Comparator<Int>, work: IntBuffer?, workBase: Int, workLen: Int) {

        assert(lo_ in 0..hi && hi <= a.cap)

        var lo = lo_

        var nRemaining = hi - lo
        if (nRemaining < 2) return  // Arrays of size 0 and 1 are always sorted

        // If array is small, do a "mini-TimSort" with no merges
        if (nRemaining < MIN_MERGE) {
            val initRunLen = countRunAndMakeAscending(a, lo, hi, c)
            binarySort(a, lo, hi, lo + initRunLen, c)
            return
        }

        /** March over the array once, left to right, finding natural runs, extending short natural runs to minRun
         *  elements, and merging runs to maintain stack invariant. */
        setupTimSort(a, c, work, workBase, workLen)
        val minRun = minRunLength(nRemaining)
        do {
            // Identify next run
            var runLen = countRunAndMakeAscending(a, lo, hi, c)

            // If run is short, extend to min(minRun, nRemaining)
            if (runLen < minRun) {
                val force = if (nRemaining <= minRun) nRemaining else minRun
                binarySort(a, lo, lo + force, lo + runLen, c)
                runLen = force
            }

            // Push run onto pending-run stack, and maybe merge
            pushRun(lo, runLen)
            mergeCollapse()

            // Advance to find next run
            lo += runLen
            nRemaining -= runLen
        } while (nRemaining != 0)

        // Merge all remaining runs to complete sort
        assert(lo == hi)
        mergeForceCollapse()
        assert(stackSize == 1)
    }

    /** Sorts the specified portion of the specified array using a binary insertion sort.  This is the best method for
     *  sorting small numbers of elements.  It requires O(n log n) compares, but O(n^2) data movement (worst case).
     *
     *  If the initial part of the specified range is already sorted, this method can take advantage of it: the method
     *  assumes that the elements from index {@code lo}, inclusive, to {@code start}, exclusive are already sorted.
     *
     * @param a the array in which a range is to be sorted
     * @param lo the index of the first element in the range to be sorted
     * @param hi the index after the last element in the range to be sorted
     * @param start_ the index of the first element in the range that is not already known to be sorted ({@code lo <= start <= hi})
     * @param c comparator to used for the sort */
    private fun binarySort(a: IntBuffer, lo: Int, hi: Int, start_: Int, c: Comparator<Int>) {

        assert(start_ in lo..hi)
        var start = start_
        if (start == lo)
            start++
        while (start < hi) {
            val pivot = a[start]

            // Set left (and right) to the index where a[start] (pivot) belongs
            var left = lo
            var right = start
            assert(left <= right)
            /*  Invariants:
                pivot >= all in [lo, left).
                pivot <  all in [right, start). */
            while (left < right) {
                val mid = (left + right) ushr 1
                if (c.compare(pivot, a[mid]) < 0)
                    right = mid
                else
                    left = mid + 1
            }
            assert(left == right)

            /*  The invariants still hold: pivot >= all in [lo, left) and pivot < all in [left, start), so pivot belongs
                at left.  Note that if there are elements equal to pivot, left points to the first slot after them --
                that's why this sort is stable. Slide elements over to make room for pivot. */
            val n = start - left  // The number of elements to move
            // Switch is just an optimization for arraycopy in default case
            when (n) {
                2 -> {
                    a[left + 2] = a[left + 1]
                    a[left + 1] = a[left]
                }
                1 -> a[left + 1] = a[left]
                else -> MemoryUtil.memCopy(MemoryUtil.memAddress(a, left), MemoryUtil.memAddress(a, left + 1), n.toLong() * Int.BYTES)
            }
            a[left] = pivot
            start++
        }
    }

    /** Returns the length of the run beginning at the specified position in the specified array and reverses the run if
     *  it is descending (ensuring that the run will always be ascending when the method returns).
     *
     * A run is the longest ascending sequence with:
     *    a[lo] <= a[lo + 1] <= a[lo + 2] <= ...
     * or the longest descending sequence with:
     *    a[lo] >  a[lo + 1] >  a[lo + 2] >  ...
     *
     * For its intended use in a stable mergesort, the strictness of the definition of "descending" is needed so that
     * the call can safely reverse a descending sequence without violating stability.
     *
     * @param a the array in which a run is to be counted and possibly reversed
     * @param lo index of the first element in the run
     * @param hi index after the last element that may be contained in the run. It is required that {@code lo < hi}.
     * @param c the comparator to used for the sort
     * @return  the length of the run beginning at the specified position in the specified array */
    private fun countRunAndMakeAscending(a: IntBuffer, lo: Int, hi: Int, c: Comparator<Int>): Int {
        assert(lo < hi)
        var runHi = lo + 1
        if (runHi == hi)
            return 1

        // Find end of run, and reverse range if descending
        if (c.compare(a[runHi++], a[lo]) < 0) { // Descending
            while (runHi < hi && c.compare(a[runHi], a[runHi - 1]) < 0)
                runHi++
            reverseRange(a, lo, runHi)
        } else {                              // Ascending
            while (runHi < hi && c.compare(a[runHi], a[runHi - 1]) >= 0)
                runHi++
        }

        return runHi - lo
    }

    /** Reverse the specified range of the specified array.
     *
     * @param a the array in which a range is to be reversed
     * @param lo_ the index of the first element in the range to be reversed
     * @param hi_ the index after the last element in the range to be reversed
     */
    private fun reverseRange(a: IntBuffer, lo_: Int, hi_: Int) {
        var hi = hi_ - 1
        var lo = lo_
        while (lo < hi) {
            val t = a[lo]
            a[lo++] = a[hi]
            a[hi--] = t
        }
    }

    /**
     * Returns the minimum acceptable run length for an array of the specified
     * length. Natural runs shorter than this will be extended with
     * {@link #binarySort}.
     *
     * Roughly speaking, the computation is:
     *
     *  If n < MIN_MERGE, return n (it's too small to bother with fancy stuff).
     *  Else if n is an exact power of 2, return MIN_MERGE/2.
     *  Else return an int k, MIN_MERGE/2 <= k <= MIN_MERGE, such that n/k
     *   is close to, but strictly less than, an exact power of 2.
     *
     * For the rationale, see listsort.txt.
     *
     * @param n_ the length of the array to be sorted
     * @return the length of the minimum run to be merged
     */
    private fun minRunLength(n_: Int): Int {
        assert(n_ >= 0)
        var r = 0      // Becomes 1 if any 1 bits are shifted off
        var n = n_
        while (n >= MIN_MERGE) {
            r = r or (n and 1)
            n = n shr 1
        }
        return n + r
    }

    /** Pushes the specified run onto the pending-run stack.
     *
     * @param runBase index of the first element in the run
     * @param runLen  the number of elements in the run */
    private fun pushRun(runBase: Int, runLen: Int) {
        this.runBase[stackSize] = runBase
        this.runLen[stackSize] = runLen
        stackSize++
    }

    /** Examines the stack of runs waiting to be merged and merges adjacent runs until the stack invariants are
     *  reestablished:
     *     1. runLen[i - 3] > runLen[i - 2] + runLen[i - 1]
     *     2. runLen[i - 2] > runLen[i - 1]
     *  This method is called each time a new run is pushed onto the stack, so the invariants are guaranteed to hold for
     *  i < stackSize upon entry to the method. */
    private fun mergeCollapse() {
        while (stackSize > 1) {
            var n = stackSize - 2
            if (n > 0 && runLen[n - 1] <= runLen[n] + runLen[n + 1]) {
                if (runLen[n - 1] < runLen[n + 1])
                    n--
                mergeAt(n)
            } else if (runLen[n] <= runLen[n + 1]) {
                mergeAt(n)
            } else {
                break // Invariant is established
            }
        }
    }

    /** Merges all runs on the stack until only one remains.  This method is called once, to complete the sort. */
    private fun mergeForceCollapse() {
        while (stackSize > 1) {
            var n = stackSize - 2
            if (n > 0 && runLen[n - 1] < runLen[n + 1])
                n--
            mergeAt(n)
        }
    }

    /** Merges the two runs at stack indices i and i+1.  Run i must be the penultimate or antepenultimate run on the
     *  stack.  In other words, i must be equal to stackSize-2 or stackSize-3.
     *
     * @param i stack index of the first of the two runs to merge   */
    private fun mergeAt(i: Int) {
        assert(stackSize >= 2)
        assert(i >= 0)
        assert(i == stackSize - 2 || i == stackSize - 3)

        var base1 = runBase[i]
        var len1 = runLen[i]
        val base2 = runBase[i + 1]
        var len2 = runLen[i + 1]
        assert(len1 > 0 && len2 > 0)
        assert(base1 + len1 == base2)

        /*  Record the length of the combined runs; if i is the 3rd-last run now, also slide over the last run (which
            isn't involved in this merge).  The current run (i+1) goes away in any case.    */
        runLen[i] = len1 + len2
        if (i == stackSize - 3) {
            runBase[i + 1] = runBase[i + 2]
            runLen[i + 1] = runLen[i + 2]
        }
        stackSize--

        /*  Find where the first element of run2 goes in run1. Prior elements in run1 can be ignored (because they're
            already in place).  */
        val k = gallopRight(a[base2], a, base1, len1, 0, c)
        assert(k >= 0)
        base1 += k
        len1 -= k
        if (len1 == 0)
            return

        /* Find where the last element of run1 goes in run2. Subsequent elements in run2 can be ignored (because they're
            already in place).  */
        len2 = gallopLeft(a[base1 + len1 - 1], a, base2, len2, len2 - 1, c)
        assert(len2 >= 0)
        if (len2 == 0)
            return

        // Merge remaining runs, using tmp array with min(len1, len2) elements
        if (len1 <= len2)
            mergeLo(base1, len1, base2, len2)
        else
            mergeHi(base1, len1, base2, len2)
    }

    /** Locates the position at which to insert the specified key into the specified sorted range; if the range contains
     *  an element equal to key, returns the index of the leftmost equal element.
     *
     * @param key the key whose insertion point to search for
     * @param a the array in which to search
     * @param base the index of the first element in the range
     * @param len the length of the range; must be > 0
     * @param hint the index at which to begin the search, 0 <= hint < n.
     *      The closer hint is to the result, the faster this method will run.
     * @param c the comparator used to order the range, and to search
     * @return the int k,  0 <= k <= n such that a[b + k - 1] < key <= a[b + k], pretending that a[b - 1] is minus
     *      infinity and a[b + n] is infinity.
     *      In other words, key belongs at index b + k; or in other words, the first k elements of a should precede key,
     *      and the last n - k should follow it.    */
    private fun gallopLeft(key: Int, a: IntBuffer, base: Int, len: Int, hint: Int, c: Comparator<Int>): Int {
        assert(len > 0 && hint >= 0 && hint < len)
        var lastOfs = 0
        var ofs = 1
        if (c.compare(key, a[base + hint]) > 0) {
            // Gallop right until a[base+hint+lastOfs] < key <= a[base+hint+ofs]
            val maxOfs = len - hint
            while (ofs < maxOfs && c.compare(key, a[base + hint + ofs]) > 0) {
                lastOfs = ofs
                ofs = (ofs shl 1) + 1
                if (ofs <= 0)   // int overflow
                    ofs = maxOfs
            }
            if (ofs > maxOfs)
                ofs = maxOfs

            // Make offsets relative to base
            lastOfs += hint
            ofs += hint
        } else { // key <= a[base + hint]
            // Gallop left until a[base+hint-ofs] < key <= a[base+hint-lastOfs]
            val maxOfs = hint + 1
            while (ofs < maxOfs && c.compare(key, a[base + hint - ofs]) <= 0) {
                lastOfs = ofs
                ofs = (ofs shl 1) + 1
                if (ofs <= 0)   // int overflow
                    ofs = maxOfs
            }
            if (ofs > maxOfs)
                ofs = maxOfs

            // Make offsets relative to base
            val tmp = lastOfs
            lastOfs = hint - ofs
            ofs = hint - tmp
        }
        assert(-1 <= lastOfs && lastOfs < ofs && ofs <= len)

        /*  Now a[base+lastOfs] < key <= a[base+ofs], so key belongs somewhere to the right of lastOfs but no farther
            right than ofs.  Do a binary search, with invariant a[base + lastOfs - 1] < key <= a[base + ofs].   */
        lastOfs++
        while (lastOfs < ofs) {
            val m = lastOfs + ((ofs - lastOfs) ushr 1)

            if (c.compare(key, a[base + m]) > 0)
                lastOfs = m + 1  // a[base + m] < key
            else
                ofs = m          // key <= a[base + m]
        }
        assert(lastOfs == ofs)    // so a[base + ofs - 1] < key <= a[base + ofs]
        return ofs
    }

    /** Like gallopLeft, except that if the range contains an element equal to key, gallopRight returns the index after
     *  the rightmost equal element.
     *
     * @param key the key whose insertion point to search for
     * @param a the array in which to search
     * @param base the index of the first element in the range
     * @param len the length of the range; must be > 0
     * @param hint the index at which to begin the search, 0 <= hint < n. The closer hint is to the result, the faster
     *              this method will run.
     * @param c the comparator used to order the range, and to search
     * @return the int k,  0 <= k <= n such that a[b + k - 1] <= key < a[b + k] */
    private fun gallopRight(key: Int, a: IntBuffer, base: Int, len: Int, hint: Int, c: Comparator<Int>): Int {
        assert(len > 0 && hint >= 0 && hint < len)

        var ofs = 1
        var lastOfs = 0
        if (c.compare(key, a[base + hint]) < 0) {
            // Gallop left until a[b+hint - ofs] <= key < a[b+hint - lastOfs]
            val maxOfs = hint + 1
            while (ofs < maxOfs && c.compare(key, a[base + hint - ofs]) < 0) {
                lastOfs = ofs
                ofs = (ofs shl 1) + 1
                if (ofs <= 0)   // int overflow
                    ofs = maxOfs
            }
            if (ofs > maxOfs)
                ofs = maxOfs

            // Make offsets relative to b
            val tmp = lastOfs
            lastOfs = hint - ofs
            ofs = hint - tmp
        } else { // a[b + hint] <= key
            // Gallop right until a[b+hint + lastOfs] <= key < a[b+hint + ofs]
            val maxOfs = len - hint
            while (ofs < maxOfs && c.compare(key, a[base + hint + ofs]) >= 0) {
                lastOfs = ofs
                ofs = (ofs shl 1) + 1
                if (ofs <= 0)   // int overflow
                    ofs = maxOfs
            }
            if (ofs > maxOfs)
                ofs = maxOfs

            // Make offsets relative to b
            lastOfs += hint
            ofs += hint
        }
        assert(-1 <= lastOfs && lastOfs < ofs && ofs <= len)

        /*
         * Now a[b + lastOfs] <= key < a[b + ofs], so key belongs somewhere to
         * the right of lastOfs but no farther right than ofs.  Do a binary
         * search, with invariant a[b + lastOfs - 1] <= key < a[b + ofs].
         */
        lastOfs++
        while (lastOfs < ofs) {
            val m = lastOfs + ((ofs - lastOfs) ushr 1)

            if (c.compare(key, a[base + m]) < 0)
                ofs = m          // key < a[b + m]
            else
                lastOfs = m + 1  // a[b + m] <= key
        }
        assert(lastOfs == ofs) // so a[b + ofs - 1] <= key < a[b + ofs]
        return ofs
    }

    /** Merges two adjacent runs in place, in a stable fashion.  The first element of the first run must be greater
     *  than the first element of the second run (a[base1] > a[base2]), and the last element of the first run
     *  (a[base1 + len1-1]) must be greater than all elements of the second run.
     *
     *  For performance, this method should be called only when len1 <= len2; its twin, mergeHi should be called if
     *  len1 >= len2.  (Either method may be called if len1 == len2.)
     *
     * @param base1 index of first element in first run to be merged
     * @param len1_  length of first run to be merged (must be > 0)
     * @param base2 index of first element in second run to be merged (must be aBase + aLen)
     * @param len2_  length of second run to be merged (must be > 0)
     */
    private fun mergeLo(base1: Int, len1_: Int, base2: Int, len2_: Int) {
        assert(len1_ > 0 && len2_ > 0 && base1 + len1_ == base2)

        var len1 = len1_
        var len2 = len2_

        // Copy first run into temp array
        val a = this.a // For performance
        val tmp = ensureCapacity(len1)
        var cursor1 = tmpBase // Indexes into tmp array
        var cursor2 = base2   // Indexes int a
        var dest = base1      // Indexes int a
        MemoryUtil.memCopy(MemoryUtil.memAddress(a, base1), MemoryUtil.memAddress(tmp, cursor1), len1.toLong() * Int.BYTES)

        // Move first element of second run and deal with degenerate cases
        a[dest++] = a[cursor2++]
        if (--len2 == 0) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(tmp, cursor1), MemoryUtil.memAddress(a, dest), len1.toLong() * Int.BYTES)
            return
        }
        if (len1 == 1) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(a, cursor2), MemoryUtil.memAddress(a, dest), len2.toLong() * Int.BYTES)
            a[dest + len2] = tmp[cursor1] // Last elt of run 1 to end of merge
            return
        }

        val c = this.c  // Use local variable for performance
        var minGallop = this.minGallop    //  "    "       "     "      "
        outer@ while (true) {
            var count1 = 0 // Number of times in a row that first run won
            var count2 = 0 // Number of times in a row that second run won

            /* Do the straightforward thing until (if ever) one run starts winning consistently. */
            do {
                assert(len1 > 1 && len2 > 0)
                if (c.compare(a[cursor2], tmp[cursor1]) < 0) {
                    a[dest++] = a[cursor2++]
                    count2++
                    count1 = 0
                    if (--len2 == 0)
                        break@outer
                } else {
                    a[dest++] = tmp[cursor1++]
                    count1++
                    count2 = 0
                    if (--len1 == 1)
                        break@outer
                }
            } while ((count1 or count2) < minGallop)

            /*  One run is winning so consistently that galloping may be a huge win. So try that, and continue galloping
                until (if ever) neither run appears to be winning consistently anymore. */
            do {
                assert(len1 > 1 && len2 > 0)
                count1 = gallopRight(a[cursor2], tmp, cursor1, len1, 0, c)
                if (count1 != 0) {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(tmp, cursor1), MemoryUtil.memAddress(a, dest), count1.toLong() * Int.BYTES)
                    dest += count1
                    cursor1 += count1
                    len1 -= count1
                    if (len1 <= 1) // len1 == 1 || len1 == 0
                        break@outer
                }
                a[dest++] = a[cursor2++]
                if (--len2 == 0)
                    break@outer

                count2 = gallopLeft(tmp[cursor1], a, cursor2, len2, 0, c)
                if (count2 != 0) {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(a, cursor2), MemoryUtil.memAddress(a, dest), count2.toLong() * Int.BYTES)
                    dest += count2
                    cursor2 += count2
                    len2 -= count2
                    if (len2 == 0)
                        break@outer
                }
                a[dest++] = tmp[cursor1++]
                if (--len1 == 1)
                    break@outer
                minGallop--
            } while ((count1 >= MIN_GALLOP) or (count2 >= MIN_GALLOP))
            if (minGallop < 0)
                minGallop = 0
            minGallop += 2  // Penalize for leaving gallop mode
        }  // End of "outer" loop
        this.minGallop = if (minGallop < 1) 1 else minGallop  // Write back to field

        when (len1) {
            1 -> {
                assert(len2 > 0)
                MemoryUtil.memCopy(MemoryUtil.memAddress(a, cursor2), MemoryUtil.memAddress(a, dest), len2.toLong() * Int.BYTES)
                a[dest + len2] = tmp[cursor1] //  Last elt of run 1 to end of merge
            }
            0 -> throw IllegalArgumentException("Comparison method violates its general contract!")
            else -> {
                assert(len2 == 0)
                assert(len1 > 1)
                MemoryUtil.memCopy(MemoryUtil.memAddress(tmp, cursor1), MemoryUtil.memAddress(a, dest), len1.toLong() * Int.BYTES)
            }
        }
    }

    /** Like mergeLo, except that this method should be called only if len1 >= len2; mergeLo should be called if
     *  len1 <= len2.  (Either method may be called if len1 == len2.)
     *
     * @param base1 index of first element in first run to be merged
     * @param len1_  length of first run to be merged (must be > 0)
     * @param base2 index of first element in second run to be merged (must be aBase + aLen)
     * @param len2_  length of second run to be merged (must be > 0)
     */
    private fun mergeHi(base1: Int, len1_: Int, base2: Int, len2_: Int) {
        assert(len1_ > 0 && len2_ > 0 && base1 + len1_ == base2)

        var len1 = len1_
        var len2 = len2_

        // Copy second run into temp array
        val a = this.a // For performance
        val tmp = ensureCapacity(len2)
        val tmpBase = this.tmpBase
        MemoryUtil.memCopy(MemoryUtil.memAddress(a, base2), MemoryUtil.memAddress(tmp, tmpBase), len2.toLong() * Int.BYTES)

        var cursor1 = base1 + len1 - 1  // Indexes into a
        var cursor2 = tmpBase + len2 - 1 // Indexes into tmp array
        var dest = base2 + len2 - 1     // Indexes into a

        // Move last element of first run and deal with degenerate cases
        a[dest--] = a[cursor1--]
        if (--len1 == 0) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(tmp, tmpBase), MemoryUtil.memAddress(a, dest - (len2 - 1)), len2.toLong() * Int.BYTES)
            return
        }
        if (len2 == 1) {
            dest -= len1
            cursor1 -= len1
            MemoryUtil.memCopy(MemoryUtil.memAddress(a, cursor1 + 1), MemoryUtil.memAddress(a, dest + 1), len1.toLong() * Int.BYTES)
            a[dest] = tmp[cursor2]
            return
        }

        val c = this.c  // Use local variable for performance
        var minGallop = this.minGallop    //  "    "       "     "      "
        outer@ while (true) {
            var count1 = 0 // Number of times in a row that first run won
            var count2 = 0 // Number of times in a row that second run won

            /*  Do the straightforward thing until (if ever) one run appears to win consistently.   */
            do {
                assert(len1 > 0 && len2 > 1)
                if (c.compare(tmp[cursor2], a[cursor1]) < 0) {
                    a[dest--] = a[cursor1--]
                    count1++
                    count2 = 0
                    if (--len1 == 0)
                        break@outer
                } else {
                    a[dest--] = tmp[cursor2--]
                    count2++
                    count1 = 0
                    if (--len2 == 1)
                        break@outer
                }
            } while ((count1 or count2) < minGallop)

            /*  One run is winning so consistently that galloping may be a huge win. So try that, and continue galloping
                until (if ever) neither run appears to be winning consistently anymore. */
            do {
                assert(len1 > 0 && len2 > 1)
                count1 = len1 - gallopRight(tmp[cursor2], a, base1, len1, len1 - 1, c)
                if (count1 != 0) {
                    dest -= count1
                    cursor1 -= count1
                    len1 -= count1
                    MemoryUtil.memCopy(MemoryUtil.memAddress(a, cursor1 + 1), MemoryUtil.memAddress(a, dest + 1), count1.toLong() * Int.BYTES)
                    if (len1 == 0)
                        break@outer
                }
                a[dest--] = tmp[cursor2--]
                if (--len2 == 1)
                    break@outer

                count2 = len2 - gallopLeft(a[cursor1], tmp, tmpBase, len2, len2 - 1, c)
                if (count2 != 0) {
                    dest -= count2
                    cursor2 -= count2
                    len2 -= count2
                    MemoryUtil.memCopy(MemoryUtil.memAddress(tmp, cursor2 + 1), MemoryUtil.memAddress(a, dest + 1), count2.toLong() * Int.BYTES)
                    if (len2 <= 1)  // len2 == 1 || len2 == 0
                        break@outer
                }
                a[dest--] = a[cursor1--]
                if (--len1 == 0)
                    break@outer
                minGallop--
            } while ((count1 >= MIN_GALLOP) or (count2 >= MIN_GALLOP))
            if (minGallop < 0)
                minGallop = 0
            minGallop += 2  // Penalize for leaving gallop mode
        }  // End of "outer" loop
        this.minGallop = if (minGallop < 1) 1 else minGallop  // Write back to field

        if (len2 == 1) {
            assert(len1 > 0)
            dest -= len1
            cursor1 -= len1
            MemoryUtil.memCopy(MemoryUtil.memAddress(a, cursor1 + 1), MemoryUtil.memAddress(a, dest + 1), len1.toLong() * Int.BYTES)
            a[dest] = tmp[cursor2]  // Move first elt of run2 to front of merge
        } else if (len2 == 0) {
            throw IllegalArgumentException("Comparison method violates its general contract!")
        } else {
            assert(len1 == 0)
            assert(len2 > 0)
            MemoryUtil.memCopy(MemoryUtil.memAddress(tmp, tmpBase), MemoryUtil.memAddress(a, dest - (len2 - 1)), len2.toLong() * Int.BYTES)
        }
    }

    /** Ensures that the external array tmp has at least the specified number of elements, increasing its size if
     *  necessary.  The size increases exponentially to ensure amortized linear time complexity.
     *
     * @param minCapacity the minimum required capacity of the tmp array
     * @return tmp, whether or not it grew  */
    private fun ensureCapacity(minCapacity: Int): IntBuffer {
        if (tmpLen < minCapacity) {
            // Compute smallest power of 2 > minCapacity
            var newSize = minCapacity
            newSize = newSize or (newSize shr 1)
            newSize = newSize or (newSize shr 2)
            newSize = newSize or (newSize shr 4)
            newSize = newSize or (newSize shr 8)
            newSize = newSize or (newSize shr 16)
            newSize++

            if (newSize < 0) // Not bloody likely!
                newSize = minCapacity
            else
                newSize = Math.min(newSize, a.cap ushr 1)

            val newArray = MemoryUtil.memRealloc(a, newSize)
            tmp = newArray
            tmpLen = newSize
            tmpBase = 0
        }
        return tmp
    }
}