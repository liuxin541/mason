import sys
sys.path.insert(0, r'D:\mason\project_root\bim_processor')
from ifc_parser.loader import load_ifc

ifc = load_ifc(r'D:\mason\一号办公楼1.ifc')
storeys = ifc.by_type('IfcBuildingStorey')
print(f'IfcBuildingStorey 数量: {len(storeys)}')
for s in storeys:
    elev = getattr(s, 'Elevation', 'N/A')
    print(f'  - {s.Name}, Elevation={elev}')
