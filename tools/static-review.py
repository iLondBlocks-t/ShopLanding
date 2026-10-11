#!/usr/bin/env python3
"""Offline structural checks. This is NOT Android lint, a Gradle build or a device test."""
from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
checks = []
def check(ok, description):
    checks.append(bool(ok))
    print(('PASS ' if ok else 'FAIL ') + description)

src = ROOT / 'app/src/main/java/com/ocpilot/app'
core = 'Store Crypto Api HttpCall ApiError Sse SseParser Backoff UrlUtil Hashing Models Events TaskLog Downloads ShareProvider TaskWatchService Notifier Log'.split()
ui = 'Navigator Screen Theme Widgets PairingScreen HomeScreen GithubScreen ProjectsScreen WorkspaceScreen DiffScreen BuildsScreen SettingsScreen'.split()
check(all((src/'core'/f'{n}.java').is_file() for n in core), 'all core classes exist')
check(all((src/'ui'/f'{n}.java').is_file() for n in ui) and (src/'App.java').is_file() and (src/'MainActivity.java').is_file(), 'all screens, App and Activity exist')
files = list((ROOT/'app/src').rglob('*.java')) + list((ROOT/'app/src').rglob('*.xml')) + [ROOT/'app/build.gradle']
forbidden = [(str(p.relative_to(ROOT)), x) for p in files for x in re.findall(r'androidx\.|import kotlin|android\.webkit\.WebView|android\.support\.', p.read_text())]
check(not forbidden, 'no AndroidX / Kotlin / support libraries / WebView')
check(not list((ROOT/'app').rglob('*.kt')) and not list((ROOT/'app').rglob('*.so')) and not list((ROOT/'app/src/main').rglob('*layout*.xml')), 'no Kotlin/native binaries/XML layouts')
java = list(src.rglob('*.java'))
check(not [(p,n) for p in java for n in re.findall(r'new\s+Handler\s*\(\s*\)', p.read_text())], 'no implicit-looper Handler constructors')
check(not [(p,n) for p in java for n in re.findall(r'TODO|FIXME|UnsupportedOperationException\("(?:todo|stub)', p.read_text(), re.I)], 'no TODO/FIXME/stub placeholders')
resources = {}
for lang in ('values', 'values-en'):
    resources[lang] = {x.attrib['name']: ''.join(x.itertext()) for x in ET.parse(ROOT/f'app/src/main/res/{lang}/strings.xml').getroot() if x.tag=='string'}
check(resources['values'].keys()==resources['values-en'].keys(), f"Arabic/English string keys match ({len(resources['values'])} each)")
check(all(re.findall(r'%\d+\$[.\d]*[a-zA-Z]',v)==re.findall(r'%\d+\$[.\d]*[a-zA-Z]',resources['values-en'][k]) for k,v in resources['values'].items()), 'localized printf signatures match')
refs = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', '\n'.join(p.read_text() for p in java)))
missing = refs-resources['values'].keys()
check(not missing, 'all Java string resource references resolve' + (': '+str(sorted(missing)) if missing else ''))
check(all(ET.parse(p) is not None for p in (ROOT/'app/src/main/res').rglob('*.xml')), 'all XML resources parse')
a = '{http://schemas.android.com/apk/res/android}'
m = ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot(); app=m.find('application')
perms={x.get(a+'name') for x in m.findall('uses-permission')}
needed={'INTERNET','ACCESS_NETWORK_STATE','POST_NOTIFICATIONS','REQUEST_INSTALL_PACKAGES','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC'}
check({'android.permission.'+p for p in needed} <= perms and any(x.get(a+'name')=='android.permission.WRITE_EXTERNAL_STORAGE' and x.get(a+'maxSdkVersion')=='28' for x in m.findall('uses-permission')), 'required manifest permissions + API-28 storage cap')
check(app.get(a+'allowBackup')=='false' and app.get(a+'supportsRtl')=='true' and app.get(a+'networkSecurityConfig')=='@xml/network_security_config', 'backup/RTL/network-security manifest flags')
activity=app.find('activity'); service=app.find('service'); provider=app.find('provider')
check(len(app.findall('activity'))==1 and activity.get(a+'exported')=='true' and activity.get(a+'windowSoftInputMode')=='adjustResize' and activity.get(a+'configChanges')=='orientation|screenSize|keyboardHidden|uiMode', 'single exported launcher Activity and configuration flags')
check(service.get(a+'exported')=='false' and service.get(a+'foregroundServiceType')=='dataSync' and provider.get(a+'exported')=='false' and provider.get(a+'grantUriPermissions')=='true' and provider.get(a+'authorities')=='${applicationId}.share', 'private foreground service and read-only grantable provider declaration')
check(ET.parse(ROOT/'app/src/main/res/xml/network_security_config.xml').getroot().find('base-config').get('cleartextTrafficPermitted')=='true', 'explicit cleartext network policy')
build=(ROOT/'app/build.gradle').read_text()
check(all(x in build for x in ["namespace 'com.ocpilot.app'",'compileSdk 34','minSdk 28','targetSdk 34','versionCode 1','versionName "1.0.0"','buildConfig true','minifyEnabled false','VERSION_1_8','returnDefaultValues = true','abortOnError false']), 'pinned Android/Java/build options')
check(re.findall(r"testImplementation\s+'([^']+)'",build)==['junit:junit:4.13.2','org.json:json:20231013'] and not re.findall(r'^\s*(?:implementation|api|runtimeOnly)\s+',build,re.M), 'only required test dependencies; no runtime dependencies')
check("version '8.2.2'" in (ROOT/'build.gradle').read_text() and 'FAIL_ON_PROJECT_REPOS' in (ROOT/'settings.gradle').read_text(), 'AGP 8.2.2 and centralized repositories')
workflow=(ROOT/'.github/workflows/build-apk.yml').read_text()
check(all(x in workflow for x in ['name: Build debug APK','branches: [ main, master ]','workflow_dispatch','ubuntu-latest','actions/checkout@v4','actions/setup-java@v4','gradle/actions/setup-gradle@v4',"gradle-version: '8.5'",'gradle --no-daemon testDebugUnitTest assembleDebug','actions/upload-artifact@v4','name: ocpilot-debug-apk','path: app/build/outputs/apk/debug/*.apk','if-no-files-found: error',"'androidx\\.|import kotlin'"]), 'exact requested CI build/guard/upload structure')
tests='SseParser Backoff UrlUtil Models Events Hashing'.split()
check(all((ROOT/f'app/src/test/java/com/ocpilot/app/core/{n}Test.java').is_file() for n in tests), 'all six JUnit suites exist')
fixtures=ROOT/'app/src/test/resources/fixtures'; manifest=json.loads((fixtures/'manifest.json').read_text())
check(all((fixtures/k).is_file() for k in manifest) and len([k for k in manifest if k.startswith('oc-')])==40, 'fixture manifest is complete (40 OpenCode examples)')
check(all(json.loads(p.read_text()) is not None for p in fixtures.glob('*.json')) and (fixtures/'http-no-content.txt').stat().st_size==0, 'JSON fixtures parse and 204 fixture is empty')
check(all((ROOT/p).is_file() for p in ['README.md','docs/CONTRACT.md','docs/OPENCODE-NOTES.md','app/proguard-rules.pro']), 'required documentation and proguard file exist')
print(f'\n{sum(checks)}/{len(checks)} structural checks passed; {len(java)} production Java files.')
print('Not covered: Gradle/lint, Android install/UI/lifecycle/Keystore, real gateway behavior.')
sys.exit(0 if all(checks) else 1)
