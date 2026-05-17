# Case 05: Reflective `MessageQueue.next()` from BG

## 一言で

`MessageQueue.next()` を **background thread からリフレクションで直接呼ぶ**。
旧実装でこれをやると **Choreographer の VSYNC フレーム描画 Message を pop してしまう**。
つまり **UI 描画タスクを別スレッドが横取りする**。

## 使う API

- **内部**: `MessageQueue::class.java.getDeclaredMethod("next")` (戻り値 `android.os.Message`)
- 1 秒タイムアウト付きで invoke (Executor + Future)

## コード抜粋

```kotlin
val exec = Executors.newSingleThreadExecutor()
val queue = Looper.getMainLooper().queue
val future = exec.submit<String> {
    val nextMethod = MessageQueue::class.java
        .getDeclaredMethod("next").apply { isAccessible = true }
    val msg = nextMethod.invoke(queue)
    "returned: $msg"
}
val outcome = try {
    future.get(1, TimeUnit.SECONDS)
} catch (_: TimeoutException) {
    future.cancel(true)
    "BLOCKED > 1s"
}
```

## 再現

```bash
./scripts/run-attack.sh 5
```

## 実機計測 (Pixel 10 / Android 17)

```
LEGACY:
  returned in 0ms: { when=-2ms
                     callback=android.view.Choreographer$FrameDisplayEventReceiver
                     target=android.view.Choreographer$FrameHandler
                     async=true heapIndex=-1 }
```

main thread のために Choreographer が用意した **フレーム描画コールバック** を、
別スレッドがリフレクションで pop してしまった。
これが繰り返されると **main thread はフレームを描画できなくなる**。

```
LOCK-FREE:
  returned in 0ms: { ... heapIndex=-1 ... }
```

`heapIndex=-1` は新実装の min-heap で「もう heap にいない」状態を示すマーカー。
新実装でも一応取れるが、`next()` は Looper thread 専有が前提なので、別スレッドから呼ぶと **未定義動作** (公式 blog の "benign data race" 仮定が崩れる)。
内部 stack/heap の整合性を壊しうる。

## 何が起きてるか

旧実装の `next()`:

1. `synchronized(this)` を取る
2. linked-list の head (`mMessages`) を pop
3. 必要なら `nativeWake` で待ち合い
4. `Message` を返す

別スレッドからこれを呼ぶと、main thread の Looper が拾うべき Message を奪う。
特に Choreographer は VSYNC ごとに `async=true` な Message を post するので、
タイミング次第でこれを横取りできる。**UI 描画を盗む**検証ケース。

新実装の `next()`:

1. 自分の bulk-transfer pass で Treiber stack → min-heap に移す
2. min-heap の root を pop
3. Looper 専有前提で内部状態を書き換える

別スレッドから呼ぶと heap の整合性が壊れる可能性。
今回の実装では一発呼び出しだけだが、繰り返すとアプリ全体の dispatch が壊れる潜在リスクあり。

## 補足

「やっちゃダメだけど動く」型の典型例。旧実装の場合は **動く上に Choreographer を盗む** という凶悪な副作用付き。

## 関連

- 公式 blog "Walking forward and benign races" のセクション
- [Case 02: 外部 synchronized](./case-02-external-synchronized.md) — こちらは monitor 経由でブロック、こっちは next() を直接呼んで Message 横取り
