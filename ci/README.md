# 為什麼 workflow 放在這裡而不是 `.github/workflows/`

GitHub 規定：**Personal Access Token 沒有 `workflow` 權限時，不能 push 任何動到
`.github/workflows/` 的 commit**，會被擋下來：

```
! [remote rejected] main -> main
  (refusing to allow a Personal Access Token to create or update workflow
   `.github/workflows/build-apk.yml` without `workflow` scope)
```

所以先把它放在 `ci/`，讓程式碼可以正常推上去。要啟用自動編譯，二選一：

---

## 方法 A：幫 token 加上 workflow 權限（推薦，一勞永逸）

**Classic token**（`ghp_` 開頭）
1. 開 <https://github.com/settings/tokens>
2. 點你正在用的那個 token
3. 勾選 **`workflow`**
4. 按 **Update token** —— token 字串不會變，Windows 存的認證不用重設
5. 回到專案：

```bash
git mv ci/build-apk.yml .github/workflows/build-apk.yml
```

（`.github/workflows/` 資料夾不存在的話 git mv 會失敗，先手動建立即可）

**Fine-grained token**（`github_pat_` 開頭）
1. 開 <https://github.com/settings/personal-access-tokens>
2. 點該 token → **Repository access** 確認有包含這個 repo
3. **Repository permissions** → **Workflows** → 設成 **Read and write**
4. 儲存後同上

---

## 方法 B：用 GitHub 網頁介面建立（不用碰 token）

網頁上的編輯不受 token 權限限制。

1. 到 repo 頁面 → **Actions** 分頁 → **set up a workflow yourself**
2. 把 [`ci/build-apk.yml`](build-apk.yml) 的內容整份貼進去
3. 檔名改成 `build-apk.yml`（路徑會自動變成 `.github/workflows/build-apk.yml`）
4. **Commit changes**

存檔後 Actions 會立刻跑第一次，跑完在該次執行頁面下方的 **Artifacts** 就能下載 APK。
