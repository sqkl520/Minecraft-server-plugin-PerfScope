/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.config;

import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.ConfigFixtures;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CPU 单值百分比阈值 + 迟滞带的校验路径。
 *
 * <p><b>与本目录另一类的分工</b>：{@code ThresholdPairZeroValidationTest} 锁的是
 * <b>成对的 int 阈值</b>（players / entities / chunks / memory 的 warning+critical 对），
 * 它关心的是「0 让高值差指标恒真」「上下界 1..100 或 1..10_000_000」「倒置要整体回退」。
 * 本类锁的是另一套完全不同的机制：</p>
 * <ul>
 *   <li>{@code cpu.process_warning_threshold} / {@code cpu.system_warning_threshold} 是
 *       <b>单值</b>百分比（没有 critical 配对），走的是 {@code sanitizePercent} 而不是
 *       {@code sanitizeIntPair}，合法域是 <b>半开半闭</b>的 {@code (0, 100]}；</li>
 *   <li>{@code cpu_hysteresis_margin} 是<b>迟滞带宽度</b>，除了自身的 {@code [0, 50]} 数值区间，
 *       还要与上面两个阈值做<b>交叉校验</b>（{@code margin < min(process, system)}），
 *       且回退值不是固定默认值而是 {@code min(5.0, min/2)}。</li>
 * </ul>
 *
 * <p><b>为什么这组断言此前是空的</b>：审计实测——把 {@code sanitizePercent} 里的
 * {@code v > 100.0d} 整道闸拆掉（cpu 阈值写 150 直接放行），现有 520 个测试全绿；
 * 而 {@code margin >= cpuMinThreshold} 这条交叉校验同样没有任何断言。两者的后果都不是崩溃，
 * 而是<b>静默失效</b>：阈值 150 让 processCpuLoad 永远到不了，这条 CPU 告警等于被关掉；
 * margin 不小于阈值则退出线 {@code threshold - margin} 非正，迟滞在运行时被
 * {@code MetricsCollector.latched} 的 {@code exit <= 0} 兜底<b>降级为无迟滞</b>
 * （不会锁死，但那 5 个百分点缓冲用户以为有、其实没有，且日志里一个字都不说）。
 * 这类「配置校验通过 = 功能静默降级」的缺陷只能靠直调 {@code validateThresholds}
 * 并断言 <b>值被真实写回</b> 来锁住——只反射读常量是锁不住的喵。</p>
 *
 * <p><b>夹具</b>：全部装配走 {@link ConfigFixtures}（{@code validConfig()} 的 CPU 基线是
 * process=80.0 / system=90.0 / margin=5.0），告警文案通过
 * {@link ConfigFixtures#warningsOf} 捕获。注意 {@code validConfig()} 的每一项都合法这一前提——
 * 否则别的字段的回退 warning 会混进来，让「回退不能静默」这类断言变成假阳性喵。</p>
 */
class CpuThresholdAndHysteresisValidationTest {

    /** 本类专用 logger 名，避免与其他配置测试串日志。 */
    private static final String LOGGER = "pscope-cpu-threshold-hysteresis-test";

    /** double 断言容差：迟滞带是百分点，1e-9 足够区分 2.5 / 2.4999999 这类回退算式差异。 */
    private static final double EPS = 1e-9;

    /**
     * 文案层必须真的带 {@code config.*} 模块表。
     *
     * <p>不初始化时 {@code Messages.get} 只回显键名、<b>不做 {@code {0}} 替换</b>，
     * 于是「warning 必须带键名」这类断言会因为「键名压根没被拼进去」而失败——
     * 失败信息会指向断言本身，而不是指向真正的原因。见 {@link AlertFixtures#initMessages()}。</p>
     */
    @BeforeAll
    static void initMessages() {
        TestObjects.assertReflectionUsable();
        AlertFixtures.initMessages();
    }

    /** 跑一次真实校验，结果写回同一个 {@code cfg}。 */
    private static ConfigManager.ConfigData validated(ConfigManager.ConfigData cfg) {
        ConfigFixtures.validate(ConfigFixtures.manager(LOGGER), cfg);
        return cfg;
    }

    /** 只改 process 一项，其余保持合法基线，返回校验后的配置。 */
    private static ConfigManager.ConfigData withProcess(double process) {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.cpu_processWarningThreshold = process;
        return validated(cfg);
    }

    /** 只改 system 一项。 */
    private static ConfigManager.ConfigData withSystem(double system) {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.cpu_systemWarningThreshold = system;
        return validated(cfg);
    }

    /** 只改 margin 一项（阈值保持 80/90 → cpuMinThreshold = 80）。 */
    private static ConfigManager.ConfigData withMargin(double margin) {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.cpu_hysteresisMargin = margin;
        return validated(cfg);
    }

    /** 三个 CPU 项一起设，用于交叉校验用例。 */
    private static ConfigManager.ConfigData withCpu(double process, double system, double margin) {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.cpu_processWarningThreshold = process;
        cfg.cpu_systemWarningThreshold = system;
        cfg.cpu_hysteresisMargin = margin;
        return validated(cfg);
    }

    // ====================================================================================
    // A. sanitizePercent 的边界（合法域是 (0, 100]，两端开闭不对称）
    // ====================================================================================

    /**
     * 0 在下界<b>开区间</b>之外 → 回退 80.0。
     *
     * <p>为什么 0 必须非法：判据是 {@code processCpuLoad > threshold}，阈值 0 使空载服务器
     * （读数 0.0，MetricsCollector 明确允许 0 作为合法读数）之上的一切都算「CPU 偏高」，
     * 告警恒真。用户想关 CPU 告警应该用 {@code cpu.enabled: false}。</p>
     */
    @Test
    void processZeroFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withProcess(0.0);
        assertEquals(80.0, cfg.cpu_processWarningThreshold, EPS,
            "process=0 若被放行，`processCpuLoad > 0` 对任何非零读数都成立，CPU 告警恒真；"
                + "这是「最灵敏」直觉的反面，必须回退默认 80.0");
    }

    /** 负数同样在开区间外 → 回退 80.0（负阈值让告警无条件恒真）。 */
    @Test
    void processNegativeFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withProcess(-1.0);
        assertEquals(80.0, cfg.cpu_processWarningThreshold, EPS,
            "process=-1 若被放行，`processCpuLoad > -1` 对所有读数恒真，CPU 告警永久亮着；"
                + "且负阈值在退出线计算里会得到更荒谬的结果");
    }

    /**
     * 100 是上界<b>闭区间</b>端点 → 必须原样保留。
     *
     * <p>这条防「把 {@code v <= 100} 收紧成 {@code v < 100}」的过度收紧：100 是语义自洽的
     * 「只有满载才告警」配置，拦掉它属于 breaking change 且没有收益。</p>
     */
    @Test
    void processHundredIsLegal() {
        ConfigManager.ConfigData cfg = withProcess(100.0);
        assertEquals(100.0, cfg.cpu_processWarningThreshold, EPS,
            "100 是百分比域闭区间上端（`only alert at full load` 是自洽配置）；"
                + "把它当非法拦掉会让合法用户的阈值被静默改成 80.0");
    }

    /** 刚过 100（100.0001）即非法 → 回退 80.0。上界的闭性必须是「闭在 100.0 这一点」。 */
    @Test
    void processJustAboveHundredFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withProcess(100.0001);
        assertEquals(80.0, cfg.cpu_processWarningThreshold, EPS,
            "100.0001 若被放行说明上界判定用了 `>=` 之外的口径；它会随下界抖动一起漂移，"
                + "属于「几乎永远不触发」的近恒假配置");
    }

    /**
     * <b>核心靶点</b>：process=101 → 回退 80.0。
     *
     * <p>审计实测：拆掉 {@code sanitizePercent} 里的 {@code v > 100.0d} 这一道闸，
     * 现有 520 个测试全绿。原因是 101 越界之后没有任何下游断言——它不崩溃、不 warning，
     * 只是让 {@code processCpuLoad > 101} 永远为假，把本来该有的 CPU 告警<b>静默关掉</b>。
     * 本断言是这条闸唯一的守卫。</p>
     */
    @Test
    void processHundredAndOneFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withProcess(101.0);
        assertEquals(80.0, cfg.cpu_processWarningThreshold, EPS,
            "CPU 阈值 101 若被放行，processCpuLoad（0-100）永远到不了，这条告警等于被静默关掉；"
                + "拆掉 `v > 100.0d` 这道闸后唯一会红的断言就是本行");
    }

    /** 150（审计报告里用的那个值）→ 回退 80.0。 */
    @Test
    void processHundredFiftyFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withProcess(150.0);
        assertEquals(80.0, cfg.cpu_processWarningThreshold, EPS,
            "CPU 阈值 150 若被放行，processCpuLoad 永远到不了，这条告警等于被静默关掉；"
                + "150 也可能是用户把「毫秒」当「百分比」写错，回退让错误可见");
    }

    /** NaN → 走非有限值分支回退 80.0（NaN 的比较恒假，会让阈值判定全部失效）。 */
    @Test
    void processNaNFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withProcess(Double.NaN);
        assertEquals(80.0, cfg.cpu_processWarningThreshold, EPS,
            "NaN 若被放行，`processCpuLoad > NaN` 恒为假，CPU 告警永久失效且没有任何报错——"
                + "这是最难排查的一种「配置写错」");
    }

    /** +Infinity → 回退 80.0（与 NaN 同理：阈值永远到不了）。 */
    @Test
    void processPositiveInfinityFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withProcess(Double.POSITIVE_INFINITY);
        assertEquals(80.0, cfg.cpu_processWarningThreshold, EPS,
            "Infinity 若被放行，阈值永远到不了，CPU 告警被静默关掉；"
                + "非有限值必须与越界值走同一条回退路径（否则 `isFinite` 检查形同虚设）");
    }

    /** 0.1 极小但合法 → 保留。防「下界写成某个经验值（比如 1）」的过度收紧。 */
    @Test
    void processTinyPositiveIsLegal() {
        ConfigManager.ConfigData cfg = withProcess(0.1);
        assertEquals(0.1, cfg.cpu_processWarningThreshold, EPS,
            "0.1 满足 0 < v <= 100，必须原样保留；把下界写成 1 之类会让「任何负载都告警」的"
                + "激进但自洽配置被静默改写");
    }

    /**
     * system 的四个关键边界：0 / 100 / 101 / NaN。
     *
     * <p>system 走的是同一个 {@code sanitizePercent}，但<b>默认值不同（90.0）</b>。
     * 若有人把两处调用的默认值抄错（比如都传 80.0），只有本用例会红——
     * 那时用户写错 system 阈值会被改成 80.0，告警比用户配置的更灵敏，属静默行为偏移。</p>
     */
    @Test
    void systemSameBoundariesAreSanitizedWithItsOwnDefault() {
        assertEquals(90.0, withSystem(0.0).cpu_systemWarningThreshold, EPS,
            "system=0 若被放行，系统 CPU 告警恒真；且回退必须用 system 自己的默认 90.0 而不是 80.0");
        assertEquals(90.0, withSystem(101.0).cpu_systemWarningThreshold, EPS,
            "system=101 若被放行，系统 CPU 告警永远不触发——与 process 一样必须拦");
        assertEquals(90.0, withSystem(Double.NaN).cpu_systemWarningThreshold, EPS,
            "system=NaN 若被放行，告警判定恒假；非有限值必须回退");
        assertEquals(100.0, withSystem(100.0).cpu_systemWarningThreshold, EPS,
            "system=100 是闭区间上端，必须保留——上界对两个键的闭性必须一致");
    }

    /** process 非法不影响 system，反之亦然：两个键各自独立走一遍 sanitizePercent。 */
    @Test
    void processAndSystemAreSanitizedIndependently() {
        ConfigManager.ConfigData cfg = withCpu(150.0, 55.0, 5.0);
        assertEquals(80.0, cfg.cpu_processWarningThreshold, EPS,
            "process=150 必须回退 80.0；若两个键被一起判死，system 的 55.0 也会被改掉");
        assertEquals(55.0, cfg.cpu_systemWarningThreshold, EPS,
            "system=55 合法，不能被 process 的越界连坐回退成 90.0——"
                + "那是把「用户配的灵敏阈值」换成「迟钝默认值」，行为变化比原错误更难发现");
    }

    // ====================================================================================
    // B. 迟滞带的数值区间 [0, 50]（0 合法，50 合法，50.5 非法）
    // ====================================================================================

    /**
     * margin = 0 → <b>合法保留</b>（关闭迟滞是刻意允许的旧行为）。
     *
     * <p>这条防的是「把 0 当非法拦掉」的过度收紧：代码注释明确写了「0 恒小于正阈值，不拦」，
     * 且 {@code MetricsCollector.latched} 对 margin=0 走的是无迟滞判据（{@code value > threshold}），
     * 语义自洽。若被拦成 5.0，等于<b>替用户偷偷打开一个他没要的迟滞</b>。</p>
     */
    @Test
    void marginZeroIsLegalBecauseItDisablesHysteresis() {
        ConfigManager.ConfigData cfg = withMargin(0.0);
        assertEquals(0.0, cfg.cpu_hysteresisMargin, EPS,
            "margin=0 是「关闭迟滞」的合法取值（注释明示），被拦掉会变成替用户偷偷打开迟滞 5.0——"
                + "告警从此要跌到阈值-5 才消失，属于静默行为变更");
    }

    /**
     * margin = -1 → 回退 5.0。
     *
     * <p>负迟滞会让退出线<b>高于</b>进入线：{@code exit = threshold + 1}。
     * 进入 latched 后判据是 {@code value >= exit}，于是闩锁只在 {@code threshold+1} 以上保持，
     * 一跌回阈值附近立刻脱落——迟滞从「延迟退出」反转成「阈值上方 1 个百分点内自由翻转」，
     * 正好是迟滞本来要消除的那种抖动。被区间闸拦下是最省事的处理。</p>
     */
    @Test
    void marginNegativeFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withMargin(-1.0);
        assertEquals(ConfigManager.CPU_HYSTERESIS_DEFAULT, cfg.cpu_hysteresisMargin, EPS,
            "margin=-1 若被放行，退出线 threshold-(-1) 反而高于进入线，迟滞反转成"
                + "「阈值上方 1 个百分点内自由翻转」，正是它本该消除的抖动；必须回退默认 5.0");
    }

    /**
     * margin = 50 且阈值 80/90 → <b>合法保留 50.0</b>。
     *
     * <p>50 是区间<b>闭</b>上端（拦截条件是 {@code > 50}），且交叉校验要求
     * {@code margin < cpuMinThreshold}：50 &lt; 80 成立，所以两道闸都放行。
     * 这条同时锁住「上界闭合方向」——若上界写成 {@code >= 50}，这里会变成 5.0。</p>
     */
    @Test
    void marginFiftyPassesBothTheRangeAndTheCrossCheck() {
        ConfigManager.ConfigData cfg = withMargin(50.0);
        assertEquals(50.0, cfg.cpu_hysteresisMargin, EPS,
            "50 是 [0, 50] 的闭端点，且 50 < min(80, 90) 故交叉校验也通过；"
                + "被改成 5.0 说明上界判定被写成了 `>= 50`");
    }

    /** margin = 50.5 → 回退 5.0（越过上界；此值也不可能通过交叉校验，但区间闸在先）。 */
    @Test
    void marginJustAboveFiftyFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withMargin(50.5);
        assertEquals(ConfigManager.CPU_HYSTERESIS_DEFAULT, cfg.cpu_hysteresisMargin, EPS,
            "margin=50.5 越过 [0, 50] 上界必须回退；放行会把退出线压到阈值一半以下，"
                + "一次真实的持续过载会永远退不出去（告警挂着不退比误报更糟）");
    }

    /** margin = NaN → 回退 5.0（NaN 的比较全假，会让迟滞逻辑静默失效）。 */
    @Test
    void marginNaNFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withMargin(Double.NaN);
        assertEquals(ConfigManager.CPU_HYSTERESIS_DEFAULT, cfg.cpu_hysteresisMargin, EPS,
            "margin=NaN 若被放行，`margin < 0` 与 `margin > 50` 双双为 false，区间闸被整个绕过；"
                + "进入 latched 后 exit=threshold-NaN=NaN，`exit <= 0` 也为 false，"
                + "带内保持 `value >= NaN` 恒假——闩锁每条样本都立刻脱落，迟滞等于不存在");
    }

    /** margin = 51 且阈值合法 → 回退 5.0（上界越界的整数值，与 50.5 同源但更贴近边界）。 */
    @Test
    void marginFiftyOneFallsBackToDefault() {
        ConfigManager.ConfigData cfg = withMargin(51.0);
        assertEquals(ConfigManager.CPU_HYSTERESIS_DEFAULT, cfg.cpu_hysteresisMargin, EPS,
            "margin=51 越过上界必须回退 5.0；若这里回退成 50，会让人误以为「51 只是被钳到 50」，"
                + "而实际语义是「这个值根本不可用」");
    }

    // ====================================================================================
    // C. 迟滞带与阈值的交叉校验：margin < min(process, system)，回退 min(5.0, min/2)
    //
    // 这一组要先说清「两层防线」的事实，否则断言消息会写出不存在的后果：
    //   • 运行时层：MetricsCollector.latched 里 `exit = threshold - margin`，
    //     若 exit <= 0 则退回无迟滞判据 `value > threshold`（CpuHysteresisLatchTest 已有覆盖）。
    //     所以 margin >= threshold 的<b>实际</b>后果不是「闩锁永不解除」，而是
    //     <b>迟滞被静默关掉</b>——用户以为设了 5 个百分点的缓冲，其实一点都没有。
    //   • 配置层（本组锁的这层）：把同一个条件提前拦下，① 发 warning 让「迟滞没生效」可见，
    //     ② 回退成 min(默认, min/2) 让迟滞<b>仍然可用</b>，而不是退化到无迟滞。
    // 「回退不能静默」是本仓库的通用口径（见 ThresholdPairZeroValidationTest 同类注释）：
    // 运行时兜底保证不坏，配置层保证不哑。
    // ====================================================================================

    /**
     * margin <b>恰好等于</b>最小阈值：process=5, system=90, margin=5 → 回退 2.5。
     *
     * <p>算一遍：{@code cpuMinThreshold = min(5.0, 90.0) = 5.0}；
     * 判据是 {@code margin >= cpuMinThreshold}（<b>闭</b>）→ {@code 5.0 >= 5.0} 成立，触发；
     * {@code fallback = min(5.0, 5.0 / 2.0) = min(5.0, 2.5) = 2.5}。期望 <b>2.5</b>。</p>
     *
     * <p>这条同时锁两件事：(1) {@code >=} 的闭合方向——写成 {@code >} 时 margin=5 会被放行；
     * (2) 回退算式是 {@code min(默认, min/2)} 而不是固定默认 5.0——后者在阈值为 5 时
     * 仍然 {@code 5 >= 5}，回退完条件照旧成立。</p>
     */
    @Test
    void marginEqualToMinThresholdRevertsToHalfOfIt() {
        ConfigManager.ConfigData cfg = withCpu(5.0, 90.0, 5.0);
        assertEquals(2.5, cfg.cpu_hysteresisMargin, EPS,
            "margin == min(5, 90) 时退出线恰为 0，运行时会退回无迟滞判据——"
                + "用户以为有 5 个百分点缓冲，实际一点都没有，且不吭声；"
                + "判据是闭区间的 >= 所以必须触发，回退值应是 min(5.0, 5.0/2)=2.5 让迟滞仍然可用");
    }

    /**
     * margin <b>略大于</b>最小阈值：process=3, system=90, margin=4 → 回退 1.5。
     *
     * <p>算一遍：{@code min = 3.0}；{@code 4.0 >= 3.0} 成立；
     * {@code fallback = min(5.0, 3.0 / 2.0) = min(5.0, 1.5) = 1.5}。期望 <b>1.5</b>。</p>
     *
     * <p>这条专锁「回退值不能硬编码成默认 5.0」：阈值 3 时 5.0 仍然 {@code >= 3}，
     * 用默认值回退等于完全没修——回退出来的 margin 自己又会触发同一个条件，
     * 迟滞依旧处于关闭状态。代码注释里那句「阈值为 3 时 5 仍不小于阈值」说的就是这个坑。</p>
     */
    @Test
    void marginSlightlyAboveMinThresholdRevertsToHalfOfIt() {
        ConfigManager.ConfigData cfg = withCpu(3.0, 90.0, 4.0);
        assertEquals(1.5, cfg.cpu_hysteresisMargin, EPS,
            "阈值 3 + margin 4 时退出线为 -1，运行时退回无迟滞判据、迟滞被静默关闭；"
                + "回退值必须是 min(5.0, 3.0/2)=1.5，若硬编码成默认 5.0 则 5 >= 3 仍成立，"
                + "回退后迟滞依然没生效");
    }

    /** margin <b>略小于</b>最小阈值：process=10, system=90, margin=9 → 合法保留 9.0。 */
    @Test
    void marginSlightlyBelowMinThresholdIsKept() {
        ConfigManager.ConfigData cfg = withCpu(10.0, 90.0, 9.0);
        assertEquals(9.0, cfg.cpu_hysteresisMargin, EPS,
            "9 < 10 时退出线为 1 > 0，迟滞真实生效，属合法配置；被改掉说明交叉判据"
                + "写得比 `margin >= min` 更严（比如误用了 >、或拿 max 而非 min 比），"
                + "那会让合法用户的迟滞留在一个比他配置更窄的值上");
    }

    /**
     * process 与 system <b>不对称</b>：process=90, system=3, margin=4 → 回退 1.5。
     *
     * <p>算一遍：{@code min(90.0, 3.0) = 3.0}；{@code 4.0 >= 3.0} 成立；
     * {@code fallback = min(5.0, 1.5) = 1.5}。期望 <b>1.5</b>。</p>
     *
     * <p>这条锁 {@code Math.min} 取的是<b>较小者</b>而不是 process：
     * 若误写成 {@code cfg.cpu_processWarningThreshold}，min 会是 90，{@code 4 >= 90} 为假 →
     * margin 原样保留 4，而 system 侧的退出线 {@code 3 - 4 = -1} 使该侧迟滞静默失效。
     * 即「用户把 system 阈值调得比 process 低」这个完全合法的场景会无声坏掉一半。</p>
     */
    @Test
    void crossCheckUsesTheSmallerOfTheTwoThresholds() {
        ConfigManager.ConfigData cfg = withCpu(90.0, 3.0, 4.0);
        assertEquals(1.5, cfg.cpu_hysteresisMargin, EPS,
            "cpuMinThreshold 必须取 min(90, 3)=3；若误取 process(90)，margin 4 会被放行，"
                + "而 system 侧退出线 3-4=-1 使该侧迟滞静默失效——"
                + "把 system 阈值调低是完全合法的配置，不能因此丢掉一半的迟滞保护");
    }

    // ====================================================================================
    // D. 与 sanitizePercent 的顺序耦合
    // ====================================================================================

    /**
     * 交叉校验用的是 <b>sanitizePercent 回退之后</b>的阈值。
     *
     * <p><b>第一个探针（真正钉住顺序的那个）</b>：process={@code -1} / system=90 / margin=5。
     * 正确顺序下 sanitizePercent 先把 -1 换成 80.0，交叉校验看到 {@code min(80, 90) = 80}，
     * {@code 5 >= 80} 为假 → margin <b>保留 5.0</b>。
     * 若顺序反过来，交叉校验看到的是原始 {@code min(-1, 90) = -1}，
     * {@code 5 >= -1} 成立 → 回退值 {@code min(5.0, -1/2) = -0.5}，
     * margin 会被写成一个<b>负数</b>（比用户原始错误更糟，且逃过了 {@code [0,50]} 区间闸，
     * 因为那道闸已经跑完了）。两条路径的结果相差整整 5.5，可证伪。</p>
     *
     * <p><b>第二个探针（任务书指定的 101 场景）</b>：process=101 / system=90 / margin=50。
     * 回退后 min 是 80，{@code 50 >= 80} 为假 → 保留 50.0。
     * <b>诚实说明</b>：这个场景其实<b>区分不了</b>两种顺序——原始 min 是 {@code min(101, 90) = 90}，
     * {@code 50 >= 90} 同样为假，结果一模一样。顺序若要在这里产生差异，margin 得落进
     * {@code [80, 90)}，而 margin 上界是 50，永远到不了。所以真正锁顺序的是第一个探针；
     * 第二个只负责「越界值不污染下游」这条独立性质，不冒充顺序覆盖。</p>
     */
    @Test
    void crossCheckSeesTheSanitizedThresholds() {
        ConfigManager.ConfigData order = withCpu(-1.0, 90.0, 5.0);
        assertEquals(80.0, order.cpu_processWarningThreshold, EPS,
            "process=-1 必须被 sanitizePercent 换成 80.0");
        assertEquals(5.0, order.cpu_hysteresisMargin, EPS,
            "回退后的 min 是 min(80, 90)=80，5 < 80 故 margin 保留 5.0；若交叉校验抢在 "
                + "sanitizePercent 之前跑，它会拿原始的 -1 当基准，把 margin 写成 -0.5（负数、"
                + "且已越过 [0,50] 区间闸），运行时 exit 变成 80-(-0.5)=80.5 高于阈值本身——"
                + "迟滞反转成「只在阈值之上 0.5 内保持」，比无迟滞还抖");

        ConfigManager.ConfigData outOfRange = withCpu(101.0, 90.0, 50.0);
        assertEquals(80.0, outOfRange.cpu_processWarningThreshold, EPS,
            "process=101 必须先被 sanitizePercent 回退成 80.0，交叉校验才有正确的比较基准");
        assertEquals(50.0, outOfRange.cpu_hysteresisMargin, EPS,
            "回退后的 min 是 min(80, 90)=80，50 < 80 故 margin 合法保留；"
                + "若这里变成 5.0，说明交叉校验拿了回退前的 101 当基准（或误用了 `>` 之外的口径）");
    }

    // ====================================================================================
    // E. 不得误报：合法配置零 warning，越界时 warning 必须点名键
    // ====================================================================================

    /**
     * 默认合法配置不产生任何 cpu 类 warning。
     *
     * <p>这条防「校验过度收紧」：回退逻辑本身不会崩，但一条无谓的 warning 会让服主
     * 每次 reload 都在控制台看到「你的配置有问题」，长期后果是所有人开始无视这类日志——
     * 真出问题时那条真正重要的 warning 也就一起被无视了。</p>
     *
     * <p><b>刻意不断言 {@code warnings.isBlank()}</b>：本类只对 CPU 三个键负责，
     * 而 {@code validateThresholds} 会跑完整张表。若其他字段的基线日后被改坏，
     * 断言整体为空会让本类红在一个与 CPU 毫无关系的地方，读的人要花时间排除误判。
     * 所以这里只按 {@code cpu} 前缀断言——既精确又不会替别的模块背锅。</p>
     */
    @Test
    void legalConfigProducesNoCpuWarnings() {
        String warnings = ConfigFixtures.warningsOf(LOGGER + "-clean", cfg -> {
        });
        assertFalse(warnings.contains("cpu.process_warning_threshold"),
            "合法的 process=80.0 不得触发回退 warning；got: " + warnings);
        assertFalse(warnings.contains("cpu.system_warning_threshold"),
            "合法的 system=90.0 不得触发回退 warning；got: " + warnings);
        assertFalse(warnings.contains("cpu.hysteresis_margin"),
            "合法的 margin=5.0（5 < min(80,90)）不得触发回退 warning；got: " + warnings);
        assertFalse(warnings.contains("CPU"),
            "合法配置不得产生任何 CPU 相关告警（含 hysteresis-not-below 那条）；got: " + warnings);
    }

    /**
     * 越界时 warning 必须带<b>键名</b>——用户要靠它 grep 到 config.yml 的那一行。
     *
     * <p>{@code ConfigMessages} 的硬约束是「键名绝不进模板正文，一律由调用点以 {@code {0}} 传入」，
     * 所以这条断言同时验证「调用点真的把键名传进去了」：漏传的话日志只剩一句
     * 「配置校验：越界，已回退」，服主得逐项猜是哪个键。</p>
     */
    @Test
    void outOfRangeWarningsNameTheOffendingKey() {
        String percentWarnings = ConfigFixtures.warningsOf(LOGGER + "-process", cfg ->
            cfg.cpu_processWarningThreshold = 101.0);
        assertTrue(percentWarnings.contains("cpu.process_warning_threshold"),
            "process=101 的告警必须点名 cpu.process_warning_threshold，"
                + "否则服主无法定位到具体键；got: " + percentWarnings);

        String systemWarnings = ConfigFixtures.warningsOf(LOGGER + "-system", cfg ->
            cfg.cpu_systemWarningThreshold = 101.0);
        assertTrue(systemWarnings.contains("cpu.system_warning_threshold"),
            "system=101 的告警必须点名 cpu.system_warning_threshold；got: " + systemWarnings);

        String marginWarnings = ConfigFixtures.warningsOf(LOGGER + "-margin", cfg ->
            cfg.cpu_hysteresisMargin = -1.0);
        assertTrue(marginWarnings.contains("cpu.hysteresis_margin"),
            "margin=-1 的告警必须点名 cpu.hysteresis_margin；got: " + marginWarnings);
    }
}
