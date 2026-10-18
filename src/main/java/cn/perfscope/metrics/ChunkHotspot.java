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
package cn.perfscope.metrics;

/**
 * 单个热点区块（Wave 8C）。
 *
 * <p>采集口径（与 V1.2 规则表 D-ENT-04 / §5 一致）：{@code entityCount} =
 * {@code chunk.getEntities().length}，<b>含玩家</b>（展示层文案须注明）；
 * {@code dominantType} 为该区块内实体类型的众数（{@code EntityType.name()}，
 * 平票取字典序最小者，保证输出稳定）；区块无实体时不会产出本对象（空区块不进候选）。</p>
 *
 * <p>契约：全部字段在采集时填充，发布后只读（与 MetricsSnapshot 同约定）。</p>
 */
public record ChunkHotspot(
    String world,        // 世界名
    int chunkX,          // 区块 X
    int chunkZ,          // 区块 Z
    int entityCount,     // 区块内实体数（含玩家）
    String dominantType, // 主导类型 EntityType.name()（无实体时不应出现本对象）
    int dominantCount    // 主导类型的数量
) {

    /** 主导类型占比（0-100 整数；0 实体防御性返回 0）。 */
    public int dominantPercent() {
        if (entityCount <= 0) {
            return 0;
        }
        return (int) Math.round(100.0 * dominantCount / entityCount);
    }

    /** 区块中心方块坐标 X（chunkX*16+8；V1.2 §5.2 锁定换算）。 */
    public int blockX() {
        return chunkX * 16 + 8;
    }

    /** 区块中心方块坐标 Z（chunkZ*16+8）。 */
    public int blockZ() {
        return chunkZ * 16 + 8;
    }
}
