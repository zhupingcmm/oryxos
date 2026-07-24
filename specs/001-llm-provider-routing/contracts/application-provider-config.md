# 契约：实例级 Provider 目录配置（application.yml）

**目的**：定义 `application.yml` 中 LLM Provider 路由层配置项的格式
**关联**：[spec.md](./spec.md) FR-001 ~ FR-006、[research.md](./research.md) R-01、R-04

---

## 完整配置示例

```yaml
oryxos:
  providers:
    # —— 必备：实例级 Provider 目录 ——
    # 每个条目是一个独立 Provider 实例；name 必须全局唯一
    # MVP 演示必须至少含 3 家不同供应商（spec 假设 #7 + 澄清 Q1）
    - name: deepseek
      model: deepseek-chat
      credentialRef: ${DEEPSEEK_API_KEY}    # 启动期解析，缺失则 fail-fast
      # endpoint 可选；不填则走 Provider 类型默认
      options:
        temperature: 0.7                    # 默认采样温度

    - name: qwen
      model: qwen-plus
      credentialRef: ${QWEN_API_KEY}
      options:
        temperature: 0.5

    - name: minimax
      model: MiniMax-M3
      credentialRef: ${MINIMAX_API_KEY}
      options:
        temperature: 0.5

    # —— 演示用：同 type 多实例（US-3 验收，扩展场景）——
    # 同一 DeepSeek 账号可加多实例以演示"同 type 路由不串扰"
    - name: deepseek-prod
      model: deepseek-chat
      credentialRef: ${DEEPSEEK_API_KEY_PROD}
    - name: deepseek-dev
      model: deepseek-chat
      credentialRef: ${DEEPSEEK_API_KEY_DEV}
```

---

## 字段约束

| 字段 | 类型 | 必填 | 约束 / 说明 |
|------|------|------|------------|
| `name` | String | ✅ | 路由键；全局唯一；匹配 `^[a-z][a-z0-9-]{0,63}$` |
| `model` | String | ✅ | 模型标识（如 `deepseek-chat`、`qwen-plus`） |
| `credentialRef` | String | ✅ | 必须是 `${ENV_VAR}` 形式（仅大写字母+数字+下划线） |
| `endpoint` | String | ❌ | 不填走 Provider 类型默认；填了走自定义 base URL |
| `options` | Map | ❌ | 私有参数（temperature / top_p / max_tokens 等） |

---

## 启动期校验规则

应用启动时，Provider 路由层必须按以下顺序校验：

1. **目录非空**：`oryxos.providers` 至少含 1 条；MVP 演示要求至少 3 条不同供应商（spec 假设 #7）
2. **name 唯一**：任意两条 name 相同 → 启动失败，错误信息指出冲突的两个位置
3. **name 格式合法**：每个 name 匹配正则 `^[a-z][a-z0-9-]{0,63}$`
4. **credentialRef 形态合法**：必须是 `${[A-Z_][A-Z0-9_]*}` 形式（不接受纯字符串）
5. **credentialRef 解析成功**：启动期每个 `${...}` 必须解析为非空字符串；任一解析为字面 `${...}` 或空串 → 启动失败
6. **model 非空**：每条的 `model` 字段必须存在且非空字符串

任何一条校验失败 → 进程退出并打印 `IllegalStateException` 堆栈。

---

## 反例（应被拒绝）

```yaml
# ❌ 硬编码凭证 → 启动失败
- name: deepseek
  credentialRef: sk-abc123def456...

# ❌ 凭证字段不是 ${...} 形式 → 启动失败
- name: deepseek
  credentialRef: DEEPSEEK_API_KEY

# ❌ 缺失 name → 启动失败
- model: deepseek-chat
  credentialRef: ${DEEPSEEK_API_KEY}

# ❌ 重复 name → 启动失败
- name: deepseek
  ...
- name: deepseek    # 冲突
  ...

# ❌ 缺失 model → 启动失败
- name: deepseek
  credentialRef: ${DEEPSEEK_API_KEY}

# ❌ name 含大写或下划线 → 启动失败
- name: DeepSeek_Prod
```

---

## 与 Profile 中 provider 字段的关系

- `application.yml` 的 `name` 是**实例级**路由键
- Profile 的 `provider.name` 必须引用**已配置的**实例级 name 之一
- Profile 的 `provider.model` 可与 `application.yml` 中该 name 的 `model` **不同**（即 Profile 可以"换模型而不换 Provider"——US-4 热切换的语义）
- Profile 的 `provider.model` 为空时使用 `application.yml` 中该 name 的默认 `model`

示例（Profile.yaml 端）：

```yaml
# 引用 deepseek 这个 Provider，但用更便宜的 deepseek-chat 模型
provider:
  name: deepseek
  model: deepseek-chat

# 引用同一个 deepseek，但用 deepseek-coder
provider:
  name: deepseek
  model: deepseek-coder
```
