"""One-off: renumber database/schema and move migrations out of it.

Run from the SeoulTransitPlatform repository root, with the bundle unpacked so
that this script sits next to its database/ folder:

    python <bundle>\apply_schema_renumber.py --dry-run
    python <bundle>\apply_schema_renumber.py

1. git mv the five files (schema numbers made unique; two migrations moved).
2. Replace references to the old names in every tracked text file.
3. Copy the bundle's run_all.sql, 01_curated_od.sql and migrations/README.md.
Line endings and encoding (UTF-8, no BOM) are kept. Not meant to be committed.
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

MOVES = [  # order matters: 10_stop_area_lookup must leave 10 before 09 takes it
    ('database/schema/10_stop_area_lookup.sql', 'database/schema/11_stop_area_lookup.sql'),
    ('database/schema/09_stop_location_fix.sql', 'database/schema/10_stop_location_fix.sql'),
    ('database/schema/07_performance_indexes.sql', 'database/schema/90_performance_indexes.sql'),
    ('database/schema/10_rename_analysis_table_final.sql', 'database/migrations/01_rename_analysis_table_final.sql'),
    ('database/schema/08b_bus_route_path_variants.sql', 'database/migrations/02_bus_route_path_variants.sql'),
]
REPLACE = [  # full paths first, then bare names; 10_stop_area_lookup before 09_stop_location_fix
    ('database/schema/10_rename_analysis_table_final.sql', 'database/migrations/01_rename_analysis_table_final.sql'),
    ('database/schema/08b_bus_route_path_variants.sql', 'database/migrations/02_bus_route_path_variants.sql'),
    ('10_rename_analysis_table_final.sql', '01_rename_analysis_table_final.sql'),
    ('08b_bus_route_path_variants.sql', '02_bus_route_path_variants.sql'),
    ('10_stop_area_lookup', '11_stop_area_lookup'),
    ('09_stop_location_fix', '10_stop_location_fix'),
    ('07_performance_indexes', '90_performance_indexes'),
]
COPY = ['database/schema/run_all.sql', 'database/schema/01_curated_od.sql', 'database/migrations/README.md']


def git(*args):
    return subprocess.run(['git', *args], check=True, capture_output=True, text=True).stdout


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--dry-run', action='store_true')
    dry = p.parse_args().dry_run
    bundle = Path(__file__).resolve().parent
    if not Path('database/schema/run_all.sql').exists():
        sys.exit('Run from the SeoulTransitPlatform repository root.')
    for old, new in MOVES:
        if not Path(old).exists():
            sys.exit(f'STOP: {old} not found (already moved?)')
        if Path(new).exists():
            sys.exit(f'STOP: {new} already exists')

    for old, new in MOVES:
        print(f'git mv {old} {new}')
        if not dry:
            Path(new).parent.mkdir(parents=True, exist_ok=True)
            git('mv', old, new)

    changed = []
    for name in git('ls-files').splitlines():
        path = Path(name)
        if name in COPY or not path.is_file():
            continue
        raw = path.read_bytes()
        if b'\0' in raw:
            continue
        try:
            text = raw.decode('utf-8')
        except UnicodeDecodeError:
            continue
        new = text
        for a, b in REPLACE:
            new = new.replace(a, b)
        if new != text:
            changed.append(name)
            if not dry:
                path.write_bytes(new.encode('utf-8'))
    print('references updated in:' if changed else 'no other references found')
    for name in changed:
        print('  ' + name)

    for name in COPY:
        print(f'copy {name}')
        if not dry:
            Path(name).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(bundle / name, name)
    print('dry run: nothing changed' if dry else 'done; review with git status and git diff')


if __name__ == '__main__':
    main()
