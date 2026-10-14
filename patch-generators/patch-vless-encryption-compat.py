"""Pinned GPL client encryption backport; requires native and device acceptance."""
import argparse
import pathlib
import re
import subprocess

REVISION='411cc672e7700c2d6b697f937f5dad9f01b29be8'
BASE='7054cac5124465ffdb145870d7c01885566711b1'
parser=argparse.ArgumentParser()
parser.add_argument('--core',required=True)
args=parser.parse_args()
core=pathlib.Path(args.core).resolve()
source=core.parent/'compat/sing-box-lx'
def git(repo,*args):
    return subprocess.check_output(['git','-C',str(repo),*args])
if git(core,'rev-parse','HEAD').decode().strip()!=BASE or git(source,'rev-parse','HEAD').decode().strip()!=REVISION:
    raise RuntimeError('Unexpected encryption source revision')
files=['protocol/vless/lx_encryption.go','protocol/vless/lx_encryption_test.go',
       'protocol/vless/lx_encryption_guard_test.go','protocol/vless/lx_encryption_deadline_test.go']
files+=git(source,'ls-tree','-r','--name-only',REVISION,'--','protocol/vless/encryption').decode().splitlines()
for name in files:
    if not name.endswith('.go'):raise RuntimeError('Unexpected encryption source file')
    target=core/name
    target.parent.mkdir(parents=True,exist_ok=True)
    body=git(source,'show',REVISION+':'+name)
    # Errors must not echo raw provider key material into client logs.
    if name.endswith('lx_encryption.go'):
        body=body.replace(b'E.New("invalid encryption key (not base64url): ", segment)',b'E.New("invalid encryption key (not base64url)")')
    target.write_bytes(body)
name='option/vless.go'
text=git(core,'show',BASE+':'+name).decode()
# VLESSUser also has Flow; change only the outbound options.
start=text.index('type VLESSOutboundOptions struct')
tail,count=re.subn(r'(\tFlow\s+string\s+`json:"flow,omitempty"`)',r'\1\n\tEncryption string `json:"encryption,omitempty"`',text[start:],count=1)
assert count==1, 'Outbound encryption field anchor missing'
text=text[:start]+tail
(core/name).write_text(text,encoding='utf-8',newline='\n')
name='protocol/vless/outbound.go'
text=git(core,'show',BASE+':'+name).decode()
text=text.replace('"github.com/sagernet/sing-box/option"','"github.com/sagernet/sing-box/option"\n\t"github.com/sagernet/sing-box/protocol/vless/encryption"')
text,count=re.subn(r'(\txudp\s+bool)',r'\1\n\tencryption *encryption.ClientInstance',text,count=1)
assert count==1, 'Outbound encryption state anchor missing'
anchor='\toutbound.client, err = vless.NewClient'
assert text.count(anchor)==1
text=text.replace(anchor,'''\tif options.Encryption != "" && options.Encryption != "none" {
        config, err := parseClientEncryption(options.Encryption)
        if err != nil { return nil, E.Cause(err, "parse encryption") }
        outbound.encryption = &encryption.ClientInstance{}
        if err := outbound.encryption.Init(config.keys, config.xorMode, config.seconds, config.padding); err != nil {
            return nil, E.Cause(err, "initialize encryption")
        }
    }
'''+anchor)
start=text.index('func (h *vlessDialer) DialContext')
head=text[:start]
tail=text[start:]
for anchor in ['\tswitch N.NetworkName(network) {','\tif h.xudp {']:
    # The first XUDP branch belongs to DialContext; anchor ListenPacket separately.
    if anchor.startswith('\tif'):
        index=tail.index('func (h *vlessDialer) ListenPacket')
        prefix=tail[:index];section=tail[index:]
        assert anchor in section
        tail=prefix+section.replace(anchor,'\tconn, err = h.wrapEncryption(ctx, conn)\n\tif err != nil { return nil, err }\n'+anchor,1)
    else:
        assert anchor in tail
        tail=tail.replace(anchor,'\tconn, err = h.wrapEncryption(ctx, conn)\n\tif err != nil { return nil, err }\n'+anchor,1)
(core/name).write_text(head+tail,encoding='utf-8',newline='\n')
print('Pinned VLESS encryption client applied; device acceptance is required before release.')
