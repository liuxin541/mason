def load_ifc(filepath):
    """
    加载 IFC 文件并返回 ifcopenshell 对象
    （由于仅在真实 IFC 处理时调用，将 import 放在函数内部，避免测试模式报错）
    """
    try:
        # 将导入移到函数内部，变成按需加载
        import ifcopenshell
        return ifcopenshell.open(filepath)
    except ImportError:
        raise ImportError("若要解析真实的 IFC 文件，请先安装 ifcopenshell (pip install ifcopenshell)。测试模式(--test)无需此依赖。")
    except Exception as e:
        raise ValueError(f"无法解析 IFC 文件: {e}")