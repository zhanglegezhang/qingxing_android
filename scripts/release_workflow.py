"""Local build/test/release coordinator. Never handles or publishes the Android AK."""
import argparse
import contextlib
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
sys.path.insert(0, str(Path(__file__).resolve().parent))
from impact_plan import SUITES, MANDATORY, select, hash_plan

for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, 'reconfigure'):
        stream.reconfigure(encoding='utf-8', errors='replace')

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
REQUIRED = SUITES  # All available suites; a candidate plan determines the required subset.
LEGACY_SIGNER = '45d07b06c445f72c1a4069645899d3f959d769f5'
REPORTS = {
    'latest-upgrade': ('latest-upgrade-test-output.txt',),
    'upgrade': ('upgrade-test-output.txt',), 'layout-upgrade': ('layout-upgrade-test-output.txt',),
    'android': ('android-test-output.txt',), 'location': ('location-test-output.txt',),
    'holiday': ('holiday-test-output.txt',),
    'baidu-location': ('baidu-location-test-output.txt',),
    'baidu-map': ('baidu-map-test-output.txt',), 'baidu-address': ('baidu-address-test-output.txt',),
    'smoke-min': ('smoke-min-test-output.txt', 'smoke-min-crash-buffer.txt'),
    'smoke': ('smoke-test-output.txt', 'smoke-crash-buffer.txt'),
}


def make_plan(root=ROOT, force_full=False, additions=()):
    current = inputs(root)
    baseline = None
    previous = {}
    baseline_inputs_valid = False
    latest = root / 'release/latest.json'
    if latest.exists():
        data = read(latest)
        apk = (root / data['apk']).resolve()
        if not apk.is_relative_to((root / 'dist/releases').resolve()) or digest(apk) != data['sha256']:
            raise RuntimeError('Published baseline APK mismatch; stop delivery.')
        baseline = {k: data[k] for k in ('versionName', 'versionCode', 'apk', 'sha256')}
        archived = apk.parent / 'inputs.json'
        if archived.exists():
            previous = read(archived)
            if fingerprint(previous) != data['sourceSha256']:
                raise RuntimeError('Published baseline input fingerprint mismatch.')
            baseline_inputs_valid = True
    changed = [p for p in set(previous) | set(current) if previous.get(p) != current.get(p)]
    plan = select(changed, baseline_inputs_valid, force_full, additions)
    plan['baseline'] = baseline
    current_version = version(root) if (root / 'release/version.json').exists() else {}
    signing = current_version.get('signing')
    if signing:
        previous_signer = data.get('apkMetadata', {}).get('signerSha1') if baseline else None
        if baseline and previous_signer != signing['sha1']:
            if (current_version['versionCode'] != signing['firstVersionCode']
                    or previous_signer != signing['previousSha1']
                    or baseline['versionCode'] >= signing['firstVersionCode']):
                raise RuntimeError('Unexpected signing change from the published baseline.')
            plan['reinstallRequired'] = True
            plan['reasons']['latest-upgrade'] = ['verify Android rejects old-signature update, then verify clean install and backup import']
        incompatible = ['upgrade']
        if not any(seed['versionCode'] >= signing['firstVersionCode'] for seed in current_version.get('upgradeFrom', [])):
            incompatible.append('layout-upgrade')
        for suite in incompatible:
            if suite in plan['required']:
                plan['required'].remove(suite)
                plan['reasons'].pop(suite)
                plan['skipped'][suite] = 'Historical APKs use the retired signing certificate. In-place upgrades from them are incompatible.'
    plan['sourceSha256'] = fingerprint(current)
    substantive = [p for p in changed if p != 'release/version.json']
    plan['documentationOnly'] = bool(substantive) and all(p.endswith('.md') and p != 'AGENTS.md' for p in substantive)
    return plan


