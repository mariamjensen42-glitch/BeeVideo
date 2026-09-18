# 配置装载跑在会话自己的作用域里，而不是调用方的作用域

设置页点「加载」用的是 `rememberCoroutineScope()` —— 用户切走页面，它就没了。
装载以前跑在那个作用域里，于是取消会落在「`LOADING` 已发布、终态还没写」的中间。
后果是状态**永久停在 LOADING**：首页一直转圈，而且无法自愈 —— `restore()` 被
`restored` 守卫着，`applyConfig` 早就把它置成 true 了。没有崩溃、没有日志。

三个可选做法：
1. **取消时把状态还原**成进入本次装载之前的值（改动最小；已先落地，见
   `VodContentRepository.load` 的 `CancellationException` 分支）；
2. 让命令**不可取消**；
3. 命令跑在**会话自己的作用域**里。

决定：**3**。`VodContentRepository` 持有
`sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`，
每次命令用 `sessionScope.async { commands.withLock { … } }.await()` 提交。

**为什么是 `async` 而不是 `withContext`**：调用方取消会打断 `withContext` 的等待，
而 `async` 的子任务挂在 `sessionScope` 上 —— 父任务是否被取消与它无关。
所以调用方取消只是**不再等**，装载照常跑完。用户按下「加载」这个意图因此被兑现，
而不是因为切了个 tab 就无声作废。

**为什么 1 还留着**：它是**兜底**。会话作用域本身被取消（进程要没了）时仍会走到，
而「状态机必须以终态收场」这条不变量不该依赖调用方是谁。它由
`VodContentRepositoryTest` 覆盖。

## 代价与已知缺口

- **「仓储持有一个 CoroutineScope」是不常见的形状**，读到这里的人会问为什么 ——
  这就是这份记录存在的理由。它换来的是「命令的生命周期由会话决定，不由调用方决定」。
- 同一把 `commands` 互斥锁顺带修掉了两处竞态：失败态「重试」连点起的多个
  `applyConfig` 互相覆盖终态；「清除」不受 `applying` 约束、可以和装载并行。
- ⚠️ **互斥本身没有单测**。要断言"某条命令没有开始"只能靠超时（不稳定）或在生产
  代码里开测试钩子（不值得）。既有那条用例断言的是**结果**（清除之后来源确实没了），
  不是互斥。互斥由代码与注释负责 —— 写在这里，是为了避免它给人"已经测过了"的错觉。
