from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
import sys

root = Path(__file__).resolve().parents[1]
delivery = root / 'delivery'
brand = root / 'marketing/个人形象版-俱乐部品牌版'
version = sys.argv[1] if len(sys.argv) > 1 else '0.1.12'
assert all(c.isdigit() or c == '.' for c in version)

def archive(target, files):
    with ZipFile(target, 'w', ZIP_DEFLATED) as z:
        for source, name in sorted(files, key=lambda item: item[1]):
            z.write(source, name)
    with ZipFile(target) as z:
        assert z.testzip() is None
        names = z.namelist()
        assert len(names) == len(set(names))
        assert not any(name.endswith(('.p12', '.jks')) or Path(name).name in {'signing.properties', 'local.properties'} for name in names)
    return target

brand_zip = archive(brand / '听讯宣传图-俱乐部品牌版.zip', [(p, p.name) for p in brand.iterdir() if p.is_file() and p.suffix != '.zip'])
source_files = []
for name in ['README.md', '.gitignore', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle']:
    p = root / name
    if p.is_file(): source_files.append((p, 'notification-reader-android/' + name))
for directory in ['app/src', 'gradle', 'scripts', f'docs/screenshots/{version}']:
    for p in (root / directory).rglob('*'):
        if p.is_file() and '__pycache__' not in p.parts:
            source_files.append((p, 'notification-reader-android/' + p.relative_to(root).as_posix()))
for p in (root / 'docs').glob('*.md'):
    source_files.append((p, 'notification-reader-android/' + p.relative_to(root).as_posix()))
source_zip = archive(delivery / f'听讯-{version}-源码.zip', source_files)

full_files = [(root / f'delivery/听讯-{version}.apk', f'听讯-{version}.apk'),
              (root / f'delivery/SHA256-{version}.txt', 'SHA256.txt'),
              (root / f'delivery/安装说明-{version}.md', '安装说明.md'),
              (root / f'delivery/测试记录-{version}.md', '测试记录.md'),
              (source_zip, source_zip.name), (brand_zip, brand_zip.name)]
for p in (root / f'docs/screenshots/{version}').glob('*.png'):
    full_files.append((p, '界面截图/' + p.name))
for p in (root / f'docs/results/{version}').rglob('*'):
    if p.is_file() and p.suffix in {'.xml', '.txt', '.json', '.html'}:
        full_files.append((p, '验证结果/' + p.relative_to(root / f'docs/results/{version}').as_posix()))
full_zip = archive(delivery / f'听讯-{version}-完整交付.zip', full_files)
for p in [brand_zip, source_zip, full_zip]:
    print(p.name, p.stat().st_size, hashlib.sha256(p.read_bytes()).hexdigest())
