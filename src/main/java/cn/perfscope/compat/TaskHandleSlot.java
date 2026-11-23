/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package cn.perfscope.compat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 「恰好一个定时任务」的句柄槽位——把 {@code PerfScopeCommand} 里已验证过的 monitor 任务
 * 启停范式抽出来，供全部四个服务（采集 / 告警 / 日报 / 自动报告与看门狗）复用。
 *
 * <h2>为什么必须是这个形状，而不是「先 stop 再赋值」</h2>
 *
 * <p>四个服务的 {@code start()} 首行都是 {@code stop()}，注释里写着「先停后启，幂等」。
 * 这句话<b>只在单线程下成立</b>。两个线程同时进来时（Folia 上两个 region 线程各跑一次
 * {@code /perfscope reload}），交错是这样的：</p>
 *
 * <pre>
 * 线程 A: stop() 抢到旧句柄并取消 → start() 里 new 任务，写字段 = H_A
 * 线程 B:             stop() 读到 null（A 的写未发布，或先于 A 的写）→ 无操作
 *                     start() 读到 null（旧字段非 volatile，跨线程可能长期读旧值）
 *                     → new 任务，写字段 = H_B，覆盖 H_A
 * </pre>
 *
 * <p>结果：H_A 与 H_B <b>同时活着</b>，而字段只记得 H_B。之后 {@code stop()} 只能取消 H_B，
 * H_A 永久泄漏——每一条告警被推两次、冷却表被推进两次、历史样本被写两份。这正是
 * {@code PerfScopeCommand} 在 9B-5 修掉的同一形状（那里是"一次开却有两份 ActionBar 刷新"）。</p>
 *
 * <h2>两条不变量</h2>
 *
 * <ol>
 *   <li><b>安装只有一个赢家</b>：{@link #start(Supplier)} 用 CAS 门闩串行化"创建"区段，
 *       再用 {@code installed.compareAndSet(null, created)} 把安装变成单选。输家<b>自己取消</b>
 *       刚建出来的任务，绝不留下无人持有的句柄。</li>
 *   <li><b>取消只有一个赢家</b>：{@link #stop()} 用 {@code getAndSet(null)} 抢占，
 *       抢到的那一方才有权 cancel——同一句柄不会被两个线程各取消一次（重复 cancel 虽无害，
 *       但"谁停的"会变得不可归因）。</li>
 * </ol>
 *
 * <p>非 volatile 普通字段还有一个独立的可见性问题：Folia 上跨 region 线程读可能长期读旧值，
 * 于是线程 B 会看到"字段仍是 null"并在 A 已经装好任务之后再装一个。{@link AtomicReference}
 * 同时解决可见性与原子的读-判-写。</p>
 *
 * <h2>为什么返回句柄而不是 boolean</h2>
 *
 * <p>调用方（告警 / 日报）需要区分「任务在跑」与「调度器返回了 noop 句柄」——后者必须打
 * severe，否则控制台显示"已启动"而功能实际停摆（{@code f-s01-err-01}）。
 * {@link #start(Supplier)} 因此返回<b>调用返回后真正生效的句柄</b>：本线程装的、
 * 别人已装的、或工厂给出的 noop（第三个调用方据此判断 {@code isActive()}）。
 * 只有"另一线程正在安装、结果尚不可知"这一种情形返回 {@code null}。</p>
 *
 * <p><b>本类为什么可以脱离 Bukkit 单测</b>：它只持有 {@link SchedulerAdapter.TaskHandle}
 * 引用，不读任何 {@code SchedulerAdapter} 静态字段（{@code FOLIA}）。按 JLS 12.4.1，
 * 加载嵌套/引用类型不会初始化引用方，所以测试 JVM 里 {@code Class.forName} 两个类都成功——
 * 与 {@code TaskHandleContractTest} 是同一条已验证的路径。</p>
 */
public final class TaskHandleSlot {

    /** 当前句柄（null = 未运行）。所有写入都经 CAS / getAndSet，绝不留 read-modify-write。 */
    private final AtomicReference<SchedulerAdapter.TaskHandle> installed = new AtomicReference<>();

    /**
     * 「正在安装」门闩。
     *
     * <p>{@code SchedulerAdapter.runGlobalTimer} 返回句柄之前任务<b>已经提交给调度器</b>，
     * 这段时间里 {@link #installed} 仍是 null。没有门闩时两个线程会各建一份任务，
     * 而 CAS 只能保证"字段里只留一个"——另一份要在输家意识到自己输了之后才被取消，
     * 期间它是真的在跑的。门闩把创建区段串行化，第二个线程直接让位。</p>
     */
    private final AtomicBoolean installing = new AtomicBoolean(false);

    /** 当前句柄；null = 未运行。只读探针（诊断/状态展示用），不参与启停裁决。 */
    public SchedulerAdapter.TaskHandle current() {
        return installed.get();
    }

    /** 是否有句柄已安装（不代表任务真的还能跑——句柄可能已被外部取消）。 */
    public boolean isRunning() {
        return installed.get() != null;
    }

    /**
     * 幂等启动：已有一个在跑就复用它，绝不叠加第二个。
     *
     * @param factory 真正创建任务的工厂（调用方在里面调 {@code SchedulerAdapter.runXxxTimer}）
     * @return 调用返回后生效的句柄；{@code null} = 另一线程正在安装，结果尚不可知
     */
    public SchedulerAdapter.TaskHandle start(Supplier<SchedulerAdapter.TaskHandle> factory) {
        if (!installing.compareAndSet(false, true)) {
            // 另一线程正在创建：它装好的任务就是本线程想要的（同一份配置在 reload 锁内）
            return null;
        }
        try {
            SchedulerAdapter.TaskHandle existing = installed.get();
            if (existing != null) {
                return existing; // 双检：拿门闩期间别人已装好
            }
            SchedulerAdapter.TaskHandle created = factory.get();
            if (created == null || !created.isActive()) {
                // 调度失败（Folia fail-safe 的 noop / 调度器拒绝）：不安装，
                // 保住不变量「句柄非空 ⇒ 创建时任务真的装上了」，让调用方看得见失败并能在下次 reload 重试
                return created;
            }
            if (!installed.compareAndSet(null, created)) {
                // 安装失败 = 别人抢先装好（门闩在理论上不该让这发生，CAS 是第二道保险）：
                // 取消自己刚建的任务，避免留下无人可取消的野定时器
                created.cancel();
                return installed.get();
            }
            return created;
        } finally {
            installing.set(false);
        }
    }

    /**
     * 停止：抢占并取消当前句柄（幂等、可并发调用）。
     *
     * @return {@code true} 表示本次调用真的停掉了一个任务（调用方可据此避免重复打日志）
     */
    public boolean stop() {
        SchedulerAdapter.TaskHandle claimed = installed.getAndSet(null);
        if (claimed == null) {
            return false;
        }
        claimed.cancel();
        return true;
    }
}
