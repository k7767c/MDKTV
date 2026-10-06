"""Export private fnOS K歌 migration data without displaying credentials."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    module_path = Path(__file__).parent/'fnos/app/migrate.py'
    if not module_path.is_file():
        module_path = Path(__file__).with_name('migrate.py')
    spec = importlib.util.spec_from_file_location('fnos_migrate', module_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    try:
        result = module.export_data(args.source, args.out)
    except Exception:
        # Exception strings can include paths, SQL or configuration contents.
        print('迁移导出失败；原服务和文件保持不变，请检查路径、权限和磁盘空间。', file=sys.stderr)
        return 1
    print(json.dumps(result))
    return 0


if __name__ == '__main__':
    sys.exit(main())
