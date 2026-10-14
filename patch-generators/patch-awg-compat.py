"""Pinned AWG 2/3 lab backport; deliberately excludes fork idle/lazy/pool rewrites."""
import argparse,pathlib,re,subprocess
BASE='7054cac5124465ffdb145870d7c01885566711b1'
FORK='411cc672e7700c2d6b697f937f5dad9f01b29be8'
DEVICE='8a53421edf7edd2f60dbeab3bd6e49256eab4d04'
parser=argparse.ArgumentParser();parser.add_argument('--core',required=True);args=parser.parse_args()
core=pathlib.Path(args.core).resolve();source=core.parent/'compat/sing-box-lx';device=core.parent/'compat/wireguard-go-awg'
def git(repo,*args):return subprocess.check_output(['git','-C',str(repo),*args])
for repo,pin in [(core,BASE),(source,FORK),(device,DEVICE)]:
    if git(repo,'rev-parse','HEAD').decode().strip()!=pin:raise RuntimeError('Unexpected AWG source revision')
if git(device,'status','--porcelain','--untracked-files=all').strip():
    raise RuntimeError('AWG device runtime has local changes; a revision marker alone is insufficient')
def write(name,text):
    target=core/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8',newline='\n')
def original(name):return git(core,'show',BASE+':'+name).decode()
files=['option/wireguard.go','option/wireguard_awg.go','option/wireguard_awg_test.go','option/wireguard_awg3_test.go','transport/wireguard/masque_cps_test.go','transport/wireguard/awg_detour_clientbind_lx_test.go']
files+=[name for name in git(source,'ls-tree','-r','--name-only',FORK,'--','transport/wireguard').decode().splitlines() if name.endswith('.go') and '_awg' in pathlib.Path(name).stem]
for name in files:write(name,git(source,'show',FORK+':'+name).decode())
write('transport/wireguard/client_bind_packet_myvpn_test.go',(core.parent/'native-tests/client_bind_packet_myvpn_test.go').read_text(encoding='utf-8'))
for name in ['transport/wireguard/testdata/chrome_147_initial.bin','transport/wireguard/testdata/firefox_149_initial.bin','transport/wireguard/testdata/README.md']:
    target=core/name;target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(git(source,'show',FORK+':'+name))
name='transport/wireguard/endpoint_options.go';text=original(name)
text=text.replace('"github.com/sagernet/sing-tun"','"github.com/sagernet/sing-tun"\n\t"github.com/sagernet/sing-box/option"')
text,count=re.subn(r'(\tWorkers\s+int)',r'\1\n\tAmneziaWG option.AmneziaWGOptions',text,count=1);assert count==1
text,count=re.subn(r'(PersistentKeepaliveInterval\s+)uint16',r'\1option.AWGRange',text,count=1);assert count==1
write(name,text)
name='protocol/wireguard/endpoint.go';text=original(name)
anchor='\t\tWorkers: options.Workers,';assert text.count(anchor)==1
text=text.replace(anchor,anchor+'\n\t\tAmneziaWG: options.AmneziaWGOptions,')
write(name,text)
name='transport/wireguard/client_bind.go';text=original(name)
anchor='func (c *ClientBind) connect()';assert text.count(anchor)==1
text=text.replace(anchor,'''// Reserved bytes are a WARP extension, not spare bytes in AWG packets.
func (c *ClientBind) hasReserved() bool {
    c.reservedAccess.RLock()
    defer c.reservedAccess.RUnlock()
    if c.reserved != [3]uint8{} { return true }
    for _, value := range c.reservedForEndpoint { if value != [3]uint8{} { return true } }
    return false
}

'''+anchor)
text=text.replace('if n > 3 {','if n > 3 && c.hasReserved() {',1)
text=text.replace('copy(buf[1:4], reserved[:])','if reserved != [3]uint8{} { copy(buf[1:4], reserved[:]) }',1)
write(name,text)
name='transport/wireguard/endpoint.go';text=original(name)
anchor='\tvar peers []peerConfig';assert text.count(anchor)==1
text=text.replace(anchor,'''\tawgLines, err := awgIpcLines(options.AmneziaWG)
    if err != nil { return nil, err }
    ipcConf += awgLines
'''+anchor)
anchor='\tfor peerIndex, rawPeer := range options.Peers {';assert text.count(anchor)==1
text=text.replace(anchor,anchor+'''\n        keepalive, err := awgKeepaliveSpec(rawPeer.PersistentKeepaliveInterval)
        if err != nil { return nil, E.Cause(err, "persistent keepalive for peer ", peerIndex) }
''')
text=text.replace('keepalive:  rawPeer.PersistentKeepaliveInterval,','keepalive:  keepalive,')
text,count=re.subn(r'(\tkeepalive\s+)uint16',r'\1string',text,count=1);assert count==1
text=text.replace('if c.keepalive > 0 {','if c.keepalive != "" {')
text=text.replace('options.MTU = 1408','options.MTU = 1408\n        if options.AmneziaWG.S4>0 { options.MTU=1280 }')
anchor='\twgDevice := device.NewDevice(e.options.Context, e.returnDevice, bind, logger, e.options.Workers)';assert text.count(anchor)==1
text=text.replace(anchor,anchor+'''\n    if decoy := awgDecoyFunc(e.options.AmneziaWG, e.options.Logger); decoy != nil { wgDevice.SetDecoyPacketsFunc(decoy) }
''')
write(name,text)
text=(core/'go.mod').read_text(encoding='utf-8')
anchor='replace github.com/sagernet/wireguard-go =>'
replacement=anchor+' ../compat/wireguard-go-awg'
existing=[line.strip() for line in text.splitlines() if line.strip().startswith(anchor)]
if existing and existing!=[replacement]:raise RuntimeError('Existing WireGuard replacement requires review')
write('go.mod',text if existing else text+'\n'+replacement+'\n')
print('Pinned AWG compatibility applied; no idle/lazy/pool changes copied.')
