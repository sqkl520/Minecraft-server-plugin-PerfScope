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

/**
 * MSPT 能力位的纯状态机 —— {@code ServerCompat.probeRuntimeAvailability} 的全部判定逻辑。
 *
 * <h2>为什么单独抽一个类</h2>
 * 判定逻辑原本内联在 {@code ServerCompat} 里（76 行），而 {@code ServerCompat} 的
 * {@code <clinit>} 会调 {@code Bukkit.getBukkitVersion()}，在没有服务端实现的测试 JVM 里
 * 必然抛 {@code ExceptionInInitializerError}。结果是这段决定 {@code PAPER_MSPT_AVAILABLE} /
 * {@code PAPER_AVG_TICK_AVAILABLE} 的逻辑<b>零测试</b>，7 条不变量全靠代码纪律维持，
 * 而破坏它们不会崩溃也不会报错——只让能力位或控制台文案变得不诚实（宣称"精确采集"却永远
 * 输出未知、把 Folia 的 global region 局部值标成全服 MSPT）。
 *
 * <h2>为什么禁止 import {@code org.bukkit.*}</h2>
 * 这个类唯一的价值就是能在普通 JUnit JVM 里被加载。任何 Bukkit 类型引用（哪怕只是
 * 一个常量的类型）都会把类加载链拖回需要 {@code Bukkit.server} 的静态初始化，收益归零。
 *
 * <h2>为什么禁止 import {@code cn.perfscope.util.Messages}</h2>
 * {@code CoreMessages} 记录了一条时序承诺：探针只在 {@code Messages.load()} 之后才
 * {@code armRuntimeProbe}。把消息层依赖引进本类，会让它在测试里加载时撞上同一类静态初始化
 * 问题；同时"渲染成哪种语言"属于展示关注点，判定只需要说清"该打哪条日志"。
 * 因此本类只产出 {@link Event}，{@code Messages.get(...)} 的调用与键名仍留在
 * {@code ServerCompat}，调用时机不变。
 *
 * <h2>被测试锁住的 7 条不变量（破坏后都不报错，只是口径变得不诚实）</h2>
 * <ol>
 *   <li><b>只降不升</b>：能力位永不 false → true。</li>
 *   <li><b>两次确认</b>：首次实测全零不立即降级，达到 {@link #ZERO_SAMPLE_CONFIRMATIONS} 次才定案。</li>
 *   <li><b>两级降级</b>：一级（{@code getTickTimes}）恒空但二级（{@code getAverageTickTime}）有值
 *       → 只降一级，二级保持 true，{@code reasonKey} 置 null。</li>
 *   <li><b>两级都空</b>：定案不可用，两个能力位都降，{@code reasonKey = "compat.mspt.tick-times-empty"}。</li>
 *   <li><b>已定案不重复采样</b>：{@code probed == true} 后转移恒等，不再产生新状态、不再打日志。</li>
 *   <li><b>只有原本可用时才解释</b>：Folia 分支按"降级前是否可用"在两条 INFO 之间二选一；非 Folia
 *       的降级/定案日志同理，由 {@link Result#announce} 门控。</li>
 *   <li><b>方法缺失即坏</b>：非 Folia 且 {@code TICK_TIMES_METHOD_PRESENT == false} 时，
 *       即使调用方误传 {@code primarySampleOk == true} 也必须走降级路径。</li>
 * </ol>
 *
 * <h2>与调用方的契约</h2>
 * <ul>
 *   <li>本类<b>不采样</b>：样本由 {@code ServerCompat} 取好传入（反射调用需要 Bukkit 服务端实例）。</li>
 *   <li>本类<b>不写</b> {@code ServerCompat} 的 volatile 字段：调用方拿 {@link Result#state} 一次性写回，
 *       避免"字段改了一半"的中间态。</li>
 *   <li>调用方必须<b>在采样前</b>检查 {@link #probed}（原实现也是如此）：定案后连反射调用都不该再发生。</li>
 *   <li>调用方的重试次数（{@code ServerCompat.PROBE_RETRY_ATTEMPTS}，当前 3）必须
 *       <b>≥ {@link #ZERO_SAMPLE_CONFIRMATIONS}</b>，否则"连续两次确认"这条永远等不到第二次采样，
 *       探针既不会收敛也不会降级。</li>
 * </ul>
 *
 * <p>本类无静态可变状态、不读时钟、不抛异常，可重入且线程安全（不可变）。</p>
 */
final class MsptCapabilityState {

    /**
     * 连续"实测全零"的确认阈值。同一服务端 tick 计时不动是稳定事实，两次采样足以定案；
     * 只采一次会把"采样窗口尚未填充"的瞬态误判成不可用。
     */
    static final int ZERO_SAMPLE_CONFIRMATIONS = 2;

