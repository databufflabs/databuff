# databuff-proxy 双写稳定性验证

[databuff-proxy](https://github.com/databufflabs/databuff-proxy) 把 SkyWalking Agent 流量同时转给多个后端。本文是压测与稳定性结论；迁移步骤见 [从 SkyWalking 迁移到 DataBuff](./from-skywalking-to-databuff.md) 方案 B。

## 测的是什么

```
业务流量 ──▶ proxy ──▶ SkyWalking
                 └──▶ DataBuff
```

压测强度：QPS = **35**（约每秒 5800 次转发 × 2 路）  
实际入库：DataBuff 约 **1.13 万 span/s** · SkyWalking 约 **5805 segment/s**

| 指标 | 结果 |
|------|------|
| 占用 CPU | **0.89 核**（不到 1 核，不是整机使用率） |
| 内存 | **~47 MB**，过夜不涨 |
| 连续跑 | **10 小时+**，进程不崩 |

## 一、资源是否持续稳定

近 6 小时监控（proxy 进程 CPU / 内存；同机网络收发）：

![CPU（进程）约 5% ≈ 0.8 核 / 16 核机](../images/proxy-stability-cpu-6h.png)

![内存 RSS（进程）约 47–60 MB](../images/proxy-stability-mem-6h.png)

![接收网络流量（主机）](../images/proxy-stability-net-rx-6h.png)

![输出网络流量（主机）](../images/proxy-stability-net-tx-6h.png)

**结论：** CPU 一直不到 1 核，内存几乎不变，网络收发持续且平稳。

## 二、异常场景是否正常

| 场景 | 表现 | 结果 |
|------|------|------|
| 一路后端挂掉 | 坏的一路熔断；另一路继续写；proxy 不挂 | 正常 |
| 手动关掉一路写入 | 该路停写；另一路不受影响；再打开可恢复 | 正常 |
| 高流量持续压 | 两路不丢包、不报错 | 正常 |
| 长时间不停机 | 10 小时+ 仍在转发；内存不涨 | 正常 |
| 配置重启后恢复 | 可正常拉起并继续双写 | 正常 |

**结论：** 异常时该断的断、该留的留，proxy 自己不跟着挂。

## 三、最终结论

| 检查项 | 结果 |
|--------|------|
| 资源持续稳定 | 通过 |
| 异常场景表现正常 | 通过 |

**可以正式使用：** 双写可靠，资源占用可控。

> 补充：两侧入口调用数对齐（service-a～d ≥ 99.98%），非本报告重点。

## 相关链接

- [从 SkyWalking 迁移到 DataBuff](./from-skywalking-to-databuff.md)（方案 B）
- [databuff-proxy 仓库与 README](https://github.com/databufflabs/databuff-proxy)
- [博客 · 双写稳定性验证](/blog/zh/databuff-proxy-dual-write-stability/)
- [完整 Markdown 报告（仓库内）](https://github.com/databufflabs/databuff-proxy/blob/main/docs/reports/proxy-fanout-verify-20260730-091800.md)
