package com.chigichan24.messagequeuebully

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.MessageQueue
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "MQBully"

class MainActivity : Activity() {

    private lateinit var output: TextView
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val parkedRunnable = Runnable { /* never executed within test horizon */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: opt in explicitly and inset the root view ourselves.
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(
                    32 + bars.left,
                    32 + bars.top,
                    32 + bars.right,
                    32 + bars.bottom,
                )
                WindowInsets.CONSUMED
            }
        }

        output = TextView(this).apply {
            textSize = 13f
            text = "Auto-running attacks. See: adb logcat -s MQBully"
        }

        val buttons = listOf(
            "Attack 1: Reflect mMessages" to ::runAttack1,
            "Attack 2: External synchronized(queue)" to ::runAttack2,
            "Attack 3: Walk Message chain (.next)" to ::runAttack3,
            "Attack 4: Force-set mMessages = null" to ::runAttack4,
            "Attack 5: Reflective MessageQueue.next() from BG" to ::runAttack5,
            "Attack 6: same-when dispatch order (public API)" to ::runAttack6,
            "Attack 7: bulk cleanup latency (public API)" to ::runAttack7,
            "Attack 8: postAtFrontOfQueue order (public API)" to ::runAttack8,
            "Attack 9: N-producer post() throughput (public API)" to ::runAttack9,
            "Attack 10: fairness + latency variance (public API)" to ::runAttack10,
            "Attack 11: deliberate tail spike (public API)" to ::runAttack11,
            "Attack 12: tombstone memory hold (public API)" to ::runAttack12,
            "Attack 13: single-producer regression (public API)" to ::runAttack13,
            "Attack 14: tombstone accumulation → OOM probe (public API)" to ::runAttack14,
        )
        buttons.forEach { (label, fn) ->
            val b = Button(this).apply {
                text = label
                setOnClickListener { fn() }
            }
            root.addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val scroll = ScrollView(this).apply {
            addView(output, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, 1f).apply {
            gravity = Gravity.FILL
            topMargin = 16
        })

        setContentView(root)
        root.requestApplyInsets()

        // Light background → dark system-bar icons so the clock/battery stay visible.
        // Must be after setContentView so the DecorView (and its InsetsController) exists.
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        // Launch with `am start --es attack N` to auto-run attack N once.
        // Without the extra, the app idles and you tap buttons manually.
        when (intent?.getStringExtra("attack")) {
            "1" -> handler.postDelayed({ runAttack1() }, 200L)
            "2" -> handler.postDelayed({ runAttack2() }, 200L)
            "3" -> handler.postDelayed({ runAttack3() }, 200L)
            "4" -> handler.postDelayed({ runAttack4() }, 200L)
            "5" -> handler.postDelayed({ runAttack5() }, 200L)
            "6" -> handler.postDelayed({ runAttack6() }, 200L)
            "7" -> handler.postDelayed({ runAttack7() }, 200L)
            "8" -> handler.postDelayed({ runAttack8() }, 200L)
            "9" -> handler.postDelayed({ runAttack9() }, 200L)
            "10" -> handler.postDelayed({ runAttack10() }, 200L)
            "11" -> handler.postDelayed({ runAttack11() }, 200L)
            "12" -> handler.postDelayed({ runAttack12() }, 200L)
            "13" -> handler.postDelayed({ runAttack13() }, 200L)
            "14" -> handler.postDelayed({ runAttack14() }, 200L)
        }
    }

    // ----- Attack 1 -----

    private fun runAttack1() {
        handler.removeCallbacks(parkedRunnable)
        handler.postDelayed(parkedRunnable, 100_000L)

        val queue: MessageQueue = Looper.myQueue()
        val report = StringBuilder("=== Attack 1: Reflect MessageQueue.mMessages ===\n")
        try {
            val field = MessageQueue::class.java.getDeclaredField("mMessages")
            field.isAccessible = true
            val value = field.get(queue)
            val rendered = if (value == null) {
                "mMessages = null  → NEW lock-free impl OR queue empty."
            } else {
                "mMessages = ${value::class.java.name}@${System.identityHashCode(value).toString(16)}" +
                    "  → legacy linked-list head exposed."
            }
            report.appendLine(rendered)
            Log.i(TAG, "[attack1] $rendered")
        } catch (t: Throwable) {
            val err = "ERROR ${t.javaClass.simpleName}: ${t.message}"
            report.appendLine(err)
            Log.w(TAG, "[attack1] $err", t)
        }
        appendOutput(report.toString())
    }

    // ----- Attack 2 -----

    private fun runAttack2() {
        val queue = Looper.getMainLooper().queue
        val holdMs = 3_000L
        Log.i(TAG, "[attack2] BG-A will hold synchronized(queue) for ${holdMs}ms")

        Thread {
            synchronized(queue) {
                Log.i(TAG, "[attack2] BG-A acquired queue monitor")
                try { Thread.sleep(holdMs) } catch (_: InterruptedException) {}
                Log.i(TAG, "[attack2] BG-A releasing queue monitor")
            }
        }.start()

        Thread {
            try { Thread.sleep(200L) } catch (_: InterruptedException) {}
            val postStartNs = System.nanoTime()
            Handler(Looper.getMainLooper()).post {
                val dispatchEndNs = System.nanoTime()
                val ms = (dispatchEndNs - postStartNs) / 1_000_000
                val msg = "[attack2] post→dispatch latency = ${ms}ms " +
                    "(legacy ≈ ${holdMs - 200}ms; lock-free ≈ 0ms)"
                Log.i(TAG, msg)
                appendOutput("=== Attack 2: external synchronized(queue) ===\n$msg")
            }
            val postReturnedNs = System.nanoTime()
            val postCallMs = (postReturnedNs - postStartNs) / 1_000_000
            Log.i(TAG, "[attack2] post() returned after ${postCallMs}ms from BG-B")
        }.start()
    }

    // ----- Attack 3 -----

    private fun runAttack3() {
        handler.postDelayed(parkedRunnable, 110_000L)
        handler.postDelayed(parkedRunnable, 210_000L)
        handler.postDelayed(parkedRunnable, 310_000L)

        val queue = Looper.myQueue()
        val report = StringBuilder("=== Attack 3: Walk Message chain via reflection ===\n")
        try {
            val mMessagesField = MessageQueue::class.java.getDeclaredField("mMessages").also { it.isAccessible = true }
            val nextField = Message::class.java.getDeclaredField("next").also { it.isAccessible = true }
            val whenField = Message::class.java.getDeclaredField("when").also { it.isAccessible = true }
            val now = SystemClock.uptimeMillis()

            var current = mMessagesField.get(queue) as? Message
            var count = 0
            val details = StringBuilder()
            while (current != null && count < 16) {
                val whenMs = whenField.getLong(current)
                details.appendLine("  #${count}: deliver in ${whenMs - now}ms")
                current = nextField.get(current) as? Message
                count++
            }
            val summary = "Walked $count pending messages from mMessages head."
            report.appendLine(summary)
            if (count > 0) report.append(details)
            Log.i(TAG, "[attack3] $summary")
        } catch (t: Throwable) {
            val err = "ERROR ${t.javaClass.simpleName}: ${t.message}"
            report.appendLine(err)
            Log.w(TAG, "[attack3] $err", t)
        }
        appendOutput(report.toString())
    }

    // ----- Attack 4 -----

    /**
     * Force-set MessageQueue.mMessages to null and observe how many already-enqueued
     * victims still get delivered.
     *
     *  - legacy impl: orphans the linked list head; subsequent victims may be lost or
     *    the next() pump may decide there's nothing to do until something else pokes it.
     *  - lock-free impl: mMessages is already null and is not consulted by the pump;
     *    the setter is effectively a no-op; all victims fire normally.
     */
    private fun runAttack4() {
        val queue = Looper.myQueue()
        val fired = AtomicInteger(0)
        val victimCount = 5

        repeat(victimCount) { i ->
            handler.postDelayed({
                fired.incrementAndGet()
                Log.i(TAG, "[attack4] victim $i fired (cumulative=${fired.get()})")
            }, 400L + i * 100L)
        }

        // Sabotage 50ms later so the victims are surely enqueued first.
        handler.postDelayed({
            try {
                val field = MessageQueue::class.java.getDeclaredField("mMessages").also { it.isAccessible = true }
                val before = field.get(queue)
                field.set(queue, null)
                val after = field.get(queue)
                val msg = "[attack4] before set=${if (before == null) "null" else before.javaClass.simpleName}" +
                    ", after set=${if (after == null) "null" else after.javaClass.simpleName}"
                Log.i(TAG, msg)
            } catch (t: Throwable) {
                Log.w(TAG, "[attack4] sabotage failed: ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }, 50L)

        // Check victim count well after all should have fired (main-thread path).
        handler.postDelayed({
            val n = fired.get()
            val msg = "[attack4] (main) Victims fired: $n / $victimCount " +
                "(legacy: may lose some; lock-free: expects $victimCount)"
            Log.i(TAG, msg)
            appendOutput("=== Attack 4: Force-set mMessages = null ===\n$msg")
        }, 2_000L)

        // Backup reporter from a BG thread so we still see the result even if the
        // main Looper has died (which is exactly what legacy impl does after the set).
        Thread {
            try { Thread.sleep(2_500L) } catch (_: InterruptedException) {}
            val n = fired.get()
            Log.i(TAG, "[attack4] (BG-watchdog) Victims fired: $n / $victimCount " +
                "→ legacy: ~0 if main Looper is dead; lock-free: $victimCount")
        }.start()
    }

    // ----- Attack 5 -----

    /**
     * Call MessageQueue.next() reflectively from a background thread.
     *
     *  - legacy impl: next() takes synchronized(this) and waits for new messages; from a
     *    non-Looper thread it competes with the real Looper for messages — effectively a
     *    message thief or a deadlock candidate. We wrap with a 1-second timeout to
     *    survive the demo.
     *  - lock-free impl: the heap is exclusively owned by the Looper thread; calling
     *    next() from another thread is undefined behavior, may throw or corrupt state.
     */
    private fun runAttack5() {
        val report = StringBuilder("=== Attack 5: Reflective MessageQueue.next() from BG ===\n")
        val exec = Executors.newSingleThreadExecutor()
        val queue = Looper.getMainLooper().queue
        val future = exec.submit<String> {
            val nextMethod = MessageQueue::class.java.getDeclaredMethod("next").also { it.isAccessible = true }
            val startNs = System.nanoTime()
            try {
                val msg = nextMethod.invoke(queue)
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                "returned in ${elapsedMs}ms: ${msg ?: "null"}"
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
                "THREW ${cause.javaClass.simpleName}: ${cause.message}"
            }
        }
        val outcome = try {
            future.get(1, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            "BLOCKED > 1s (no return, no throw)"
        } catch (t: Throwable) {
            "future failed: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            exec.shutdownNow()
        }
        report.appendLine(outcome)
        Log.i(TAG, "[attack5] $outcome")
        appendOutput(report.toString())
    }

    // ----- Attack 6: same-when dispatch order -----

    /**
     * Post N runnables with **identical `when`** via `postAtTime(r, sameMs)`.
     * Each runnable records its index in the order it actually executes.
     *
     *  - legacy impl: linked list maintains insertion order → output is [0,1,2,...,N-1].
     *  - lock-free impl: messages flow Treiber stack → min-heap. Heap is keyed by
     *    deadline; with ties, sibling order is heap-internal and not guaranteed to
     *    match insertion order. Output may scramble.
     *
     *  Public API only: Handler.postAtTime / SystemClock.uptimeMillis.
     */
    private fun runAttack6() {
        val n = 50
        val deliverAt = SystemClock.uptimeMillis() + 300L
        val observed = java.util.Collections.synchronizedList(ArrayList<Int>(n))

        for (i in 0 until n) {
            handler.postAtTime({ observed.add(i) }, deliverAt)
        }

        handler.postAtTime({
            val expected = (0 until n).toList()
            val inOrder = observed == expected
            val firstDiff = (0 until n).firstOrNull { observed.getOrNull(it) != it }
            val msg = "[attack6] same-when post ($n items): inOrder=$inOrder" +
                (firstDiff?.let { " (first divergence at index $it: got ${observed.getOrNull(it)})" } ?: "") +
                " observed=${observed.take(20)}${if (n > 20) "..." else ""}"
            Log.i(TAG, msg)
            appendOutput("=== Attack 6: same-when dispatch order ===\n$msg")
        }, deliverAt + 1L)
    }

    // ----- Attack 7: bulk cleanup latency -----

    /**
     * Enqueue many never-firing messages, then time how long
     * `removeCallbacksAndMessages(null)` takes to drain them.
     *
     *  - legacy impl: walks a singly-linked list under synchronized(this); time grows
     *    linearly with N.
     *  - lock-free impl: messages live in a min-heap with back-pointers; cleanup uses
     *    different mechanics. Public Android 17 blog claims back-pointers enable
     *    O(1) per-removal once located.
     *
     *  Public API only: Handler.postDelayed / removeCallbacksAndMessages(null).
     */
    private fun runAttack7() {
        val n = 10_000
        val sentinel = Runnable { /* never fires within test horizon */ }

        // Warmup so JIT and Looper state are settled.
        for (i in 0 until 100) handler.postDelayed(sentinel, 60_000L + i)
        handler.removeCallbacksAndMessages(null)

        for (i in 0 until n) handler.postDelayed(sentinel, 1_000_000L + i)

        val startNs = System.nanoTime()
        handler.removeCallbacksAndMessages(null)
        val elapsedUs = (System.nanoTime() - startNs) / 1_000

        val msg = "[attack7] removeCallbacksAndMessages(null) on $n msgs took ${elapsedUs}µs " +
            "(${elapsedUs / 1000}ms)"
        Log.i(TAG, msg)
        appendOutput("=== Attack 7: bulk cleanup latency ($n msgs) ===\n$msg")
    }

    // ----- Attack 8: postAtFrontOfQueue ordering -----

    /**
     * Call `postAtFrontOfQueue(r)` repeatedly.
     *
     *  - legacy impl: each call inserts at the linked-list head, so the order they
     *    actually run in is the reverse of insertion (LIFO): 4,3,2,1,0.
     *  - lock-free impl: each call effectively posts with `when=SystemClock.uptimeMillis()`
     *    (or 0), so all 5 share a near-identical deadline. The heap chooses an order;
     *    LIFO is not guaranteed.
     *
     *  Public API only: Handler.postAtFrontOfQueue.
     */
    private fun runAttack8() {
        val n = 8
        val observed = java.util.Collections.synchronizedList(ArrayList<Int>(n))

        for (i in 0 until n) {
            val idx = i
            handler.postAtFrontOfQueue { observed.add(idx) }
        }

        handler.postDelayed({
            val legacyLifo = (n - 1 downTo 0).toList()
            val matchesLegacyLifo = observed == legacyLifo
            val msg = "[attack8] postAtFrontOfQueue x$n observed=$observed " +
                "matchesLegacyLIFO($legacyLifo)=$matchesLegacyLifo"
            Log.i(TAG, msg)
            appendOutput("=== Attack 8: postAtFrontOfQueue ordering ===\n$msg")
        }, 50L)
    }

    // ----- Attack 9: N-producer post() throughput -----

    /**
     * Spawn N background threads that each call `handler.post(noop)` M times,
     * starting at the same instant. Measure per-post overhead.
     *
     *  - legacy impl: every post() takes synchronized(this) on the queue. With N
     *    producers contending for one monitor, throughput is gated by serialization.
     *    Per-post cost rises with N.
     *  - lock-free impl: producers push onto the Treiber stack with a CAS loop. No
     *    mutual exclusion. Per-post cost stays flat (or even drops) as N grows.
     *
     *  Public API only: Handler.post / java.util.concurrent.*
     *  Reproduces the public claim of "up to 5,000x faster multi-threaded insertion".
     */
    private fun runAttack9() {
        val producers = 8
        val perProducer = 5_000
        val totalPosts = producers * perProducer

        Thread {
            val startGate = java.util.concurrent.CountDownLatch(1)
            val readyGate = java.util.concurrent.CountDownLatch(producers)
            val doneGate = java.util.concurrent.CountDownLatch(producers)
            val perThreadNs = LongArray(producers)
            val drained = java.util.concurrent.atomic.AtomicInteger(0)
            val noop = Runnable { drained.incrementAndGet() }
            val mainHandler = Handler(Looper.getMainLooper())

            for (tid in 0 until producers) {
                Thread {
                    readyGate.countDown()
                    startGate.await()
                    val start = System.nanoTime()
                    repeat(perProducer) {
                        mainHandler.post(noop)
                    }
                    perThreadNs[tid] = System.nanoTime() - start
                    doneGate.countDown()
                }.start()
            }

            readyGate.await()
            // All producers blocked at startGate.await(); release them in unison.
            val wallStartNs = System.nanoTime()
            startGate.countDown()
            doneGate.await(30, java.util.concurrent.TimeUnit.SECONDS)
            val wallElapsedNs = System.nanoTime() - wallStartNs

            val sumNs = perThreadNs.sum()
            val maxNs = perThreadNs.max()
            val minNs = perThreadNs.min()
            val avgNsPerPost = sumNs / totalPosts
            val wallNsPerPost = wallElapsedNs / totalPosts
            val throughputPerSec = (totalPosts.toLong() * 1_000_000_000L) / wallElapsedNs

            val msg = buildString {
                appendLine("=== Attack 9: N-producer post() throughput ===")
                appendLine("producers=$producers, posts/producer=$perProducer, totalPosts=$totalPosts")
                appendLine("wall: ${wallElapsedNs / 1_000_000}ms")
                appendLine("avg post() per call (per-producer view): ${avgNsPerPost}ns")
                appendLine("avg post() per call (wall view):         ${wallNsPerPost}ns")
                appendLine("per-thread elapsed: min=${minNs / 1_000_000}ms, max=${maxNs / 1_000_000}ms")
                appendLine("throughput: ${throughputPerSec} posts/sec (wall basis)")
                appendLine("(legacy: lock contention serializes producers; lock-free: CAS-only)")
            }
            Log.i(TAG, msg.replace("\n", " | "))
            appendOutput(msg)
        }.start()
    }

    // ----- Attack 10: fairness + latency variance -----

    /**
     * Classic worry about lock-free queues: with no mutual exclusion, fast producers
     * can starve slow ones, and CAS retries can produce per-call latency spikes.
     *
     * We post against a *dedicated* HandlerThread (not the main one) so that the
     * consumer's drain rate is constant across runs. N producers race for `duration`
     * milliseconds, each recording (a) how many posts they got through, and (b) the
     * per-call nanos histogram.
     *
     *  Compare:
     *   - fairness ratio = max(perThreadCount) / min(perThreadCount). 1.0 = perfect.
     *   - p999 latency  = worst-case post() call seen by any thread.
     *
     *  - legacy impl: synchronized(this) FIFO-ish queue serializes producers; we
     *    expect modest fairness ratio (e.g. <2x) and high p999 (lock wait).
     *  - lock-free impl: CAS push, no exclusion. Could exhibit unfairness as fast
     *    threads keep winning the CAS race, AND/OR latency spikes when a thread loses
     *    many retries.
     *
     *  Public API only: Handler.post / HandlerThread.
     */
    private fun runAttack10() {
        val producers = 6
        val durationMs = 1_500L

        Thread {
            val ht = HandlerThread("MQBully-Bench").apply { start() }
            val benchHandler = Handler(ht.looper)
            val noop = Runnable { /* drained by benchHandler.looper */ }

            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val perThreadCount = IntArray(producers)
            val perThreadLatencies = Array(producers) { LongArray(0) }
            val startGate = java.util.concurrent.CountDownLatch(1)
            val doneGate = java.util.concurrent.CountDownLatch(producers)

            for (tid in 0 until producers) {
                Thread {
                    val buf = LongArray(1_000_000) // generous; we'll trim
                    var local = 0
                    startGate.await()
                    while (running.get()) {
                        val t0 = System.nanoTime()
                        benchHandler.post(noop)
                        val t1 = System.nanoTime()
                        if (local < buf.size) buf[local] = t1 - t0
                        local++
                    }
                    perThreadCount[tid] = local
                    perThreadLatencies[tid] = buf.copyOf(minOf(local, buf.size))
                    doneGate.countDown()
                }.start()
            }

            // Synchronize start.
            Thread.sleep(50)
            startGate.countDown()
            Thread.sleep(durationMs)
            running.set(false)
            doneGate.await(5, java.util.concurrent.TimeUnit.SECONDS)
            ht.quitSafely()

            val totalCount = perThreadCount.sum()
            val maxCount = perThreadCount.max()
            val minCount = perThreadCount.min().coerceAtLeast(1)
            val fairnessRatio = maxCount.toDouble() / minCount

            val flat = perThreadLatencies.flatMap { it.toList() }.sorted()
            fun percentile(p: Double): Long {
                if (flat.isEmpty()) return -1
                val idx = ((flat.size - 1) * p).toInt().coerceIn(0, flat.size - 1)
                return flat[idx]
            }
            val p50 = percentile(0.50)
            val p99 = percentile(0.99)
            val p999 = percentile(0.999)
            val pMax = flat.lastOrNull() ?: -1L

            val msg = buildString {
                appendLine("=== Attack 10: fairness + latency variance ===")
                appendLine("producers=$producers, duration=${durationMs}ms, target=HandlerThread")
                appendLine("perThreadCount=${perThreadCount.toList()}")
                appendLine("totalCount=$totalCount  fairnessRatio=${"%.2f".format(fairnessRatio)}x")
                appendLine("post() latency p50=${p50}ns  p99=${p99}ns  p999=${p999}ns  max=${pMax}ns")
                appendLine("(legacy: monitor wait → high p99/p999; lock-free: CAS retries → potential spikes & unfair)")
            }
            Log.i(TAG, msg.replace("\n", " | "))
            appendOutput(msg)
        }.start()
    }

    // ----- Attack 11: deliberate tail spike -----

    /**
     * "Producer bursting": N noise threads + 1 victim, all gated on a barrier and
     * released together. Repeat for many rounds and report the worst per-call
     * latency the victim ever saw.
     *
     * This is intentionally the worst case for a CAS-based push: every burst, all
     * N+1 threads attempt CAS on the same head pointer in the same ~microsecond.
     * The victim wins the CAS once or loses many times in a row.
     *
     *  - legacy impl: synchronized(this) serializes producers FIFO-ish; the victim
     *    waits for the monitor but the wait time is bounded by other holders' very
     *    short critical sections.
     *  - lock-free impl: no exclusion, but on contended bursts the victim's CAS may
     *    keep failing as faster threads keep winning. The CAS retry loop has no
     *    fairness guarantee → tail latency spikes.
     *
     *  Public API only: Handler.post / java.util.concurrent.*
     *
     *  If this attack reliably shows victim_max much higher on lock-free than on
     *  legacy, we have isolated a real regression: "Android 17 made something worse,
     *  precisely here."
     */
    private fun runAttack11() {
        val noiseThreads = 32
        val rounds = 50
        val burstSize = 200  // each noise thread posts this many per round

        Thread {
            val ht = HandlerThread("MQBully-Burst").apply { start() }
            val benchHandler = Handler(ht.looper)
            val noop = Runnable { }

            val victimLatencies = LongArray(rounds)

            for (round in 0 until rounds) {
                val startGate = java.util.concurrent.CountDownLatch(1)
                val readyGate = java.util.concurrent.CountDownLatch(noiseThreads + 1)
                val doneGate = java.util.concurrent.CountDownLatch(noiseThreads + 1)
                val victimLatencyHolder = java.util.concurrent.atomic.AtomicLong(-1)

                // Noise threads.
                for (n in 0 until noiseThreads) {
                    Thread {
                        readyGate.countDown()
                        startGate.await()
                        repeat(burstSize) {
                            benchHandler.post(noop)
                        }
                        doneGate.countDown()
                    }.start()
                }
                // Victim thread (also fights the CAS).
                Thread {
                    readyGate.countDown()
                    startGate.await()
                    val t0 = System.nanoTime()
                    benchHandler.post(noop)
                    val t1 = System.nanoTime()
                    victimLatencyHolder.set(t1 - t0)
                    doneGate.countDown()
                }.start()

                readyGate.await()
                startGate.countDown()
                doneGate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                victimLatencies[round] = victimLatencyHolder.get()

                // Let the queue drain between rounds.
                Thread.sleep(20)
            }

            ht.quitSafely()

            val sorted = victimLatencies.sorted()
            val p50 = sorted[sorted.size / 2]
            val p90 = sorted[(sorted.size * 9) / 10]
            val p99 = sorted[(sorted.size * 99) / 100]
            val max = sorted.last()
            val min = sorted.first()

            val msg = buildString {
                appendLine("=== Attack 11: deliberate tail spike (victim under burst) ===")
                appendLine("noiseThreads=$noiseThreads, rounds=$rounds, burstSize=$burstSize")
                appendLine("victim post() ns:  min=$min p50=$p50 p90=$p90 p99=$p99 max=$max")
                appendLine("victim worst few: ${sorted.takeLast(5)}")
                appendLine("(if lock-free.max >> legacy.max here, Android 17 regressed this scenario)")
            }
            Log.i(TAG, msg.replace("\n", " | "))
            appendOutput(msg)
        }.start()
    }

    // ----- Attack 12: tombstone memory hold -----

    /**
     * Per the Android 17 blog, DeliQueue uses logical removal + deferred cleanup
     * ("tombstones"): a removed Message stays in the stack/heap until the Looper
     * pulls a cleanup pass. So a rapid post → remove cycle keeps the queue
     * physically populated even though it's logically empty.
     *
     * We post N tagged messages with a token, then remove them by token, and
     * measure JVM heap delta. If the new impl really tombstones, we should see
     * more memory held until the next Looper drain than the legacy impl.
     *
     *  Public API only: Handler.sendMessageDelayed / removeCallbacksAndMessages(token).
     */
    private fun runAttack12() {
        Thread {
            val ht = HandlerThread("MQBully-Tomb").apply { start() }
            val bh = Handler(ht.looper)
            val cycles = 200_000
            val token = Any()

            // Warm up + settle.
            repeat(2_000) {
                val m = Message.obtain(bh) { /* noop */ }
                m.obj = token
                bh.sendMessageDelayed(m, 1_000_000L)
            }
            bh.removeCallbacksAndMessages(token)
            Thread.sleep(100)
            System.gc()
            System.runFinalization()
            Thread.sleep(200)

            val rt = Runtime.getRuntime()
            val memBefore = rt.totalMemory() - rt.freeMemory()

            val tPost0 = System.nanoTime()
            for (i in 0 until cycles) {
                val m = Message.obtain(bh) { /* noop, never fires within test */ }
                m.obj = token
                bh.sendMessageDelayed(m, 1_000_000L + i)
            }
            val tPost1 = System.nanoTime()

            val memAfterPost = rt.totalMemory() - rt.freeMemory()

            val tRemove0 = System.nanoTime()
            bh.removeCallbacksAndMessages(token)
            val tRemove1 = System.nanoTime()

            val memAfterRemove = rt.totalMemory() - rt.freeMemory()

            // Give the Looper a chance to drain its cleanup work.
            Thread.sleep(200)
            System.gc()
            Thread.sleep(200)
            val memAfterDrain = rt.totalMemory() - rt.freeMemory()

            ht.quitSafely()

            val msg = buildString {
                appendLine("=== Attack 12: tombstone memory hold ===")
                appendLine("cycles=$cycles, target=HandlerThread (idle Looper)")
                appendLine("post all:   ${(tPost1 - tPost0) / 1_000_000}ms")
                appendLine("removeAll:  ${(tRemove1 - tRemove0) / 1_000_000}ms")
                appendLine("mem before:        ${memBefore / 1024}KB")
                appendLine("mem after post:    ${memAfterPost / 1024}KB (delta +${(memAfterPost - memBefore) / 1024}KB)")
                appendLine("mem after remove:  ${memAfterRemove / 1024}KB (delta vs before +${(memAfterRemove - memBefore) / 1024}KB)")
                appendLine("mem after drain:   ${memAfterDrain / 1024}KB (delta vs before +${(memAfterDrain - memBefore) / 1024}KB)")
                appendLine("(legacy: removeAll frees immediately; new: tombstones held until Looper drain)")
            }
            Log.i(TAG, msg.replace("\n", " | "))
            appendOutput(msg)
        }.start()
    }

    // ----- Attack 13: single-producer regression -----

    /**
     * The blog explicitly notes the branchless heap comparator pays an unconditional
     * cost: "if the branch is predictable most of the time, that wasted work can
     * slow your code down". The classic case is single-producer, single-consumer
     * with predictable order — no contention to absorb.
     *
     * We post N times from one thread to an idle HandlerThread and measure per-call
     * latency. If lock-free per-call cost > legacy per-call cost here, we have
     * isolated a regression: a workload that got WORSE on Android 17.
     *
     *  Public API only: Handler.post.
     */
    private fun runAttack13() {
        Thread {
            val ht = HandlerThread("MQBully-Single").apply { start() }
            val bh = Handler(ht.looper)
            val noop = Runnable { /* drained by HandlerThread */ }

            // Warm up the JIT and let everything settle.
            repeat(20_000) { bh.post(noop) }
            Thread.sleep(300)

            val iters = 200_000
            val latencies = LongArray(iters)
            val tWall0 = System.nanoTime()
            for (i in 0 until iters) {
                val t0 = System.nanoTime()
                bh.post(noop)
                val t1 = System.nanoTime()
                latencies[i] = t1 - t0
            }
            val tWallElapsed = System.nanoTime() - tWall0

            // Wait for the consumer to drain before quitting.
            Thread.sleep(500)
            ht.quitSafely()

            val sorted = latencies.sorted()
            val avg = latencies.average().toLong()
            val p50 = sorted[sorted.size / 2]
            val p90 = sorted[(sorted.size * 9) / 10]
            val p99 = sorted[(sorted.size * 99) / 100]
            val p999 = sorted[(sorted.size * 999) / 1000]
            val max = sorted.last()
            val throughput = iters.toLong() * 1_000_000_000L / tWallElapsed

            val msg = buildString {
                appendLine("=== Attack 13: single-producer regression (uncontended path) ===")
                appendLine("iters=$iters, single producer, idle HandlerThread consumer")
                appendLine("avg=${avg}ns  p50=${p50}ns  p90=${p90}ns  p99=${p99}ns  p999=${p999}ns  max=${max}ns")
                appendLine("wall=${tWallElapsed / 1_000_000}ms  throughput=${throughput} posts/sec")
                appendLine("(blog: \"branchless... can slow your code down\" when branch is predictable)")
            }
            Log.i(TAG, msg.replace("\n", " | "))
            appendOutput(msg)
        }.start()
    }

    // ----- Attack 14: tombstone accumulation probe -----

    /**
     * Decide whether what Attack 12 observed is a real *leak* (memory never freed)
     * or a *deferred reclaim* (Looper's cleanup pass eventually catches up).
     *
     * We run R rounds of (post N messages → removeAll), and after each round we
     * record JVM heap size *without* explicitly forcing GC and without draining
     * the HandlerThread. If memory grows monotonically across rounds, the
     * tombstones genuinely accumulate and we can drive the app to OOM. If memory
     * plateaus or returns, the Looper's cleanup is keeping up and "leak" is the
     * wrong word.
     *
     *  Public API only: Handler.sendMessageDelayed / removeCallbacksAndMessages.
     */
    private fun runAttack14() {
        Thread {
            val ht = HandlerThread("MQBully-Bloat").apply { start() }
            val bh = Handler(ht.looper)
            val token = Any()
            val rounds = 40
            val cyclesPerRound = 100_000

            // Initial baseline
            System.gc()
            Thread.sleep(150)
            val rt = Runtime.getRuntime()
            val baselineKB = (rt.totalMemory() - rt.freeMemory()) / 1024
            val maxHeapKB = rt.maxMemory() / 1024

            val sb = StringBuilder()
            sb.appendLine("=== Attack 14: tombstone accumulation probe ===")
            sb.appendLine("rounds=$rounds, cyclesPerRound=$cyclesPerRound")
            sb.appendLine("JVM maxHeap=${maxHeapKB}KB, baseline=${baselineKB}KB")

            var oom: Throwable? = null
            val mems = IntArray(rounds)
            for (r in 0 until rounds) {
                try {
                    for (i in 0 until cyclesPerRound) {
                        val m = Message.obtain(bh) { /* noop */ }
                        m.obj = token
                        bh.sendMessageDelayed(m, 1_000_000L + i)
                    }
                    bh.removeCallbacksAndMessages(token)
                } catch (t: OutOfMemoryError) {
                    oom = t
                    sb.appendLine("OOM caught at round $r: ${t.message}")
                    break
                } catch (t: Throwable) {
                    oom = t
                    sb.appendLine("Threw at round $r: ${t.javaClass.simpleName}: ${t.message}")
                    break
                }
                val nowKB = (rt.totalMemory() - rt.freeMemory()) / 1024
                mems[r] = nowKB.toInt()
                // Log every few rounds so we can see growth.
                if (r % 4 == 0 || r == rounds - 1) {
                    Log.i(TAG, "[attack14] round=$r mem=${nowKB}KB (delta vs baseline +${nowKB - baselineKB}KB)")
                }
            }

            // Let cleanup catch up, then GC, then measure.
            Thread.sleep(500)
            System.gc()
            Thread.sleep(300)
            val afterDrainKB = (rt.totalMemory() - rt.freeMemory()) / 1024

            ht.quitSafely()

            val maxObserved = mems.max()
            val ratio = maxObserved.toDouble() / baselineKB
            sb.appendLine("max observed: ${maxObserved}KB (${"%.1f".format(ratio)}x baseline)")
            sb.appendLine("after wait+GC: ${afterDrainKB}KB (delta vs baseline ${afterDrainKB - baselineKB}KB)")
            sb.appendLine(
                if (oom != null) "verdict: OOM-able (real leak)"
                else if (maxObserved >= baselineKB * 4) "verdict: accumulates strongly (close to leak)"
                else if (afterDrainKB > baselineKB * 2) "verdict: deferred reclaim (NOT a leak)"
                else "verdict: cleanup keeps up (definitely NOT a leak)"
            )
            Log.i(TAG, sb.toString().replace("\n", " | "))
            appendOutput(sb.toString())
        }.start()
    }

    private fun appendOutput(s: String) {
        runOnUiThread {
            output.text = output.text.toString() + "\n\n" + s
        }
    }
}
