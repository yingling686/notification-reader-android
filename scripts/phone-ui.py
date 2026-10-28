"""Small ADB UI helper for explicitly connected TingXun devices; never reads app secrets."""
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

serial, operation, label, *extra = sys.argv[1:]
adb = str(Path(os.environ['LOCALAPPDATA']) / 'TingXunSdk/platform-tools/adb.exe')
def shell(*args):
    return subprocess.check_output([adb, '-s', serial, 'shell', *args], text=True, encoding='utf-8', errors='replace')
shell('uiautomator', 'dump', '/data/local/tmp/tingxun-ui.xml')
root = ET.fromstring(shell('cat', '/data/local/tmp/tingxun-ui.xml'))
nodes = list(root.iter('node'))
if not any(n.get('package') == 'cn.tingxun.app' for n in nodes):
    raise SystemExit('TingXun is not visible; unlock the device and open the app')
matches = [n for n in nodes if n.get('package') == 'cn.tingxun.app' and label in (n.get('text'), n.get('content-desc'))]
if not matches: raise SystemExit('Requested control is not visible: ' + label)
node = matches[0]
if operation == 'toggle':
    parents = {child: parent for parent in root.iter() for child in parent}
    container = node
    while container is not None:
        choices = [n for n in container.iter('node') if n.get('checkable') == 'true']
        if choices:
            node = choices[0]
            if node.get('checked') == extra[0]:
                print('Already set'); sys.exit(0)
            break
        container = parents.get(container)
    else: raise SystemExit('No switch found for the label')
elif operation != 'tap': raise SystemExit('Use tap or toggle')
numbers = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
if len(numbers) != 4: raise SystemExit('Invalid control bounds')
x1, y1, x2, y2 = numbers
if x2 <= x1 or y2 <= y1: raise SystemExit('Control is clipped')
shell('input', 'tap', str((x1 + x2)//2), str((y1 + y2)//2))
print('Control tapped')
