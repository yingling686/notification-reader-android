import io
from pathlib import Path
import subprocess
import sys
import tarfile

adb, serial, destination = sys.argv[1:]
if not serial.startswith('emulator-'):
    raise SystemExit('Screenshot export is limited to the dedicated debug emulator')

probe = subprocess.run([adb, '-s', serial, 'shell', 'run-as', 'cn.tingxun.app', 'test', '-d', 'files/screenshots'], capture_output=True)
if probe.returncode != 0:
    print('No screenshots produced by this targeted test run')
    raise SystemExit(0)
data = subprocess.check_output([adb, '-s', serial, 'exec-out', 'run-as', 'cn.tingxun.app', 'tar', '-cf', '-', '-C', 'files', 'screenshots'])
target = Path(destination)
target.mkdir(parents=True, exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(data)) as archive:
    for member in archive:
        if member.isfile() and member.name.startswith('screenshots/') and member.name.endswith('.png'):
            name = Path(member.name).name
            stream = archive.extractfile(member)
            if stream: (target / name).write_bytes(stream.read())
print('Exported', len(list(target.glob('*.png'))), 'screenshots')
