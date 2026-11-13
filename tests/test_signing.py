"""Exercise signing credentials without exposing passwords or replacing releases."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
POWERSHELL = shutil.which('pwsh') or shutil.which('powershell')


@unittest.skipUnless(POWERSHELL, 'PowerShell is required for the Windows signing workflow')
class LocalSigning(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='qingxing signing ')
        self.root = Path(self.temp.name)
        self.private = self.root / '.tooling'
        self.private.mkdir()
        (self.private / 'personal.keystore').write_bytes(b'placeholder for argument checks')

    def tearDown(self):
        self.temp.cleanup()

    def arguments(self, root):
        code = "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); $ErrorActionPreference='Stop'; . $env:QINGXING_SIGNING_SCRIPT; @(Get-QingxingSigningArguments -ProjectRoot $env:QINGXING_SIGNING_TEST_ROOT) | ConvertTo-Json -Compress"
        return subprocess.run([POWERSHELL, '-NoProfile', '-NonInteractive', '-Command', code],
                              env=os.environ | {'QINGXING_SIGNING_SCRIPT': str(ROOT / 'scripts/signing.ps1'),
                                                'QINGXING_SIGNING_TEST_ROOT': str(root)},
                              capture_output=True, encoding='utf-8', errors='replace', timeout=30)

    def test_missing_password_fails_without_creating_credentials(self):
        result = self.arguments(self.root)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Missing local signing-store-password.txt', result.stderr)
        self.assertFalse((self.private / 'signing-store-password.txt').exists())

    def test_store_password_is_passed_as_file_path_including_spaces(self):
        password = self.private / 'signing-store-password.txt'
        password.write_text('dummy-store-test-value\n', encoding='utf-8')
        key = self.private / 'signing-key-password.txt'
        key.write_text('dummy-store-test-value\n', encoding='utf-8')
        result = self.arguments(self.root)
        self.assertEqual(result.returncode, 0, result.stderr)
        arguments = json.loads(result.stdout)
        self.assertEqual(arguments[arguments.index('--ks-pass') + 1], 'file:' + str(password))
        self.assertEqual(arguments[arguments.index('--key-pass') + 1], 'file:' + str(key))
        self.assertNotIn('dummy-store-test-value', result.stdout + result.stderr)
        self.assertFalse(any(argument.startswith('pass:') for argument in arguments))

    def test_separate_key_password_and_empty_files(self):
        store = self.private / 'signing-store-password.txt'
        key = self.private / 'signing-key-password.txt'
        store.write_text('dummy-store-test-value\n', encoding='utf-8')
        key.write_text('dummy-key-test-value\n', encoding='utf-8')
        result = self.arguments(self.root)
        self.assertEqual(result.returncode, 0, result.stderr)
        arguments = json.loads(result.stdout)
        self.assertEqual(arguments[arguments.index('--key-pass') + 1], 'file:' + str(key))
        self.assertNotIn('dummy-key-test-value', result.stdout + result.stderr)
        key.write_bytes(b'')
        self.assertNotEqual(self.arguments(self.root).returncode, 0)
        key.unlink()
        self.assertNotEqual(self.arguments(self.root).returncode, 0)
        key.write_text('dummy-key-test-value\n', encoding='utf-8')
        store.write_bytes(b'')
        self.assertNotEqual(self.arguments(self.root).returncode, 0)

    def test_real_local_signing_preserves_published_certificate(self):
        private = ROOT / '.tooling'
        source = ROOT / 'build/aligned.apk'
        if not all(path.exists() for path in [private / 'personal.keystore', private / 'signing-store-password.txt', private / 'signing-key-password.txt', source]):
            self.skipTest('Local private signing files and a previous unsigned build are required')
        jdk = next((private / 'jdk').glob('*/bin/java.exe'))
        signer = next((private / 'build-tools').rglob('apksigner.jar'))
        result = self.arguments(ROOT)
        self.assertEqual(result.returncode, 0, result.stderr)
        arguments = json.loads(result.stdout)
        with tempfile.TemporaryDirectory(prefix='signing-check-', dir=ROOT / 'build') as folder:
            output = Path(folder) / 'signing-check.apk'
            signed = subprocess.run([str(jdk), '-jar', str(signer), 'sign'] + arguments + ['--out', str(output), str(source)], capture_output=True, timeout=90)
            diagnostic = signed.stderr.decode('utf-8', 'replace')
            for name in ('signing-store-password.txt', 'signing-key-password.txt', 'baidu-ak.txt'):
                credential = private / name
                if credential.exists():
                    value = credential.read_text(encoding='utf-8-sig').strip()
                    if value:
                        diagnostic = diagnostic.replace(value, '[REDACTED]')
            self.assertEqual(signed.returncode, 0, diagnostic[:2000])
            checked = subprocess.run([str(jdk), '-jar', str(signer), 'verify', '--print-certs', str(output)], capture_output=True, timeout=90)
            self.assertEqual(checked.returncode, 0, 'Signed test copy did not verify')
            actual = re.search(r'Signer #1 certificate SHA-1 digest: ([0-9a-fA-F]+)', checked.stdout.decode('utf-8', 'replace'))
            version = json.loads((ROOT / 'release/version.json').read_text(encoding='utf-8-sig'))
            expected = version.get('signing', {}).get('sha1') or json.loads((ROOT / 'release/latest.json').read_text(encoding='utf-8-sig'))['apkMetadata']['signerSha1']
            self.assertIsNotNone(actual)
            self.assertEqual(actual.group(1).lower(), expected.lower())


if __name__ == '__main__':
    unittest.main(verbosity=2)
