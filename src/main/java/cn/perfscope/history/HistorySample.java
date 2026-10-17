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
 */
public record HistorySample(
    long timestamp,          // epoch ms，采样时刻
    double tps1m,
    double msptAvg,          // -1 = 未采集/不可用（哨兵，原样存）
    int heapUsagePercent,
    int playerCount,
    int totalEntities,
    int totalLoadedChunks
) {}
