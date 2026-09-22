# 管理后台设计系统

本文是 `src/styles.css` 的配套说明。改样式前先读这里 —— 尤其是「三条硬规则」，
它们解释了为什么很多看起来"可以更花哨"的地方刻意保持了克制。

## 方向：中性石墨 (neutral graphite)

表面上只有三层明度，靠**发丝线**分隔，不靠阴影堆叠、也不靠彩色填充：

| 令牌 | 值 | 用途 |
|---|---|---|
| `--bg-deep` | `#0b0c0f` | 侧栏、凹槽、输入框（比内容区更暗 = 下沉） |
| `--bg` | `#101216` | 内容区底色 |
| `--surface` | `#16181d` | 卡片 |
| `--surface-2` | `#1c1f25` | 卡片内的次级块 |
| `--surface-3` | `#242830` | 凸起 / hover / 选中药丸 |

发丝线三级：`--line`（结构性分隔）、`--line-soft`（更弱的行分隔）、`--line-2`（输入框、分段轨道）。

> 关键词是**中性**：不偏棕、也不偏藏青。偏色一旦介入，全站就会蒙上一层滤镜，
> 明度阶梯失效，只能靠加饱和度补层次 —— 那就是"脏"的来源。

## 三条硬规则

### 1. 强调色 ≠ 数字色

`--accent`（`#6aa9ff`）只出现在**可交互与选中态**上：导航选中、链接、焦点环、
主按钮、分段控件选中、数据条。

统计数值一律用正文色 `--text`。只有真正异常时才染色：

- `.stat.warn` / `.stat.err` / `.stat.ok`
- 实例见「系统信息 → 磁盘水位」：`>= 配置的告警阈值` 转 warn，`>= 95%` 转 err

**为什么**：如果所有数字都是品牌色，那"磁盘 90.8%"和"v0.1.0"就长得一样，告警色白定义。

### 2. 强调色与状态色解耦

因为强调色是冷调，琥珀 / 绿 / 红就可以干净地表示状态，不会和品牌色混淆：

| 令牌 | 值 | 在 `--surface` 上的对比度 |
|---|---|---|
| `--ok` | `#46c489` | 8.1:1 |
| `--warn` | `#e2a33c` | 8.1:1 |
| `--err` | `#f0685f` | 5.8:1 |
| `--danger` | `#c9413a` | 实心按钮填充，白字 4.9:1 |

（这也是为什么没有沿用之前的琥珀色品牌色 —— 那样 `--warn` 就失去了语义。）

### 3. 危险动作分级

- **行内**（表格里的「删除」「彻底删除」）→ 安静灰字，hover 才变红
  （CSS 用 `td button.danger` 提权，专治 `button.danger` 的实心红被一列铺满）
- **块级 / 确认**（工具栏的「清空失败任务」、弹窗确认、选择栏批量删除）→ 实心红

## 文字三级

括号内为在 `--surface` / `--surface-3`（最浅的那张底）上的实测对比度。
两级都取"最浅的底"为准，所以同一个灰放在卡片里、输入框里、选中药丸上都过 AA，
不需要逐处调色。

| 令牌 | 值 | 对比度 | 用途 |
|---|---|---|---|
| `--text` | `#eceef2` | 15.0 / 11.7 | 正文、数值 |
| `--text-2` | `#a8aeba` | 8.0 / 6.7 | 次要信息、表头 |
| `--text-3` | `#8a92a0` | 5.7 / 4.7 | 微标签（贴线，不要再降） |

## 字体

**一律系统无衬线**，不用衬线体：

```
--font: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
        'Hiragino Sans GB', 'Microsoft YaHei', Roboto, Helvetica, Arial, sans-serif;
```

排版惯例：

- 卡片标题 `12.5px / 600 / --text-2`
- 统计标签 `11.5px / --text-3`，数值 `26px / 620 / -0.022em`
- 表头 `11.5px / 500 / --text-3 / letter-spacing .03em`
- 数值一律 `font-variant-numeric: tabular-nums`，否则刷新时会抖

## 控件

统一高度：`--ctl-h: 32px`、`--ctl-h-sm: 26px`、`--ctl-h-lg: 38px`。

**按钮三级**：

| 类 | 外观 | 用在哪 |
|---|---|---|
| 默认 | 中性凸起（`--surface-3` + 边界） | 次级动作，占绝大多数 |
| `.primary` | `--accent-fill` 实心 | 一个容器里只该有一个 |
| `.ghost` | 透明 + 边界 | 工具栏 |
| `.danger` | `--danger` 实心（行内自动降级为灰字） | 不可逆动作 |

**分段控件 `.seg`**：凹槽 + 凸起药丸。选中靠「明度跳一档 + 白字加粗」，
不靠上色 —— 同一屏里常有多组分段控件，都上色就吵了。

## 几个易踩的坑（都已在 CSS 里注释）

1. **`:focus-visible` 不要写 `border-radius`** —— 会覆盖元素自己的圆角，
   把药丸和圆形标记压成方角。
2. **瓦片是 `<button>`，必须显式抵消 `height: --ctl-h`**
   （`.photo-grid .tile`、`.file-tile`、`.person-thumb`），
   否则 `aspect-ratio` 派生的高度会被 32px 吃掉，缩略图压成小方块。
3. **`.file-thumb` 要显式 `width: 100%`** —— 让 `aspect-ratio` 靠 flex 拉伸推宽度
   在纵向 flex 里算不出高，纯图标瓦片的缩略图会塌成 28px 横条。
4. **长文件名要 `min-width: 0`** —— flex 子项的 `min-width: auto` 会被 nowrap
   文本撑到 max-content，光靠 `overflow: hidden` 拦不住，文字会压到隔壁瓦片。
5. **`.op` 类要写成 `button.op`** 提权 —— 否则 `.op.danger` 会被 `button.danger` 压过去。
6. **`select` 用自绘箭头**（`appearance: none` + data-URI SVG），
   原生箭头各平台长得都不一样，换掉才跟得上整体质感。

## 无障碍

- 全站 axe-core **violations 0**（10 个页面 + 登录页 + 图片查看器 + 地图视图）
- 空表头用 `<span className="sr-only">操作</span>`，不要留 `<th />`
- 地图容器是 `role="region"` + `aria-label`（有 tabindex 无 role 时 `aria-label` 会被判非法）
- 动效尊重 `prefers-reduced-motion`
- 按钮的 `aria-label` 用 `aria-pressed` 表达选中，不要只靠颜色
