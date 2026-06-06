# PAI Gateway · Android

把 Android 手機變成 PAI 的一個節點：內建 MCP server、**內建受控瀏覽器**（AI 可實際操作）、**語音對話 UI**，並用 cloudflared 通道讓雲端 PAI 連得到。

## 功能
- **節點分頁**：一鍵配對並啟動（MCP server + cloudflared 通道 + 自動註冊到 PAI）
- **語音對話分頁**：內嵌 `/voice`，喚醒（嘿助理）、打斷、操控全功能，授予麥克風即可說話
- **瀏覽器分頁**：內建 WebView 受控瀏覽器，AI 透過 `browser_*` 工具實際操作（導航/點擊/輸入/讀取），含 React/SPA 相容（cursor:pointer 元素、座標事件派發、受控元件填值）
- MCP 工具：`browser_navigate / snapshot / click / type / read / back / current_url`、`open_url`、`device_info`

## 編譯
1. 用 **Android Studio**（Koala 以上）開啟此資料夾
2. 等 Gradle sync（會自動下載 gradle 8.7 + 依賴）
3. 接手機（開 USB 偵錯）或模擬器 → Run

> 命令列：`./gradlew assembleDebug`（需先 `gradle wrapper` 產生 wrapper jar，或用 Android Studio）

## cloudflared 通道（讓雲端 PAI 連到手機）
手機在 4G/NAT 後沒有公網 IP，需要 cloudflared。Android 10+ 只允許執行 `jniLibs` 內的二進位：

1. 下載 `cloudflared-linux-arm64`：https://github.com/cloudflare/cloudflared/releases
2. 改名為 `libcloudflared.so`，放到：
   `app/src/main/jniLibs/arm64-v8a/libcloudflared.so`
3. 重新編譯。App 啟動時會自動跑 cloudflared、取得 `trycloudflare` 公網網址並註冊到 PAI。

> 沒放 binary 也能用：會改用「區域網址」註冊（限 PAI 與手機同網段時）。

## 一鍵配對
**配對碼**格式為 base64 或 JSON：
```json
{"pai":"https://pai.vito1317.com","token":"<PAI 註冊 Token>","name":"我的手機"}
```
- `token` = PAI 中控台的 gateway 註冊 Token（一鍵指令 `REGISTER_TOKEN=...` 那段）
- 在「節點」分頁貼上配對碼 → 按「🔗 一鍵配對並啟動」→ 自動填好設定、啟動 server、建立通道、註冊到 PAI

也可手動填 PAI 網址 + Token + 節點名稱後按「啟動並串接」。

## 架構
| 元件 | 說明 |
|---|---|
| `GatewayServer` | NanoHTTPD：`/health`、`/mcp`（JSON-RPC：initialize / tools/list / tools/call），密鑰 `X-Gateway-Secret` |
| `BrowserController` | WebView 自動化（snapshot JS + 事件派發點擊 + 受控元件填值），主執行緒同步橋接 |
| `McpTools` | 工具定義 + 分派 |
| `GatewayService` | 前景服務：server + cloudflared + 註冊 |
| `Cloudflared` | 從 jniLibs 執行 cloudflared、解析公網網址 |
| `MainActivity` | Compose UI：節點 / 語音 / 瀏覽器 三分頁 |
