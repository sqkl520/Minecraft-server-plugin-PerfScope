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
package cn.perfscope.history;

/**
 * 单条历史采样（Wave 7A 历史环形缓冲的值对象，来源：docs/06-tasks/WAVE7-DESIGN.md「7A」节）。
 *
 * <p>RAW 值：不消毒、不转换；哨兵值原样存（消费方按有效性过滤——
 * 过滤规则见 {@link HistoryStore} 类注释，勿在此记录层丢弃原始值）。</p>
 *
 * <p>不可变 record：写入环形缓冲后即可被任意线程只读共享，无需复制。</p>
 *
 * <p><b>v1.4 schema 扩容</b>：新增 {@code msptMax}（尖峰——{@code msptAvg} 会把一次 500ms 的
 * 卡顿摊薄到看不见）、{@code cpuProcess}/{@code cpuSystem}（"昨晚 CPU 有没有打满"）、
 * {@code gcCount}/{@code gcTime}（GC 是否在反复扫）。前 7 个组件的位置与语义<b>不变</b>，
 * 新组件一律追加在尾部，使旧 CSV 文件仍按位置读得出来（见 {@link HistoryFileStore}）。</p>
 *
 * <p>哨兵约定（与 {@code MetricsSnapshot} 的 P0-13 一致）：{@code -1} = 未采集/不可用/该维度
 * 在此版本之前没有记录。<b>消费方必须按 -1 过滤</b>，不能当作真实的 0——否则趋势图会显示
 * "CPU 掉到 0%"这种从未发生过的假象。</p>
 */
public record HistorySample(
    long timestamp,          // epoch ms，采样时刻
    double tps1m,
    double msptAvg,          // -1 = 未采集/不可用（哨兵，原样存）
    int heapUsagePercent,
    int playerCount,
    int totalEntities,
    int totalLoadedChunks,
    double msptMax,          // -1 = 未采集（TPS 反推/avg-only 路径没有极值）
    double cpuProcess,       // -1 = 未采集；0.0 是合法读数
    double cpuSystem,        // -1 = 未采集；0.0 是合法读数
    long gcCount,            // -1 = 未采集（累计次数）
    long gcTime              // -1 = 未采集（累计耗时，毫秒）
) {

    /** 旧 7 列样本的哨兵值：v1.4 之前的文件没有这几列。 */
    public static final double NO_DOUBLE = -1.0;
    public static final long NO_LONG = -1L;

    /**
     * 7 参数构造（v1.4 之前的字段集）：新增的 5 个指标一律置 -1 哨兵。
     *
     * <p>保留它有两个理由：既有调用点（诊断测试等）不必为了编译而补一堆 -1；
     * 而"只有 7 个指标"这个历史口径也因此仍然是一种<b>可表达</b>的状态，
     * 而不是靠调用方手写五个 -1 去模仿。</p>
     */
    public HistorySample(long timestamp, double tps1m, double msptAvg, int heapUsagePercent,
                         int playerCount, int totalEntities, int totalLoadedChunks) {
        this(timestamp, tps1m, msptAvg, heapUsagePercent, playerCount, totalEntities,
            totalLoadedChunks, NO_DOUBLE, NO_DOUBLE, NO_DOUBLE, NO_LONG, NO_LONG);
    }
}
