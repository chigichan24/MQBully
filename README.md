# MQBully

> Android 17 で `android.os.MessageQueue` が DeliQueue という lock-free 実装に置き換わった。
> 公開 API/内部 API の両面から **14 通りの検証** で挙動差を観測し、最後の 1 つで OOM クラッシュまで持っていった全実機ログ集。

`Looper` / `MessageQueue` を **14 種類の方法で検証** して、Android 16 (旧 synchronized 実装) と Android 17 (新 DeliQueue 実装) の挙動差を実機で観測する検証アプリ。
すべての結果は **Pixel 10 / Android 17 (CinnamonBun) / API 37 / user ビルド** での実測ログ。

---

## ハイライト: 公開 API のみで Android 17 を OOM クラッシュさせた

[**→ Case 14: tombstone accumulation → OOM**](./docs/case-14-tombstone-oom.md)

```
LEGACY (Android 16 相当 = synchronized impl)
  round  8 → 100MB     round 16 → 66MB (GC回収)   round 32 → 242MB
  最終   → 2.2MB (baseline 戻り)   verdict: GC-able

LOCK-FREE (Android 17 = DeliQueue)
  round  8 → 100MB     round 16 → 177MB           round 24 → 256MB
  round 25 → 🔥 OutOfMemoryError 🔥
  verdict: OOM-able (real leak)
```