    /** Folia 无全局 tick 时间的消息键（只存键不渲染：语言可在运行期被 reload 切走）。 */
    static final String REASON_FOLIA_NO_GLOBAL_TICK = "compat.mspt.folia-no-global-tick";
    /** 两级 tick API 都实测为空的键。 */
    static final String REASON_TICK_TIMES_EMPTY = "compat.mspt.tick-times-empty";

    /** {@code getTickTimes()} 是否可信（精确 MSPT）。 */
    final boolean msptAvailable;
    /** {@code getAverageTickTime()} 是否可信（只有平均值）。 */
    final boolean avgTickAvailable;
    /** 不可用的消息键；可用时为 null。 */
    final String reasonKey;
    /** 是否已定案（可用或不可用）；定案后不再采样。 */
    final boolean probed;
    /** 连续实测全零次数。 */
    final int zeroSampleStreak;

    private MsptCapabilityState(boolean msptAvailable, boolean avgTickAvailable,
                                String reasonKey, boolean probed, int zeroSampleStreak) {
        this.msptAvailable = msptAvailable;
        this.avgTickAvailable = avgTickAvailable;
        this.reasonKey = reasonKey;
        this.probed = probed;
        this.zeroSampleStreak = zeroSampleStreak;
    }

    /**
     * 类静态初始化完成后的初始状态（{@code ServerCompat} 的 {@code <clinit>} 算出能力位后给出）。
     *
     * @param msptAvailable     一级能力位初值
     * @param avgTickAvailable  二级能力位初值
     * @param reasonKey         初始原因键（可用时为 null）
     */
    static MsptCapabilityState initial(boolean msptAvailable, boolean avgTickAvailable, String reasonKey) {
        return new MsptCapabilityState(msptAvailable, avgTickAvailable, reasonKey, false, 0);
    }

    /**
     * 读回线上 volatile 字段当前值的入口。
     *
     * <p>生产代码只用 {@link #initial} 加 {@link #transition} 就能推完全程，
     * 但 {@code ServerCompat} 的字段是 {@code volatile} 静态变量（reload、其他模块写入、
     * 反射调试都可能让它们偏离状态机推演的结果），所以每轮探针都要从字段重建一次状态，
     * 而不是在插件里另存一个状态机实例——那样会出现"两份真相"。</p>
     */
    static MsptCapabilityState current(boolean msptAvailable, boolean avgTickAvailable,
                                       String reasonKey, boolean probed, int zeroSampleStreak) {
        return new MsptCapabilityState(msptAvailable, avgTickAvailable, reasonKey, probed, zeroSampleStreak);
    }

    /**
     * 纯转移：给定本轮输入，返回新状态 + 该打哪条日志。
     *
     * <p>入参全部由调用方取好，本方法只做判定，不做 IO、不读全局。分支顺序与原实现
     * （{@code ServerCompat#probeRuntimeAvailability}）逐行对应，注释里标了每段为什么不能合并。</p>
     *
     * @param isFolia                必须是<b>调用方传入</b>的判定结果，不能改回读
     *                               {@code ServerCompat.isFolia()}（读静态 {@code TYPE}）：两者正常时恒等，
     *                               一旦不等，Folia 会走实测路径，{@code reasonKey} 从
     *                               {@code folia-no-global-tick} 漂成 {@code tick-times-empty}，
     *                               更糟的是若 {@code getAverageTickTime()} 恰好返回正数，就会落进
     *                               DEGRADED 分支，把 global region 局部值标成"精确"。
     * @param tickTimesMethodPresent 静态探测：{@code getTickTimes()} 方法是否存在
     * @param avgTickMethodPresent   静态探测：{@code getAverageTickTime()} 方法是否存在
     * @param primarySampleOk        本轮一级实测是否有正样本（方法不存在时应传 false）
     * @param avgTickOk              本轮二级实测是否 &gt; 0（方法不存在时应传 false；
     *                               即使误传 true，本方法也会按方法缺失覆盖掉）
     */
    Result transition(boolean isFolia, boolean tickTimesMethodPresent, boolean avgTickMethodPresent,
                      boolean primarySampleOk, boolean avgTickOk) {
        if (probed) {
            // 不变量 5：已定案就不再采样、不再改口径。原实现的顶部 early-return 等价物。
            return new Result(this, Event.NONE, false);
        }

        if (isFolia) {
            // 原实现：先置位再读 wasAvailable；两者互不影响，顺序保留只为对照方便。
            boolean wasAvailable = msptAvailable || avgTickAvailable;
            MsptCapabilityState next = new MsptCapabilityState(
                false, false, REASON_FOLIA_NO_GLOBAL_TICK, true, zeroSampleStreak);
            // 不变量 6：只有"此前被误判为可用"时才需要解释，否则只补一句 scope note（都是 INFO）。
            return new Result(next,
                wasAvailable ? Event.FOLIA_EXPLAINED : Event.FOLIA_QUIET, true);
        }

        // 一级口径（只看 PAPER_MSPT_AVAILABLE）——与下面两级口径的 wasAvailable 语义不同，
        // 它们决定打 degraded-to-avg 还是 marked-unavailable，绝不能合并。
        boolean wasPrimaryAvailable = msptAvailable;
        boolean bad = !tickTimesMethodPresent || !primarySampleOk;
        boolean avgBad = !avgTickMethodPresent || !avgTickOk;

        if (!bad) {
            // 一级实测有非零样本 → 能力确认并定案。
            // 注意：这里**不写** msptAvailable/avgTickAvailable（不变量 1：只降不升），
            // 也不看 avgBad：一级成功就够定案，二级读数本轮如何不影响已确认的一级能力。
            MsptCapabilityState next = new MsptCapabilityState(
                msptAvailable, avgTickAvailable, null, true, 0);
            return new Result(next, Event.CONFIRMED_EXACT, false);
        }

        // 一级不可用（全零或方法缺失）。可能是"采样窗口尚未填充"的瞬态——
        // 统一要求连续 ZERO_SAMPLE_CONFIRMATIONS 次异常才定案（不变量 2）。
        int streak = zeroSampleStreak + 1;
        if (streak < ZERO_SAMPLE_CONFIRMATIONS) {
            MsptCapabilityState next = new MsptCapabilityState(
                msptAvailable, avgTickAvailable, reasonKey, false, streak);
            return new Result(next, Event.NONE, false);
        }

        // 走到这里就已经定案了（原实现：runtimeProbed = true 在两级分支判定之前），
        // 下面的两个分支都必须带 probed = true，否则 DEGRADED / MARKED 会多跑一轮采样
        // （多一条日志 + 多 5 秒延迟）。
        if (!avgBad) {
            // 不变量 3：一级恒空、二级有值 → 只降一级，二级保持原值，reasonKey 依契约置 null。
            MsptCapabilityState next = new MsptCapabilityState(
                false, avgTickAvailable, null, true, streak);
            return new Result(next, Event.DEGRADED_TO_AVG, wasPrimaryAvailable);
        }

        // 不变量 4：两级都空 → 双降 + 记原因键。
        boolean wasAvailable = msptAvailable || avgTickAvailable;
        MsptCapabilityState next = new MsptCapabilityState(
            false, false, REASON_TICK_TIMES_EMPTY, true, streak);
        return new Result(next, Event.MARKED_UNAVAILABLE, wasAvailable);
    }

