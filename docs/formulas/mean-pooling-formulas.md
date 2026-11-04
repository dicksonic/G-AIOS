# Mean Pooling + L2 归一化：公式与说明

> 对应代码：`E5EmbeddingEngine.meanPoolAndNormalize()`
> 作用：把模型输出的 `[n, 384]` token 向量序列，压缩成一个 [384] 的单位句向量。

---

## 1. 记号

| 符号 | 含义 | 代码中的对应 |
|---|---|---|
| $h_1, h_2, \dots, h_n \in \mathbb{R}^d$ | 模型输出的 n 个 token 向量，每个 d 维（e5-small 中 d = 384） | `tokenVecs[i]`，形状 `[n][384]` |
| $m_i \in \{0, 1\}$ | 掩码：$m_i = 0$ 表示第 i 个位置是 padding | `mask[i]`（attention_mask） |
| $N = \sum_{i=1}^{n} m_i$ | 有效 token 数 | `tokenCount` |
| $d$ | 向量维度，固定 384 | `dim` |

---

## 2. 四步公式

### 步骤 1：Mask 加权和

$$
s = \sum_{i=1}^{n} m_i \cdot h_i
$$

说明：padding 位置的向量被乘 0 抹掉，不参与累加。等价于"只把真实 token 的向量加起来"。

### 步骤 2：均值（Mean Pooling）

$$
\bar{h} = \frac{s}{N} = \frac{1}{N}\sum_{i=1}^{n} m_i \cdot h_i
$$

说明：句向量 = 所有真实 token 向量的"重心"。这就是 "mean pooling" 一词的全部含义：对序列维取平均。

### 步骤 3：L2 范数

$$
\|\bar{h}\|_2 = \sqrt{\sum_{j=1}^{d} \bar{h}_j^{\,2}}
$$

说明：该 d 维向量到原点的欧氏距离。

### 步骤 4：归一化（单位化）

$$
v = \frac{\bar{h}}{\|\bar{h}\|_2}
$$

输出 $v$ 满足性质：**$\|v\|_2 = 1$**（单位向量），方向与 $\bar{h}$ 相同。

---

## 3. 为什么值得做步骤 4（归一化的收益）

余弦相似度的定义：

$$
\cos(a, b) = \frac{\langle a, b \rangle}{\|a\|_2 \cdot \|b\|_2}
$$

当 a、b 都已归一化（长度均为 1）时分母为 1，于是：

$$
\cos(a, b) = \langle a, b \rangle = \sum_{j=1}^{d} a_j b_j
$$

即：**归一化把余弦相似度退化成了点积**。比较两个句向量时只剩一次 O(d) 的内积运算——这也是代码中 `cosine()` 方法只做 `dot += a[i]*b[i]`、无需再除以模长的原因。

语义直觉：归一化后，向量的**方向**代表语义内容，长度信息（能量/置信度）被抹除——而语义比较只关心方向。

---

## 4. 公式 ↔ 代码对照

| 数学 | 代码 |
|---|---|
| $s = \sum m_i h_i$ | `if (mask[i]==0) continue; pooled[j] += tokenVecs[i][j]` |
| $N = \sum m_i$ | `tokenCount++` |
| $\bar{h} = s / N$ | `pooled[j] /= tokenCount` |
| $\|\bar{h}\|_2 = \sqrt{\sum \bar{h}_j^2}$ | `norm += v*v; norm = Math.sqrt(norm)` |
| $v = \bar{h} / \|\bar{h}\|_2$ | `pooled[j] /= norm` |

---

## 5. 矩阵视角（一行写完）

把 n 个 token 向量叠成矩阵 $H \in \mathbb{R}^{n \times d}$，mask 写成向量 $m \in \mathbb{R}^n$，整个函数是两行矩阵运算：

$$
\bar{h} = \frac{m^{\top} H}{\sum_i m_i},
\qquad
v = \frac{\bar{h}}{\|\bar{h}\|_2}
$$

读法：**池化 = mask 加权的行平均**。论文中 "mean pooling over the sequence" / "masked average" 均指此式。

---

## 6. 数据流总览（放进上下文）

```
文本 "查询隐患台账"
  → 分词: n 个 token
  → 模型: H = [h_1, ..., h_n], 每个 h_i ∈ R^384     ← 本函数的输入
  → 步骤1+2: h̄ = mean(H, mask)        ∈ R^384
  → 步骤3+4: v  = h̄ / ‖h̄‖₂           ∈ R^384, ‖v‖₂ = 1
  → 下游: cos(v, anchor) = v · anchor  （锚点相似度检测）
```

---

*文档生成于 G-AIOS 学习过程 · 对应论文：multilingual-e5 官方推荐 mean pooling + 归一化*
