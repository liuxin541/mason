import argparse
import os
import sys
import json
import math
import numpy as np
import shutil
import subprocess
import importlib.util

# 引入各个子模块
from ifc_parser.loader import load_ifc
from ifc_parser.extractor import extract_elements
from voxelizer.voxel_grid import VoxelGrid
from voxelizer.builder import build_from_elements, build_test_map, seal_exterior, fix_stair_vertical_connectivity, clip_penetrating_elements, fix_room_connectivity, fill_slab_gaps, seal_isolated_air, post_process_cleanup
from exporter.voxel_writer import write_voxel
from exporter.debug_vis import generate_debug_slice

# 尝试查找 IfcConvert 路径
def find_ifcconvert():
    candidates = []

    # 1. Conda 环境标准位置: <env>/Library/bin/IfcConvert.exe
    for prefix in (getattr(sys, 'prefix', None), getattr(sys, 'base_prefix', None)):
        if prefix:
            for name in ("IfcConvert.exe", "IfcConvert"):
                candidates.append(os.path.join(prefix, "Library", "bin", name))

    # 2. 优先在 ifcopenshell 包内部查找 bin 目录（pip 直装/非 conda 方式）
    try:
        import ifcopenshell
        base_path = os.path.dirname(ifcopenshell.__file__)
        for sub in ("bin", os.path.join("..", "Library", "bin")):
            bin_path = os.path.normpath(os.path.join(base_path, sub))
            if os.path.isdir(bin_path):
                for name in ("IfcConvert.exe", "IfcConvert"):
                    candidates.append(os.path.join(bin_path, name))
    except Exception:
        pass

    for p in candidates:
        if os.path.isfile(p) and os.access(p, os.X_OK):
            return p

    # 3. 回退：尝试在系统 PATH 中查找
    return shutil.which("IfcConvert") or shutil.which("IfcConvert.exe")

