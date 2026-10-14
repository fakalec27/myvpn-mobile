"""Reviewed REALITY key-share compatibility backport; no verification bypass.

Sources: Leadaxe/sing-box-lx commit159922a4e (GPL core) and pinned
utls-lx ad69b191e2f83367ec0ac5f3a1a61310732c43b9 (Go/uTLS BSD license).
Rebuild from original core HEAD; never stack patches on generated code.
"""
import argparse,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--core',required=True);args=p.parse_args()
core=pathlib.Path(args.core).resolve();compat=core.parent/'compat'/'utls'
def head(repo):return subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD'],text=True).strip()
if head(core)!='7054cac5124465ffdb145870d7c01885566711b1':raise RuntimeError('Unexpected core source revision')
if head(compat)!='ad69b191e2f83367ec0ac5f3a1a61310732c43b9':raise RuntimeError('Unexpected compatibility source revision')
if subprocess.check_output(['git','-C',str(compat),'status','--porcelain','--untracked-files=all']).strip():
    raise RuntimeError('Compatibility runtime has local changes; a revision marker alone is insufficient')
def original(name):return subprocess.check_output(['git','-C',str(core),'show','HEAD:'+name],text=True)
def write(name,text):(core/name).write_text(text,encoding='utf-8',newline='\n')
name='common/tls/reality_client.go';text=original(name)
start=text.index('\tfor _, extension := range uConn.Extensions {\n\t\tif ce, ok := extension.(*utls.SupportedCurvesExtension)')
end=text.index('\n\tif len(uConfig.NextProtos) > 0 {',start)
assert text[start:end].count('X25519MLKEM768')==2
text=text[:start]+text[end:]
text=text.replace('\n\t"github.com/sagernet/sing/common"','')
old='\tecdheKey := keyShareKeys.Ecdhe\n\tif ecdheKey == nil {'
assert old in text
text=text.replace(old,'\tecdheKey := keyShareKeys.Ecdhe\n\tif ecdheKey == nil {\n\t\tecdheKey = keyShareKeys.MlkemEcdhe\n\t}\n\tif ecdheKey == nil {',1)
old='hello.SessionId[0] = 1\n\thello.SessionId[1] = 8\n\thello.SessionId[2] = 1'
assert old in text
text=text.replace(old,'hello.SessionId[0] = 26\n\thello.SessionId[1] = 9\n\thello.SessionId[2] = 30')
write(name,text)
write('go.mod',original('go.mod')+'\n// Pinned local compatibility fork; see android/compat/utls LICENSE.\nreplace github.com/metacubex/utls => ../compat/utls\n')
print('Pinned REALITY hybrid/Firefox148 compatibility backport applied; verification retained.')
