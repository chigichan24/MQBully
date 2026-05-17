# Case 14: Tombstone accumulation → `OutOfMemoryError`

> 本リポジトリで唯一、**Android 17 でアプリをクラッシュさせる** ことに成功した検証ケース。
> しかも **公開 API のみ** で再現可能。

## 一言で

`Handler.sendMessageDelayed` で 100,000 件積み → `removeCallbacksAndMessages(token)` で全削除、
という 1 ラウンドを **40 ラウンド連続** 実行。
- Legacy: 途中で GC がうまく回収する → baseline に戻れる
- **Lock-free: tombstone が物理的に解放されず累積 → round 25 で `OutOfMemoryError` → アプリクラッシュ**

## 使う API

- **公開**: `Handler.sendMessageDelayed`, `Message.obtain`, `removeCallbacksAndMessages(token)`
- **計測**: `Runtime.totalMemory/freeMemory`, `OutOfMemoryError` を try-catch

## コード抜粋

```kotlin
val ht = HandlerThread("MQBully-Bloat").apply { start() }
val bh = Handler(ht.looper)
val token = Any()
val rounds = 40
val cyclesPerRound = 100_000

System.gc(); Thread.sleep(150)
val baselineKB = (rt.totalMemory() - rt.freeMemory()) / 1024

try {
    for (r in 0 until rounds) {
        for (i in 0 until cyclesPerRound) {
            val m = Message.obtain(bh) { /* noop */ }
            m.obj = token
            bh.sendMessageDelayed(m, 1_000_000L + i)
        }
        bh.removeCallbacksAndMessages(token)
        val mem = (rt.totalMemory() - rt.freeMemory()) / 1024
        // round, mem を記録
    }
} catch (oom: OutOfMemoryError) {
    // round 25 でここに来る (lock-free のみ)
}
```

**ポイント**:

- `HandlerThread` は起動するが、active な work がほぼないので Looper が cleanup pass を回す機会が少ない
- `m.obj = token` で全削除トリガー (`removeCallbacksAndMessages(token)`) を仕掛けている
- `sendMessageDelayed(m, 1_000_000L + i)` で 1000 秒後 (= 実質的に発火しない遠未来)
- `System.gc()` を**明示的に呼ばない**: 実アプリと同じ条件にしたいから

## 再現

```bash
./scripts/run-attack.sh 14 25
```

`run-attack.sh` の最後の 25 は待ち時間 (秒)。40 round + drain 待ちで 15-20 秒かかる。

## 実機計測 (Pixel 10 / Android 17)

### Legacy (`adb am compat reset`)

```
JVM maxHeap=262144KB, baseline=2253KB
round  0 → 13MB
round  4 → 57MB
round  8 → 100MB
round 12 → 23MB    ← GC が回収
round 16 → 66MB
round 20 → 110MB
round 24 → 154MB
round 28 → 198MB
round 32 → 242MB
round 36 → 52MB    ← GC が回収
round 39 → 85MB
最終     → 2253KB  (baseline に戻る)
verdict  : accumulates strongly but GC-able
```

### Lock-free (`adb am compat enable USE_NEW_MESSAGEQUEUE`)

```
JVM maxHeap=262144KB, baseline=2253KB
round  0 → 13MB
round  4 → 57MB
round  8 → 100MB
round 12 → 144MB   ← GC されない
round 16 → 177MB   ← されない
round 20 → 221MB   ← されない
round 24 → 256MB   ← heap 上限到達
round 25 → 🔥 OutOfMemoryError 🔥 アプリクラッシュ
  "Failed to allocate a 104 byte allocation with 805744 free bytes
   and 786KB until OOM, target footprint 268435456, growth limit 268435456;
   giving up on allocation because <1% of heap free after GC."
after wait+GC: 261389KB (delta +259136KB)  ← GC しても戻らない
verdict : OOM-able (real leak)
```

## 「リーク」と呼んでよい根拠

Java での **メモリリーク** = 「不要になったオブジェクトへの参照が残り続けて GC が回収できない」状態。

- Legacy: `removeCallbacksAndMessages(token)` 直後に Message への参照が物理的に切れる → GC 対象 → 一時 242MB まで膨らんでも cleanup される
- **Lock-free: `removeCallbacksAndMessages(token)` してもtombstone として Treiber stack/min-heap に参照が残る → GC が回収できない**