def main():
    parser = argparse.ArgumentParser(description='BIM 体素化预处理工具')
    parser.add_argument('--input', help='输入 IFC 文件路径')
    parser.add_argument('--output', required=True, help='输出目录')
    parser.add_argument('--voxel-size', type=float, default=0.25, help='体素分辨率（米）')
    parser.add_argument('--test', action='store_true', help='生成测试用的 3D 迷宫体素（无需输入 IFC）')
    
    args = parser.parse_args()
    
    os.makedirs(args.output, exist_ok=True)
    log_path = os.path.join(args.output, 'process.log')
    
    class Logger:
        def __init__(self, path):
            self.file = open(path, 'w', encoding='utf-8')
        def log(self, msg):
            print(msg)
            self.file.write(msg + '\n')
            self.file.flush()
        def close(self):
            self.file.close()
    
    logger = Logger(log_path)
    logger.log(f"[INFO] BIM 处理启动。")

    # 记录网格原点偏移量（用于前端对齐）
    origin_shift = [0.0, 0.0, 0.0]

    # ---------------- 测试模式 ----------------
    if args.test:
        logger.log("[INFO] 运行测试模式：生成预设的 20x20x24 三层迷宫体素。")
        grid = VoxelGrid(width=20, height=20, depth=24, voxel_size=args.voxel_size)
        build_test_map(grid)
        semantic_info = {"floors": 3, "floor_height": 8, "stair_positions": [[11, 3], [11, 4], [11, 5], [11, 6], [11, 7]]}

    # ---------------- 真实 IFC 模式 ----------------
    else:
        if not args.input:
            logger.log("[ERROR] 必须输入 --input IFC文件路径，或者使用 --test 模式。")
            logger.close()
            return
        
        logger.log(f"[INFO] 加载 IFC 文件: {args.input}")
        try:
            ifc_file = load_ifc(args.input)
        except Exception as e:
            logger.log(f"[ERROR] IFC 加载失败: {e}")
            logger.close()
            return

        # 提取楼层信息（IfcBuildingStorey），失败不影响主流程
        floor_count = 1
        floor_elevations = [0.0]
        try:
            storeys = ifc_file.by_type('IfcBuildingStorey')
            floor_count = len(storeys)
            floor_elevations = []
            for s in storeys:
                elev = getattr(s, 'Elevation', None)
                if elev is not None:
                    floor_elevations.append(float(elev) / 1000.0 if abs(elev) > 100 else float(elev))
                else:
                    floor_elevations.append(0.0)
            logger.log(f'[INFO] 识别到 {floor_count} 个楼层')
        except Exception as e:
            logger.log(f'[WARN] 楼层提取失败，使用默认值: {e}')

        logger.log("[INFO] IFC 加载成功，正在提取建筑元素...")
        elements = extract_elements(ifc_file)
        total_elements = sum(len(v) for v in elements.values())
        logger.log(f"[INFO] 提取到 {total_elements} 个建筑构件。")

        # ★★★ 计算建筑包围盒（基于所有构件，非仅 walls）★★★
        all_bboxes = []
        for category_elems in elements.values():
            for elem in category_elems:
                if 'bbox' in elem:
                    all_bboxes.append(elem['bbox'])
        
        if all_bboxes:
            # ★ 精确包围盒（不取整），作为坐标系统统一的权威基准
            #   origin_offset = 建筑世界坐标最小点 (xmin, ymin, zmin)
            #   voxel_index = floor((world_coord - origin_offset) / voxel_size)
            #   world_coord  = voxel_index * voxel_size + origin_offset
            xmin = min(b[0] for b in all_bboxes)
            ymin = min(b[1] for b in all_bboxes)
            zmin = min(b[2] for b in all_bboxes)
            xmax = max(b[3] for b in all_bboxes)
            ymax = max(b[4] for b in all_bboxes)
            zmax = max(b[5] for b in all_bboxes)

            # 记录原点平移量，体素网格将以此为 (0,0,0)
            origin_shift = [xmin, ymin, zmin]

            # 换算成网格数 (额外向外 +2 格作为物理计算安全边距)
            width = int(math.floor((xmax - xmin) / args.voxel_size)) + 4
            height = int(math.floor((ymax - ymin) / args.voxel_size)) + 4
            depth = int(math.floor((zmax - zmin) / args.voxel_size)) + 4
            logger.log(f"[INFO] IFC 包围盒计算完毕, 动态网格尺寸: {width}x{height}x{depth}")
            logger.log(f"[INFO] 精确 origin_offset=[{xmin:.4f}, {ymin:.4f}, {zmin:.4f}]")
        else:
            width, height, depth = 60, 40, 24
            logger.log("[WARN] 未计算出包围盒，使用保底网格尺寸 60x40x24")

        grid = VoxelGrid(width=int(width), height=int(height), depth=int(depth), voxel_size=args.voxel_size)
        build_from_elements(grid, elements, origin_shift)
        fix_stair_vertical_connectivity(grid)
        clip_penetrating_elements(grid)  # 清除穿透楼板的墙和窗，防止人员在屋顶生成
        seal_exterior(grid)  # 封闭外部空气，防止人员在楼外生成
        seal_isolated_air(grid)  # 封闭被墙包围的孤立空气区域（无门/出口/楼梯）
        fill_slab_gaps(grid)  # 填充楼板层空气缺口（楼梯井除外）
        fix_room_connectivity(grid)  # 打通小房间，修复连通性
        post_process_cleanup(grid)  # 后处理：清除楼板层孤立门，验证出口连通性
        # 坐标系统统一元数据：所有端 (Python/Java/前端) 都以本字段为权威来源
        # 从体素数据提取楼梯位置（去重 x,y 坐标）
        stair_coords = set()
        stair_indices = np.argwhere(grid.data == VoxelGrid.STAIR)
        for z, y, x in stair_indices:
            stair_coords.add((int(x), int(y)))
        stair_positions = [list(p) for p in sorted(stair_coords)]
        logger.log(f'[INFO] 提取到 {len(stair_positions)} 个楼梯水平位置')

        # 检测楼板层：非空气体素占比在 80%~95% 之间的层视为楼板
        # 排除 >95% 的全墙层（seal_exterior 封闭的屋顶以上区域）
        # 合并连续的楼板层（如双层楼板），只保留每层的起始 z
        raw_slabs = []
        for z in range(grid.depth):
            layer = grid.data[z]
            non_empty = int(np.sum(layer != VoxelGrid.EMPTY))
            ratio = non_empty / (grid.width * grid.height)
            if 0.80 < ratio < 0.95:
                raw_slabs.append(z)
        # 合并连续的楼板层，只保留每组的第一个
        merged_slabs = []
        prev = -10
        for z in raw_slabs:
            if z - prev > 1:
                merged_slabs.append(z)
            prev = z
        # 过滤：只保留上方有真正活动楼层（非楼板层）且有门的楼板
        # 屋顶楼板上方是全墙层，不应作为独立楼层显示
        floor_slabs = []
        for i, slab_z in enumerate(merged_slabs):
            # 找到当前楼板组的最后一层（连续楼板）
            slab_end = slab_z
            while slab_end + 1 < grid.depth:
                ratio = np.sum(grid.data[slab_end + 1] != VoxelGrid.EMPTY) / (grid.width * grid.height)
                if ratio > 0.80:
                    slab_end += 1
                else:
                    break
            # 检查楼板组上方是否有活动楼层（非空气占比 < 80%）且有门
            next_slab = merged_slabs[i + 1] if i + 1 < len(merged_slabs) else grid.depth
            has_active_floor_with_door = False
            for z in range(slab_end + 1, next_slab):
                layer = grid.data[z]
                ratio = np.sum(layer != VoxelGrid.EMPTY) / (grid.width * grid.height)
                if ratio < 0.80 and int(np.sum(layer == 3)) > 0:
                    has_active_floor_with_door = True
                    break
            if has_active_floor_with_door:
                floor_slabs.append(slab_z)
        logger.log(f'[INFO] 检测到 {len(floor_slabs)} 个楼板层: z={floor_slabs} (合并后 {len(merged_slabs)} 层, 原始 {len(raw_slabs)} 层)')

        semantic_info = {
            "floors": len(floor_slabs) + 1,  # 活动楼层数 = 楼板数 + 1
            "floor_elevations": floor_elevations,
            "floor_slabs": floor_slabs,
            "stair_positions": stair_positions,
            # 体素网格尺寸（整数索引上限 +1）
            "grid_dims": [int(grid.width), int(grid.height), int(grid.depth)],
            # 建筑在世界坐标中的轴对齐包围盒 (xmin, ymin, zmin, xmax, ymax, zmax)
            "building_bbox_world": [float(xmin), float(ymin), float(zmin),
                                    float(xmax), float(ymax), float(zmax)],
        }

    # ==========================================
    # 导出体素文件
    # ==========================================
    voxel_path = os.path.join(args.output, 'voxel_grid.voxel')
    write_voxel(grid, voxel_path)
    logger.log(f"[INFO] 体素网格已导出: {voxel_path}")
    logger.log(f"[INFO] 最终网格尺寸: {grid.width}x{grid.height}x{grid.depth}")

    # 导出语义信息（包含坐标偏移，供前端渲染 glTF 时对齐）
    semantic_info["voxel_size"] = args.voxel_size
    semantic_info["origin_offset"] = origin_shift
    info_path = os.path.join(args.output, 'semantic_info.json')
    with open(info_path, 'w', encoding='utf-8') as f:
        json.dump(semantic_info, f, indent=4)
    logger.log(f"[INFO] 语义信息已导出: {info_path}")

    # 生成预览图
    vis_path = os.path.join(args.output, 'debug_slice_z0.png')
    generate_debug_slice(grid, z_index=0, output_path=vis_path)
    logger.log(f"[INFO] 2D 预览切片已生成: {vis_path}")

    # ==========================================
    # ★★★ 额外步骤：调用 IfcConvert 导出精确 glTF 模型 ★★★
    # ==========================================
    if not args.test:
        cc_path = find_ifcconvert()
        if cc_path:
            glb_path = os.path.join(args.output, 'building.glb')
            # IfcConvert 默认输出世界坐标 glTF; 体素网格 (0,0,0) 对应世界 origin_shift,
            # 二者通过 semantic_info.json 中的 origin_offset 在前端对齐:
            #   world_coord = voxel_index * voxel_size + origin_offset
            # 不使用 --translate (0.8.0 不支持) / --model-offset (0.8.4+ 才有),
            # 保证跨版本兼容。多线程加速用 -j。
            cmd = [
                cc_path,
                args.input,
                glb_path,
                "-y",            # --y-up: 将 IFC 的 Z-up 转为 Three.js 的 Y-up (Y 为垂直方向)
                                # 注意：不是"自动确认覆盖"，--yes 才是。前端据此做 Y-Z 互换对齐。
                "-j", str(os.cpu_count() or 4),  # 并行几何解释
            ]
            try:
                logger.log(f"[INFO] 开始导出精细模型 (glTF): {glb_path}")
                subprocess.run(cmd, check=True, capture_output=True, text=True)
                logger.log(f"[INFO] 精细模型导出成功！")
            except subprocess.CalledProcessError as e:
                logger.log(f"[WARN] 导出 glTF 失败: {e.stderr}")
        else:
            logger.log("[WARN] 未找到 IfcConvert 工具，跳过精细模型导出。")

    logger.log("[INFO] 所有处理完成！")
    logger.close()

if __name__ == '__main__':
    main()