- 使った API は **`Handler.sendMessageDelayed` / `removeCallbacksAndMessages(token)` のみ**(リフレクションなし)
- 公式 blog が言う「[deferred cleanup (tombstones)](https://developer.android.com/blog/posts/under-the-hood-android-17-lock-free-message-queue)」が、HandlerThread の Looper が cleanup pass を回す機会を与えなければ **GC が回収できないメモリリーク**として観測される
- Android 16 では `removeCallbacksAndMessages` の瞬間に参照が切れて GC される。Android 17 では tombstone マークがついて参照が残るので GC が回収できない
- 同じコードで Android 16 なら最大 242MB、Android 17 では 256MB の Java heap 上限を 25 ラウンドで食い尽くす

詳細は [docs/case-14-tombstone-oom.md](./docs/case-14-tombstone-oom.md) を参照。

---

## 14 検証ケースの一覧

公開 API だけで動くか、リフレクションが必要か、で大きく 2 種類に分かれる。
さらにどちら側の挙動が変わるかでカテゴリを分類している。

| # | 検証ケース | 使う API | Legacy | Lock-free | 結末 | 詳細 |
|---|---|---|---|---|---|---|
| 1 | `mMessages` をリフレクションで読む | 内部 | `Message@xxxx` | `null` | 観測側が壊れる | [docs](./docs/case-01-reflect-mmessages.md) |
| 2 | `synchronized(Looper.myQueue().queue)` で外部ロック | 内部 | post 2802ms 詰まる | post 1ms | 外部ロックが無効化 | [docs](./docs/case-02-external-synchronized.md) |
| 3 | `Message.next` をリフレクションで辿る | 内部 | 11 件取れる | 0 件 | 観測情報が消える | [docs](./docs/case-03-walk-message-chain.md) |
| 4 | `mMessages = null` を強制セット | 内部 | **Looper 死亡** (victim 0/5) | 無効化 (victim 5/5) | 16 で動いて 17 で動かない | [docs](./docs/case-04-force-set-mmessages-null.md) |
| 5 | BG thread から `MessageQueue.next()` を直接呼ぶ | 内部 | **Choreographer フレームを横取り** | 同じく取れる | UI 描画横取り | [docs](./docs/case-05-reflective-next.md) |
| 6 | 同 `when` で N 件 post して順序確認 | 公開 | FIFO 維持 | FIFO 維持 | 後方互換 ✓ | [docs](./docs/case-06-same-when-order.md) |
| 7 | 10,000 件 → `removeCallbacksAndMessages(null)` の所要時間 | 公開 | 3ms | 1ms | 微速化 | [docs](./docs/case-07-bulk-cleanup-latency.md) |
| 8 | `postAtFrontOfQueue` を 8 回連続 | 公開 | LIFO 維持 | LIFO 維持 | 後方互換 ✓ | [docs](./docs/case-08-postAtFrontOfQueue.md) |
| 9 | 8 producers × 5,000 posts の wall time | 公開 | 3955ms | **18ms (約 219倍速)** | 大幅改善 | [docs](./docs/case-09-multi-producer-throughput.md) |
| 10 | 6 producers × 1.5s の fairness + latency 分布 | 公開 | fairness 1.14x, max 28ms | fairness 1.14x, max 382ms (noise) | 弱点見つからず | [docs](./docs/case-10-fairness-variance.md) |
| 11 | 32 noise + 1 victim を 50 round 同時 wake で CAS リトライ嵐 | 公開 | victim max 9.5ms | victim max 0.63ms | 弱点見つからず | [docs](./docs/case-11-tail-spike.md) |
| 12 | 200,000 件 post → removeAll → drain 後メモリ計測 (1 ラウンド) | 公開 | drain 後 2.2MB (戻る) | drain 後 22.7MB (残る) | tombstone 観測 | [docs](./docs/case-12-tombstone-memory-hold.md) |
| 13 | 単一 producer × 200,000 posts のレイテンシ | 公開 | avg 836ns, p99 2.2µs | avg 629ns, p99 1.9µs | 弱点見つからず | [docs](./docs/case-13-single-producer-regression.md) |
| **14** | **post/remove を 40 ラウンド連続 → OOM 狙い** | 公開 | 242MB 上下、最後に GC 回収 | **round 25 で `OutOfMemoryError`** | **本物のリーク → アプリクラッシュ** | [docs](./docs/case-14-tombstone-oom.md) |

挙動差のレイヤー:

- **内部 reflection (1–5)**: `@UnsupportedAppUsage` の private を覗いていれば壊れる。Espresso 3.6 以前 / Robolectric `LEGACY` LooperMode が壊れた理由がここ
- **公開 API は基本的に維持される (6–8, 10, 11, 13)**: AOSP チームが後方互換性に執念を燃やした証拠
- **公開 API でも明らかに改善された (7, 9)**: 8 producers の wall time が 200 倍速
- **公開 API でも明らかに悪化した (12, 14)**: tombstone deferred cleanup の代償。**14 は OOM までいく本物のリーク**

---

## 環境

| 項目 | 値 |
|---|---|
| Device | Pixel 10 (`frankel_beta`) |
| OS | Android 17 (`CinnamonBun`) / API 37 |
| Build | `frankel:CinnamonBun/CP31.260423.012.A1/15327290:user/release-keys` |
| `USE_NEW_MESSAGEQUEUE` ChangeId | `421623328` |
| `enableSinceTargetSdk` | 37 |
| `ro.debuggable` | 0 (user ビルド) |
| Host | macOS 26.x (Darwin arm64) |
| AGP / Gradle | 9.0.0 / 9.1.0 |
| Kotlin | 2.0.21 (AGP 内蔵) |

ポイント: アプリは **`targetSdk=36` + `debuggable=true`** でビルドしている。
こうすると `USE_NEW_MESSAGEQUEUE` は **デフォルトでは旧実装のまま**で、
`adb shell am compat enable USE_NEW_MESSAGEQUEUE <pkg>` で新実装に切り替えられる。
同一 APK・同一端末でフラグだけで両方の挙動を見せられる。

---

## セットアップ

```bash
# 1. Pixel に Android 17 が入った実機を USB で接続
adb devices  # device が見えること

# 2. ビルド + インストール
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. アプリ起動（ボタンを手で押すモード）
adb shell am start -n com.chigichan24.messagequeuebully/.MainActivity
```

## 検証ケースの走らせ方

### 個別 (旧/新 自動切り替え)

```bash
./scripts/run-attack.sh 14        # Case 14 を旧/新両方で実行して logcat 表示
./scripts/run-attack.sh 9 8       # 待ち時間 8 秒 (重いケース用)
```

`run-attack.sh` は内部で `am compat enable USE_NEW_MESSAGEQUEUE` を切り替えて 2 回走らせる。

### フラグだけ切り替え

```bash
./scripts/toggle.sh new      # USE_NEW_MESSAGEQUEUE 有効化 + force-stop
./scripts/toggle.sh old      # 無効化 + force-stop
./scripts/toggle.sh reset    # オーバーライド解除
./scripts/toggle.sh status   # 現在の状態確認
```

### 手動 (UI から)

アプリを開いてボタンを押すと該当ケースが走る。
すべての結果は `adb logcat -s MQBully:V` で確認できる。

### Intent extra で特定ケースを auto-run

```bash
adb shell am start -n com.chigichan24.messagequeuebully/.MainActivity --es attack 14
```

---

## 公式リソース

- [Under the hood: Android 17's lock-free MessageQueue](https://developer.android.com/blog/posts/under-the-hood-android-17-lock-free-message-queue)
- [MessageQueue behavior change guidance](https://developer.android.com/about/versions/17/changes/messagequeue)
- [Behavior changes: Apps targeting Android 17 or higher](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Compatibility framework tools](https://developer.android.com/guide/app-compatibility/test-debug)

## ライセンス

MIT