公式 blog の "Logical removal + Deferred cleanup" は、Looper thread が定期的に cleanup pass を回す前提で設計されている。
しかし HandlerThread の Looper が active work をほとんど持たない idle 状態だと cleanup pass のトリガーが弱く、tombstone が物理的に解放されない。
結果として **公開 API のみで再現可能なメモリリーク** が発生する。

## 影響を受けやすい実アプリのパターン

- background HandlerThread に大量 post → cancel するパターン
- 動画/音声処理で毎フレーム post → 中断時 cancel
- RecyclerView の scroll callback を毎フレーム post → cancel
- `WorkManager` 内部の job dispatch (要検証)
- 長時間 idle になりがちな worker thread

## 安全な構成 (実機検証済 4 パターン)

**結論: `release build だから安全` は誤り。安全条件は `targetSdk < 37` のみ**。

### 4 構成マトリクス (Pixel 10 / Android 17 user ビルドで全実測)

| build | targetSdk | 新実装の状態 | Case 14 結果 |
|---|---|---|---|
| debug (`debuggable=true`) | 36 | `am compat` で任意切替可 | 切替に応じて GC回収 or **OOM** |
| **debug** | **37** (CinnamonBun) | **自動 ON** | **OOM** |
| release (`debuggable=false`) | 36 | 強制 ON 不可 / 自動 ON されず → 旧実装固定 | GC で回収、OOM せず |
| **release** | **37** (CinnamonBun) | **自動 ON** (debuggable 関係なし) | **OOM** |

### 検証ログ

**release + targetSdk=36** (compat enable は拒否される):

```
$ adb shell am compat enable USE_NEW_MESSAGEQUEUE com.chigichan24.messagequeuebully
Cannot override a change on a non-debuggable app and user build.

$ adb shell am start ... --es attack 14
round 12 → 144MB
round 16 →  27MB  ← GC が回収  (Legacy 挙動)
round 28 → 159MB
round 32 → 202MB
round 36 →  28MB  ← GC が回収  (Legacy 挙動)
最終     →   2.2MB (baseline 戻り)
verdict  : accumulates strongly but GC-able
```

**release + targetSdk=CinnamonBun** (本リポジトリでは `compileSdk { version = preview("CinnamonBun") }` への置換で再現):

```
round  0 → 13MB
round  4 → 57MB
round  8 → 101MB
round 12 → 144MB
round 16 → 188MB
round 20 → 217MB
round 24 → 261MB ← heap 上限到達
→ 🔥 OutOfMemoryError 🔥
  "Throwing OutOfMemoryError 'Failed to allocate ...
   giving up on allocation because <1% of heap free after GC'"
```

### 何が言えるか

- **debuggable=false (release) だから安全」は今回否定された**。compat フラグの override は確かにできなくなるが、targetSdk=37 のときは override の必要がない (デフォルトで新実装が走る)
- **唯一効く安全弁は `targetSdk < 37` の据え置き**
- ただし Google Play は毎年 targetSdk 要件を引き上げる。**Android 17 が GA 直後の数か月だけ有効な逃げ道**であり、constituent な対策にはならない

### 本物のワークアラウンド (仮説、未検証)

`targetSdk >= 37` で安全に動かしたい場合の候補:

- `HandlerThread` の Looper に定期的に `post {}` (空 Runnable) を投げて cleanup pass を誘発
- `removeCallbacksAndMessages(null)` ではなく 1 件ずつ `removeCallbacks(r)` する (cleanup タイミングが違うかも)
- 大量 post/cancel をやめて、`AtomicBoolean.isCancelled()` 等の cooperative cancellation に置き換える


## 公式情報

- [Under the hood: Android 17's lock-free MessageQueue](https://developer.android.com/blog/posts/under-the-hood-android-17-lock-free-message-queue) — "Logical removal", "Deferred cleanup" の節
- [MessageQueue behavior change guidance](https://developer.android.com/about/versions/17/changes/messagequeue)

## 関連

- [Case 12: 1 ラウンドでも tombstone は観測できる](./case-12-tombstone-memory-hold.md)
- [Case 07: removeCallbacksAndMessages の所要時間自体は微速化](./case-07-bulk-cleanup-latency.md)
