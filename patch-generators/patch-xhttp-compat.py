"""Pinned GPL XHTTP backport from sing-box-lx; preserves upstream other protocols.

Acceptance lab: official Xray old/new, Chrome/Firefox, authenticated HTTPS
and wrong-user controls. Source/LICENSE must accompany distributed binaries.
"""
import argparse, pathlib, subprocess

REVISION = '411cc672e7700c2d6b697f937f5dad9f01b29be8'
BASE = '7054cac5124465ffdb145870d7c01885566711b1'
parser = argparse.ArgumentParser()
parser.add_argument('--core', required=True)
args = parser.parse_args()
core = pathlib.Path(args.core).resolve()
source = core.parent / 'compat' / 'sing-box-lx'

def git(repo, *arguments):
    return subprocess.check_output(['git', '-C', str(repo), *arguments])

if git(core, 'rev-parse', 'HEAD').decode().strip() != BASE:
    raise RuntimeError('Unexpected base core revision')
if git(source, 'rev-parse', 'HEAD').decode().strip() != REVISION:
    raise RuntimeError('Unexpected XHTTP source revision')

files = [
    'constant/v2ray.go', 'option/v2ray_transport.go',
    'option/v2ray_xhttp.go', 'option/v2ray_xhttp_xmux_range.go',
    'include/v2rayxhttp.go', 'transport/v2ray/transport.go',
    'transport/v2ray/registry.go', 'common/tls/std_config_quic_lx.go',
    'common/tls/utls_client_std_lx.go',
]
for directory in ['transport/v2rayxhttp', 'common/badh2']:
    files.extend(git(source, 'ls-tree', '-r', '--name-only', REVISION, '--', directory).decode().splitlines())
for name in files:
    if not name.endswith('.go'):
        raise RuntimeError('Unexpected backport source file')
    target = core / name
    target.parent.mkdir(parents=True, exist_ok=True)
    content = git(source, 'show', REVISION + ':' + name)
    if name == 'option/v2ray_transport.go':
        content = content.replace(b'enum:"http,ws,quic,grpc,httpupgrade"', b'enum:"http,ws,quic,grpc,httpupgrade,xhttp"')
    target.write_bytes(content)
print('Pinned XHTTP source backport applied; production enablement requires native build and device acceptance.')
