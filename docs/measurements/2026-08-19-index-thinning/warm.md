# Index cost split — Gradle Tooling API vs source parsing

| repo | build extract (ms) | source parse (ms) | source share |
|---|---|---|---|
| trading-candles | 2463 | 461 | 16% |
| trading-core | 8544 | 925 | 10% |
| trading-ops | 1236 | 133 | 10% |
| trading-platform-libs | 4339 | 429 | 9% |
| trading-product-a | 2685 | 40 | 1% |
| trading-product-b | 2507 | 34 | 1% |
| **total** | **21774** | **2022** | **8%** |
