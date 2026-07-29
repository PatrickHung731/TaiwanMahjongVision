# TaiwanMahjongVision AI

完全離線（On-Device）的 **台灣 16 張麻將** AI 助手 Android App。
手機開著鏡頭對牌桌，螢幕直接告訴你：

```
建議切牌：9條 (放槍 1.8%) | 聽牌進張：3筒, 6筒 (剩餘 5 張)
想收手：改打 東 (放槍 0.2%, 1向聽, 進張 28 張)
```

- 🀄 專攻台灣 16 張：胡牌型固定為 **5 面子 + 1 眼**
- 🛡️ **會算放槍機率**：從三家的牌河、副露、已見牌推估，而不是只教你衝
- 📴 完全離線：辨識與算牌都在手機上跑，不連任何雲端 API
- 🔋 低功耗：預覽 30 FPS，但**每秒只抽 1 張影格**做 AI 推論，避免過熱降頻
- 👁️ 會記牌河：進張張數自動扣掉海底已見的牌（人腦記不住，鏡頭可以）

---

## 目前進度

| 階段 | 內容 | 狀態 |
| --- | --- | --- |
| Phase 1A | 牌效率引擎（向聽數、進張、切牌） | ✅ 完成 |
| Phase 1B | 守備引擎（放槍機率、風格取捨） | ✅ 完成 |
| Phase 2 | 鏡頭辨識：1 FPS 抽樣 + TFLite YOLO + 牌河解析 | ⬜ 待開發 |
| Phase 3 | CameraX 預覽 + Compose AR 覆蓋層 | ⬜ 待開發 |

詳見 [docs/PHASE_PLAN.md](docs/PHASE_PLAN.md)。

> **關於放槍機率**：對手的暗牌看不到，所以任何軟體給的放槍機率都是**估計值**。
> 這個模型建立在鏡頭看得到的三件事上——他打過什麼、他吃碰了什麼、場上還剩幾張，
> 並且用台灣 16 張自己跑的模擬統計當基準（不是套日麻的數據）。
> 方法與限制見 [docs/DANGER_MODEL.md](docs/DANGER_MODEL.md)。

---

## 專案結構

```
TaiwanMahjongVision/
├── core-engine/                    # Phase 1：純 Kotlin 引擎（無 Android 相依）
│   └── src/
│       ├── main/kotlin/com/tmvision/engine/
│       │   ├── Tiles.kt                  # 34 位元索引、MPSZ 記法、中文牌名
│       │   ├── HandState.kt              # 手牌狀態 + SeenTiles 已見牌
│       │   ├── ShantenCalculator.kt      # 向聽數（最大重疊 DP）
│       │   ├── WinChecker.kt             # 胡牌判定與牌型拆解
│       │   ├── TaiwanMahjongEngine.kt    # 進張、切牌建議
│       │   ├── Analysis.kt               # 效率分析結果 + 覆蓋層文字
│       │   ├── TableState.kt             # 整桌狀態：三家牌河、副露、巡目
│       │   ├── DangerStats.kt            # 台灣 16 張模擬統計表（自動產生）
│       │   ├── DangerEstimator.kt        # 放槍機率
│       │   └── DiscardAdvisor.kt         # 效率 × 風險 → 最終建議
│       └── test/kotlin/com/tmvision/engine/
├── tools/
│   ├── verify_engine.py            # 演算法三重交叉驗證（純 Python，不需 JDK）
│   ├── generate_wait_stats.py      # 模擬台灣 16 張，產生危險度統計表
│   └── danger_model.py             # 放槍機率模型的 Python 對照實作
└── docs/
    ├── ALGORITHM.md                # 向聽數演算法與正確性論證
    ├── DANGER_MODEL.md             # 放槍機率模型與它的限制
    ├── PHASE_PLAN.md               # 分階段開發計畫
    └── wait_stats.json             # 模擬統計原始數據
```

---

## 怎麼用

### 只算效率（沒有牌河資料時）

```kotlin
val engine = TaiwanMahjongEngine()

// 17 張（摸牌後待切）：一二三萬 四五六萬 七八九萬 一二三筒 四五筒 東東 + 摸到 9 條
val hand = HandState.of("123m456m789m123p45p11z9s")

// 牌河已經看到 2 張 3 筒
val seen = SeenTiles.of("33p")

println(engine.analyze(hand, seen).overlayText())
// 建議切牌：9條 | 聽牌進張：3筒, 6筒 (剩餘 5 張)
```

### 效率 + 放槍機率（看得到整桌時）

```kotlin
val table = TableState(
    hand = HandState.of("123m456m789m123p45p11z5s"),
    opponents = listOf(
        OpponentState(Seat.LEFT,   river = listOf(/* 上家牌河，依順序 */)),
        OpponentState(Seat.ACROSS, river = listOf(...), meldedSets = 2,
                      meldedTiles = Tiles.parse("111p234p")),   // 對家在收筒子
        OpponentState(Seat.RIGHT,  river = listOf(...), meldedSets = 1),
    ),
    turn = 14,
)

val advice = DiscardAdvisor().advise(table, PlayStyle.BALANCED)
println(advice.overlayText())      // 建議切牌：5條 (放槍 3.2%) | 聽牌進張：3筒, 6筒 (剩餘 6 張)
println(advice.alternativeText())  // 想收手：改打 東 (放槍 0.3%, 1向聽, 進張 28 張)
```

三種風格：`PlayStyle.ATTACK`（衝聽牌）、`BALANCED`（預設）、`DEFENSE`（寧可退回一向聽）。

牌面記法用 MPSZ：`m` 萬、`p` 筒、`s` 條、`z` 字牌（1~7 = 東南西北中發白）。
副露（吃碰槓）用 `meldedSets` 表示，每一組讓手牌少 3 張：

```kotlin
HandState.of("123m456m78p11z", meldedSets = 2)   // 副露 2 組 + 暗牌 10 張
```

---

## 執行測試

Phase 1 是純 Kotlin JVM module，**不需要 Android SDK 或模擬器**，但需要 JDK 17。

```bash
gradlew.bat :core-engine:test
```

> **這台電腦目前沒有安裝 JDK / Gradle / Android Studio**，所以 Kotlin 測試尚未在本機執行過。
> 裝好 [Android Studio](https://developer.android.com/studio)（內含 JDK 與 Gradle）後，
> 用它開啟本專案即可自動產生 `gradlew`，然後跑上面那行指令。

### 不裝 JDK 也能驗證演算法

`tools/verify_engine.py` 是演算法的 Python 對照實作 + 暴力窮舉真值，
Kotlin 單元測試的期望值就是由它產生的：

```bash
python tools/verify_engine.py
```

```
[1] DP vs 面子分解公式：1600 手，不一致 0 手
[2] 胡牌判定 (shanten==-1 vs 牌型拆解)：1600 手，不一致 0 手
[3] 暴力窮舉真值：{0: 20, 1: 20, 2: 20} 共 60 手（另含 17 張），不一致 0 手
*** 全部通過 ***
```

加 `--quick` 可略過較慢的暴力窮舉。

---

## 演算法

向聽數用「**最大重疊 DP**」計算，而不是傳統的面子/搭子分解公式——
後者推廣到 5 面子時邊界條款很容易寫錯，而且錯了不會當掉，只會安靜地建議你切錯牌。

> 向聽數 = (胡牌張數 − 手牌與最接近胡牌型的最大重疊張數) − 1

完整推導與驗證方式見 [docs/ALGORITHM.md](docs/ALGORITHM.md)。
