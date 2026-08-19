# Index cost split — Gradle Tooling API vs source parsing

| repo | build extract (ms) | source parse (ms) | source share |
|---|---|---|---|
| trading-candles | 9156 | 471 | 5% |
| trading-core | 15637 | 937 | 6% |
| trading-ops | 2129 | 126 | 6% |
| trading-platform-libs | 6679 | 435 | 6% |
| trading-product-a | 3705 | 38 | 1% |
| trading-product-b | 2417 | 30 | 1% |
| **total** | **39723** | **2037** | **5%** |
