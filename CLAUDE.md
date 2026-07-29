# TaiwanMahjongVision AI — 專案須知

離線台灣 16 張麻將 AI 助手（Android APK）。開工前先看 [README.md](README.md) 與
[docs/PHASE_PLAN.md](docs/PHASE_PLAN.md) 確認目前進度。

## 規則鐵律

- **台灣 16 張**：胡牌型固定 5 面子 + 1 眼；手牌 16 張，摸牌後 17 張。
  不要套日麻的 4 面子 + 1 眼、也不要加七對子/國士無雙。
- 副露（吃、碰、明槓、暗槓）各算 **1 個面子**，手牌少 3 張；
  槓用掉 4 張牌但仍只是 1 個面子，那 4 張**不留在** `HandState.concealed`。
- 花牌不進 34 陣列，只用 `HandState.flowers` 計張數。

## 開發環境現況

- 這台機器 **沒有 JDK / Gradle / Android Studio / Android SDK**（2026-07 確認）。
  Kotlin 程式碼無法在本機編譯或跑測試——改動演算法時，請用
  `python tools/verify_engine.py` 驗證邏輯，不要假裝測試跑過了。
- Python 可用（`python --version` → 3.11）。

## 改演算法時的規矩

1. `tools/verify_engine.py` 裡有三套互相獨立的實作（DP、面子分解公式、暴力窮舉真值）。
   Kotlin `ShantenCalculator` 的任何改動，都要同步改 Python 的 DP 版本並重跑驗證。
2. **單元測試的期望值一律由驗證工具產生**，不要用手算或憑印象填數字——
   16 張的向聽數很反直覺（例如 `1122334455667788m` 是聽牌不是 4 向聽）。
3. `WinChecker` 刻意用與 DP 完全不同的演算法實作，是 `ShantenCalculator` 的對照組。
   測試裡 `shanten == -1` 必須恆等於 `WinChecker.isWinning()`，不要為了「簡化」把其中一個改成呼叫另一個。

## 放槍機率模型的規矩

1. **這是估計值，不是真值**，UI 與文件都必須講清楚。看不到對手暗牌，不可能有真值。
2. `DangerStats.kt` 的數字由 `tools/generate_wait_stats.py` 模擬產生（輸出在 `docs/wait_stats.json`），
   **不要手動改那些數字**。要調整就重跑模擬，並把 `meta.simulated_hands` 一起更新。
3. `DangerEstimator` 改公式時，`tools/danger_model.py` 要同步改並重跑——
   它會驗證模型該有的性質（現物較安全、絕張不可能被胡、中張比么九危險⋯⋯）。
4. Kotlin 測試只驗**性質**不驗絕對數值，因為統計表重跑會微幅變動。
5. `DiscardAdvisor` 裡的 `SHANTEN_DECAY` / `RISK_SCALE` 是**啟發式權重**不是統計值，
   註解已標明，不要假裝它們有數據支撐。
6. 沒有牌河資料時（`TableState.opponents` 為空），放槍機率必須顯示「資料不足」，
   **絕對不要編一個數字出來**——那比不顯示更糟。
7. 危險度權重必須依**聽型**拆三塊再套修正，不要圖方便合在一起：
   兩面／嵌張／邊張他握的是鄰牌，雙碰／單吊他才握有那張牌本身。
   「這張 4 張全現身了所以安全」**只對字牌成立**；數牌絕張照樣會被嵌張胡。
   這條寫錯會把 8% 的牌報成 0%，是這個模型最危險的 bug，測試已鎖住。
8. `TableState.inconsistencies()` 是給 Phase 2 把關用的：辨識出「某種牌超過 4 張」
   就代表這一幀認錯了，寧可顯示「請對準牌桌」也不要拿錯的牌面去算。

## 台灣規則的關鍵差異（別套日麻）

- **過水規則**：台灣多數牌桌的過水只到自己下次摸牌，所以「現物」遠不如日麻安全。
  這是 `SafeTileRule` 這個設定存在的原因，預設 `TAIWAN_PASS_UNTIL_NEXT_DRAW`。
  真正上桌前要跟使用者的牌桌規則對齊。
- **沒有立直**：無法從立直宣告判斷對手聽牌，只能用巡目 + 副露數推估，
  這是整個危險度模型最粗的一環，有真實牌譜時應優先校正。

## 效能預算（Phase 2/3 會用到）

- AI 推論 **每秒只跑 1 次**（1 FPS 抽樣），預覽維持 30 FPS。
  影格丟棄策略是「上一張沒算完就直接丟」，不要排隊。
- 單次 `analyze()` 在 JVM 上是毫秒等級，手機上仍應遠低於 100 ms；
  `HandAnalysis.computeNanos` 就是拿來監控這件事的。
- `ShantenCalculator` **不是執行緒安全的**（內部有共用 memo 緩衝區），
  請用 `ShantenCalculator.forCurrentThread()`。

## 撰碼慣例

- 註解與使用者可見文字用**繁體中文**，程式碼識別字用英文。
- 錯誤訊息要講清楚實際數字（例如「應為 16 或 17 張，實際 15 張」），
  因為影像辨識抓錯張數是最常見的失敗情境，訊息含糊會很難除錯。