    /**
     * {@code getTickTimes()} 的返回值里是否存在正样本。
     *
     * <p>null / 空数组 / 全零都算"没有数据"——这是"方法在但数据恒空"与"数据真的可用"的唯一区分点。
     * 负数不算正样本（tick 耗时不可能为负，出现负值说明该服务端实现异常，按不可信处理）。</p>
     */
    static boolean hasPositiveTickSample(long[] arr) {
        if (arr == null || arr.length == 0) {
            return false;
        }
        for (long t : arr) {
            if (t > 0L) {
                return true;
            }
        }
        return false;
    }

    /** 该打哪条控制台行。渲染（{@code Messages.get}）留在 {@code ServerCompat}，键名不变。 */
    enum Event {
        /** 未定案（首轮全零，等下一次确认）：不打日志。 */
        NONE,
        /** Folia 且降级前<b>不可用</b>：打"原因 + TPS scope note"。 */
        FOLIA_QUIET,
        /** Folia 且降级前<b>可用</b>（被误判过）：打"原因 + MSPT marked unavailable"。 */
        FOLIA_EXPLAINED,
        /** 一级实测成功、能力确认：不打日志（原实现也没有这条日志）。 */
        CONFIRMED_EXACT,
        /** 一级降级、二级仍可用：{@code announce} 为 true 时打 degraded-to-avg。 */
        DEGRADED_TO_AVG,
        /** 两级都空：{@code announce} 为 true 时打 marked-unavailable。 */
        MARKED_UNAVAILABLE
    }

    /** 一次转移的结果：新状态 + 该打哪条日志 + 是否真的该打。 */
    static final class Result {
        final MsptCapabilityState state;
        final Event event;
        /**
         * 是否应当打印 {@link #event} 对应的控制台行。
         *
         * <p>原实现的两条降级日志分别由 {@code wasPrimaryAvailable}（一级口径）与
         * {@code wasAvailable}（两级口径）门控：本来就不可用时再喊一次"已降级"只是噪音。
         * {@link Event} 只回答"哪条行"，是否打由本字段回答，两者一起才完整覆盖原行为。</p>
         */
        final boolean announce;

        Result(MsptCapabilityState state, Event event, boolean announce) {
            this.state = state;
            this.event = event;
            this.announce = announce;
        }

        @Override
        public String toString() {
            return "Result[event=" + event + ", announce=" + announce + ", state=" + state + "]";
        }
    }

    @Override
    public String toString() {
        return "MsptCapabilityState[mspt=" + msptAvailable + ", avg=" + avgTickAvailable
            + ", reason=" + reasonKey + ", probed=" + probed + ", streak=" + zeroSampleStreak + "]";
    }
}
