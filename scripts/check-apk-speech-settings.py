"""Check the built APK contains the speech-settings menu and its three types."""
import sys
import zipfile

apk_path = sys.argv[1]
with zipfile.ZipFile(apk_path) as apk:
    dex = b''.join(apk.read(name) for name in apk.namelist()
                   if name.startswith('classes') and name.endswith('.dex'))
    for value in ('ตั้งค่าเสียงพูด', 'ลูกค้าทักมา', 'ลูกค้าส่งรูป', 'Thunder ตรวจสลิปผ่าน'):
        if value.encode('utf-8') not in dex:
            raise SystemExit('Missing speech settings UI label: ' + value)
print('Speech settings menu and three type labels found in APK DEX')