def validate_plan(data, root=ROOT):
    plan = data.get('testPlan')
    if not isinstance(plan, dict) or data.get('planSha256') != hash_plan(plan):
        raise RuntimeError('Missing or changed test plan; build a new candidate.')
    expected = make_plan(root, plan['forcedFull'], plan['additions'])
    if expected != plan:
        raise RuntimeError('Test plan/baseline changed; build a new candidate.')
    return plan


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def atomic(path, content):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        temporary.write_text(content, encoding='utf-8')
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def write(path, data):
    atomic(path, json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def version(root=ROOT):
    v = read(root / 'release/version.json')
    if not re.fullmatch(r'\d+\.\d+(?:\.\d+)?', v.get('versionName', '')) or type(v.get('versionCode')) is not int or v['versionCode'] < 1:
        raise RuntimeError('Invalid release/version.json')
    signing = v.get('signing')
    if signing is not None and (not isinstance(signing, dict)
            or not re.fullmatch(r'[0-9a-f]{40}', str(signing.get('sha1', '')))
            or not re.fullmatch(r'[0-9a-f]{40}', str(signing.get('previousSha1', '')))
            or signing['sha1'] == signing['previousSha1']
            or type(signing.get('firstVersionCode')) is not int
            or not 1 <= signing['firstVersionCode'] <= v['versionCode']):
        raise RuntimeError('Invalid release signing transition.')
    return v


@contextlib.contextmanager
def exclusive(root=ROOT):
    """Kernel-owned byte lock, automatically released even if the process dies."""
    lock = root / 'build/workflow.lock'
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open('a+b') as handle:
        handle.seek(0, os.SEEK_END)
        if not handle.tell():
            handle.write(b'0')
            handle.flush()
        handle.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise RuntimeError('Another build/test/release is running in this directory; retry after it finishes.') from None
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == 'nt':
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def inputs(root=ROOT):
    paths = set(root.glob('*.ps1')) | {root / 'release/version.json', root / 'AGENTS.md', root / 'README.md', root / 'docs/BUILD.md'}
    for folder in ('app', 'scripts', 'tests'):
        paths.update(p for p in (root / folder).rglob('*') if p.is_file() and p.suffix in ('.java', '.xml', '.py', '.ps1', '.json', '.apk'))
    paths.add(root / '.tooling/baidu/dependencies.json')
    paths.add(root / '.tooling/baidu-ak.txt')  # Hash only, never the key contents.
    records = {p.relative_to(root).as_posix(): digest(p) for p in sorted(paths) if p.is_file()}
    return records


def fingerprint(records):
    return hashlib.sha256(json.dumps(records, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def candidate_path(value=None):
    path = Path(value).resolve() if value else Path(read(ROOT / 'build/current-candidate.json')['manifest']).resolve()
    if not path.is_relative_to((ROOT / 'build/candidates').resolve()) or path.name != 'candidate.json':
        raise RuntimeError('Candidate must be a project-local candidate.json')
    return path


def validate_candidate(path):
    data = read(path)
    current = version()
    if any(data.get(k) != current.get(k) for k in ('versionName', 'versionCode', 'signing')):
        raise RuntimeError('Version changed since candidate build; build a new candidate.')
    if fingerprint(inputs()) != data['sourceSha256']:
        raise RuntimeError('Source/build/test inputs changed since build; build a new candidate.')
    apk = path.parent / data['file']
    if digest(apk) != data['sha256']:
        raise RuntimeError('Candidate APK changed after build; refusing test/release.')
    if digest(ROOT / 'build/classes.jar') != data['classesSha256']:
        raise RuntimeError('Compiled classes no longer match this candidate; rebuild before testing.')
    validate_plan(data)
    return data, apk


def shell():
    return shutil.which('pwsh') or shutil.which('powershell') or str(Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/powershell.exe')


def run_core(name, arguments, log, candidate):
    env = os.environ.copy()
    env['QINGXING_CANDIDATE'] = str(candidate)
    env['QINGXING_WORKFLOW_LOCKED'] = str(os.getpid())
    env.setdefault('JAVA_TOOL_OPTIONS', '-Xmx768m')
    # Keep all output in the candidate's evidence, and forward bounded progress.
    with Path(log).open('w', encoding='utf-8') as output:
        process = subprocess.Popen([shell(), '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(ROOT / 'scripts' / name)] + list(arguments), cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')
        for line in process.stdout:
            output.write(line)
            output.flush()
            if re.search(r'PASS|FAIL|ERROR|Exception|Built|Candidate|^Ran |^OK$|D8 completed', line):
                print(line.rstrip(), flush=True)
        code = process.wait()
    if code:
        raise RuntimeError(f'{name} failed; see {log}')


def ensure_new_release(v, root=ROOT):
    name = v['versionName']
    if (root / f'dist/releases/{name}').exists() or (root / f'dist/Qingxing-{name}.apk').exists():
        raise RuntimeError(f'Version {name} already exists. Increment release/version.json; published APKs cannot be overwritten.')
    if (root / 'release/latest.json').exists() and read(root / 'release/latest.json')['versionCode'] >= v['versionCode']:
        raise RuntimeError('versionCode must increase for a new release.')


def inspect_apk(apk, expected):
    aapt = next((ROOT / '.tooling/build-tools').rglob('aapt2.exe'))
    jdk = next(p for p in (ROOT / '.tooling/jdk').iterdir() if p.is_dir())
    signer = aapt.parent / 'lib/apksigner.jar'
    badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], cwd=ROOT).decode('utf-8', 'replace')
    package = next(line for line in badging.splitlines() if line.startswith('package:'))
    fields = dict(re.findall(r"(\w+)='([^']*)'", package))
    if fields.get('name') != 'cn.qingxing.reminder' or fields.get('versionName') != expected['versionName'] or fields.get('versionCode') != str(expected['versionCode']):
        raise RuntimeError('Actual APK package/version differs from release/version.json')
    certs = subprocess.check_output([str(jdk / 'bin/java.exe'), '-jar', str(signer), 'verify', '--print-certs', str(apk)], cwd=ROOT).decode('utf-8', 'replace')
    sha = re.search(r'Signer #1 certificate SHA-1 digest: ([0-9a-fA-F]+)', certs)
    if sha is None or sha.group(1).lower() != expected.get('signing', {}).get('sha1', LEGACY_SIGNER):
        raise RuntimeError('APK signing identity changed; upgrade would be incompatible.')
    return {'package': fields['name'], 'versionName': fields['versionName'], 'versionCode': int(fields['versionCode']), 'signerSha1': sha.group(1).lower()}


def build(skip_tests=False, force_full=False, additions=()):
    plan = make_plan(force_full=force_full, additions=additions)
    if plan['documentationOnly']:
        raise RuntimeError('Documentation-only change: no new APK is needed.')
    v = version()
    ensure_new_release(v)
    directory = ROOT / 'build/candidates' / (v['versionName'] + '-' + uuid.uuid4().hex[:12])
    directory.mkdir(parents=True)
    before = inputs()
    write(directory / 'inputs.json', before)
    manifest = directory / 'candidate.json'
    data = dict(v, file=f"Qingxing-{v['versionName']}.apk", sourceSha256=fingerprint(before), builtAt=now(), state='building', tests={}, testPlan=plan, planSha256=hash_plan(plan))
    write(manifest, data)
    # The source manifest contains no duplicate version declarations.
    source = ET.fromstring((ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8'))
    if ANDROID + 'versionCode' in source.attrib or ANDROID + 'versionName' in source.attrib:
        raise RuntimeError('Source AndroidManifest must not duplicate the central version.')
    source.set(ANDROID + 'versionCode', str(v['versionCode']))
    source.set(ANDROID + 'versionName', v['versionName'])
    ET.register_namespace('android', ANDROID[1:-1])
    (directory / 'AndroidManifest.xml').write_bytes(ET.tostring(source, encoding='utf-8', xml_declaration=True))
    try:
        run_core('build-core.ps1', ['-SkipTests'] if skip_tests else [], directory / 'build.log', manifest)
        if inputs() != before:
            raise RuntimeError('Inputs changed during build; candidate rejected.')
        apk = directory / data['file']
        data.update(sha256=digest(apk), bytes=apk.stat().st_size, state='candidate', baseTests=not skip_tests, classesSha256=digest(ROOT / 'build/classes.jar'), apkMetadata=inspect_apk(apk, v))
        write(manifest, data)
        write(ROOT / 'build/current-candidate.json', {'manifest': str(manifest)})
        print(f'Candidate ready (not released): {manifest}', flush=True)
        return manifest
    except BaseException:
        data['state'] = 'failed'
        write(manifest, data)
        raise


def validate_report(name, content):
    if not name.endswith('crash-buffer.txt') and ('PASS ' not in content or 'FAIL' in content):
        raise RuntimeError('Missing passing test report: ' + name)
    if 'FATAL EXCEPTION' in content:
        raise RuntimeError('Crash recorded: ' + name)


def test(path, suite, serial='emulator-5556', build_only=False, preview=False):
    data, _ = validate_candidate(path)
    evidence = path.parent / 'evidence'
    evidence.mkdir(exist_ok=True)
    arguments = ['-TestSerial', serial]
    if build_only:
        arguments.append('-BuildOnly')
    if preview:
        arguments.append('-Preview')
    try:
        if suite in ('android', 'location', 'holiday'):
            core = 'test-android-core.ps1'; arguments += ['-Group', suite]
        elif suite.startswith('baidu-'):
            core = 'test-baidu-core.ps1'; arguments += ['-Scope', suite[6:]]
        elif suite == 'smoke-min':
            core = 'test-smoke-core.ps1'; arguments += ['-Minimal']
        elif suite == 'latest-upgrade':
            core = 'test-layout-upgrade-core.ps1'; arguments += ['-LatestOnly']
        else:
            core = 'test-' + suite + '-core.ps1'
        run_core(core, arguments, evidence / (suite + '.log'), path)
        validate_candidate(path)
        if build_only:
            return
        reports = {}
        for name in REPORTS[suite]:
            original = ROOT / 'docs' / name
            target = evidence / name
            shutil.copyfile(original, target)
            content = target.read_text(encoding='utf-8-sig')
            validate_report(name, content)
            reports[name] = digest(target)
        data = read(path)
        data['tests'][suite] = {'passed': True, 'at': now(), 'sha256': data['sha256'], 'sourceSha256': data['sourceSha256'], 'planSha256': data['planSha256'], 'reports': reports}
        write(path, data)
    except BaseException:
        data = read(path)
        data['tests'][suite] = {'passed': False, 'at': now()}
        write(path, data)
        raise


def suite_passed(path, data, suite):
    record = data.get('tests', {}).get(suite, {})
    if not all(data.get(k) for k in ('sha256', 'sourceSha256', 'planSha256')):
        return False
    if not record.get('passed') or any(record.get(k) != data.get(k) for k in ('sha256', 'sourceSha256', 'planSha256')):
        return False
    reports = record.get('reports', {})
    if set(reports) != set(REPORTS[suite]):
        return False
    try:
        return all(digest(path.parent / 'evidence' / name) == sha for name, sha in reports.items())
    except OSError:
        return False


def check_evidence(path, data):
    if not data.get('baseTests'):
        raise RuntimeError('Pure Java tests were skipped; rebuild with tests before releasing.')
    plan = data.get('testPlan', {})
    if not set(MANDATORY).issubset(plan.get('required', [])) or data.get('planSha256') != hash_plan(plan):
        raise RuntimeError('Missing or changed test plan; refusing release.')
    for suite in plan['required']:
        if not suite_passed(path, data, suite):
            raise RuntimeError('Release requires passing exact-APK evidence; missing, failed or evidence changed: ' + suite)


def release_candidate(path, serial='emulator-5556'):
    data, _ = validate_candidate(path)
    ensure_new_release(data)
    for suite in data['testPlan']['required']:
        data, _ = validate_candidate(path)
        if suite_passed(path, data, suite):
            print('Reuse verified exact-APK evidence: ' + suite, flush=True)
        else:
            print('Run required suite: ' + suite, flush=True)
            test(path, suite, serial)
    return promote(path)


def delivery_page(data):
    coverage = data.get('testPlan', {})
    note = '\n测试范围：' + ', '.join(coverage.get('required', [])) + '。未执行：' + ', '.join(coverage.get('skipped', {})) + '。\n' if coverage else ''
    download = f"https://github.com/zhanglegezhang/qingxing_android/releases/download/v{data['versionName']}/{data['file']}"
    installation = '直接覆盖安装，不要先卸载。'
    if data.get('signing'):
        installation = ('旧签名版本（1.1.2 及更早）需要重装。请先在旧版导出设置，卸载后安装本版，再导入设置并重新授权。'
                        '设置备份不含打卡记录。已安装新签名版本的用户可以直接覆盖升级。')
    return f"# 当前正式安装包\n\n[下载轻醒 {data['versionName']}]({download}) · [本机安装包](../{data['apk']})\n\n{installation}\n\n版本：{data['versionName']}（{data['versionCode']}）\n\nSHA256：`{data['sha256']}`\n\n[验证记录](../{data['verification']}) · [机器可读交付记录](latest.json)\n\n旧版本仅供追溯。正式下载入口只维护这一处。\n{note}"


def promote(path, root=ROOT):
    data, apk = validate_candidate(path)
    check_evidence(path, data)
    ensure_new_release(data, root)
    inspect_apk(apk, data)
    destination = root / 'dist/releases' / data['versionName']
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Staging stays on the same volume; failed tests never reach this point.
    staging = destination.with_name('.staging-' + uuid.uuid4().hex)
    staging.mkdir()
    try:
        shutil.copyfile(apk, staging / data['file'])
        shutil.copyfile(path.parent / 'inputs.json', staging / 'inputs.json')
        shutil.copyfile(path.parent / 'build.log', staging / 'build.log')
        shutil.copytree(path.parent / 'evidence', staging / 'evidence')
        (staging / (data['file'] + '.sha256')).write_text(data['sha256'] + '  ' + data['file'] + '\n', encoding='ascii')
        release = dict(data, releasedAt=now(), state='released', apk=(destination / data['file']).relative_to(root).as_posix(), verification=f"docs/VERIFICATION-{data['versionName']}.md")
        write(staging / 'release.json', release)
        if digest(staging / data['file']) != data['sha256']:
            raise RuntimeError('Copied APK checksum mismatch.')
        validate_candidate(path)
        if destination.exists():
            raise RuntimeError('Concurrent or duplicate release detected.')
        os.rename(staging, destination)  # No replacement of an existing release directory.
    except BaseException:
        # Preserve staging for diagnosis; never recursively delete an unchecked path.
        raise
    # latest.json is the authoritative atomic commit. Documentation is derived from it.
    write(root / 'release/latest.json', release)
    atomic(root / 'release/INSTALL.md', delivery_page(release))
    print('Released: ' + str(destination / data['file']), flush=True)
    return release


def status():
    latest = ROOT / 'release/latest.json'
    if not latest.exists():
        print('No release registered by the new workflow yet.')
        return
    data = read(latest)
    if digest(ROOT / data['apk']) != data['sha256']:
        raise RuntimeError('Published APK no longer matches latest.json!')
    atomic(ROOT / 'release/INSTALL.md', delivery_page(data))
    print(json.dumps({k: data[k] for k in ('versionName', 'versionCode', 'apk', 'sha256', 'releasedAt')}, ensure_ascii=True, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=('build', 'test', 'release', 'status', 'plan', 'workflow-check'))
    parser.add_argument('--candidate')
    parser.add_argument('--suite', choices=SUITES + ('baidu', 'android-all'))
    parser.add_argument('--full', action='store_true')
    parser.add_argument('--add-suite', choices=SUITES, action='append', default=[])
    parser.add_argument('--serial', default='emulator-5556')
    parser.add_argument('--skip-tests', action='store_true')
    parser.add_argument('--build-only', action='store_true')
    parser.add_argument('--preview', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        raise RuntimeError('Only emulator serials are permitted by this workflow.')
    with exclusive():
        if args.command == 'plan':
            print(json.dumps(make_plan(force_full=args.full, additions=args.add_suite), ensure_ascii=False, indent=2))
        elif args.command == 'workflow-check':
            subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tests', '-p', 'test_*.py', '-v'], cwd=ROOT, check=True)
        elif args.command == 'build':
            build(args.skip_tests, args.full, args.add_suite)
        elif args.command == 'status':
            status()
        else:
            path = candidate_path(args.candidate)
            if args.command == 'test':
                if not args.suite:
                    raise RuntimeError('--suite required')
                suites = ('android', 'location', 'holiday') if args.suite == 'android-all' else ('baidu-location', 'baidu-map', 'baidu-address') if args.suite == 'baidu' else (args.suite,)
                for suite in suites:
                    test(path, suite, args.serial, args.build_only, args.preview)
            else:
                data, _ = validate_candidate(path)
                if args.full or args.add_suite:
                    old = data['testPlan']
                    plan = make_plan(force_full=args.full or old['forcedFull'], additions=set(old['additions']) | set(args.add_suite))
                    if plan != old:
                        data.update(testPlan=plan, planSha256=hash_plan(plan))
                        write(path, data)  # Changing coverage invalidates previous plan-bound evidence.
                release_candidate(path, args.serial)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        sys.exit(1)
