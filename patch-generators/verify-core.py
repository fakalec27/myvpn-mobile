"""Fail release builds/publication if the native core ABI or provenance differs."""
import argparse,hashlib,json,pathlib,zipfile,struct
VERSION='1.14.3-myvpn-mobile15'
REVISION='7054cac5124465ffdb145870d7c01885566711b1'
COMPATIBILITY={'utls':'ad69b191e2f83367ec0ac5f3a1a61310732c43b9','xhttp':'411cc672e7700c2d6b697f937f5dad9f01b29be8','vless_encryption':'411cc672e7700c2d6b697f937f5dad9f01b29be8','awg':'411cc672e7700c2d6b697f937f5dad9f01b29be8','wireguard_awg':'8a53421edf7edd2f60dbeab3bd6e49256eab4d04'}
def inspect(path,abi,apk=False):
 with zipfile.ZipFile(path) as z:
  prefix='lib/' if apk else 'jni/'
  entries=[n for n in z.namelist() if n.startswith(prefix) and n.endswith('/libbox.so')]
  expected=prefix+abi+'/libbox.so'
  if entries!=[expected]:raise RuntimeError('Native core ABI missing or ambiguous')
  binary=z.read(expected)
  if VERSION.encode() not in binary:raise RuntimeError('Native core version stamp mismatch')
  # AGP strips debug sections; hash every allocated ELF section instead of debug metadata.
  if binary[:6]!=b'\x7fELF\x02\x01':raise RuntimeError('Expected little-endian ELF64 core')
  if struct.unpack_from('<H',binary,18)[0] != (183 if abi=='arm64-v8a' else 62):raise RuntimeError('ELF architecture mismatch')
  shoff=struct.unpack_from('<Q',binary,40)[0]
  entsize,count,names_index=struct.unpack_from('<HHH',binary,58)
  sections=[struct.unpack_from('<IIQQQQIIQQ',binary,shoff+i*entsize) for i in range(count)]
  names=sections[names_index];strings=binary[names[4]:names[4]+names[5]]
  digest=hashlib.sha256()
  for section in sections:
   if not section[2]&2:continue
   name=strings[section[0]:].split(b'\0',1)[0]
   digest.update(name+b'\0');digest.update(struct.pack('<Q',section[5]))
   if section[1]!=8:digest.update(binary[section[4]:section[4]+section[5]])
  return digest.hexdigest()
def verify(path,abi,contract,apk=False):
 value=json.loads(pathlib.Path(contract).read_text())
 if value['schema']!=1 or value['abi']!=abi or value['revision']!=REVISION or value['version']!=VERSION:raise RuntimeError('Core provenance contract mismatch')
 if value.get('compatibility')!=COMPATIBILITY:raise RuntimeError('Core compatibility provenance mismatch')
 if inspect(path,abi,apk)!=value['nativeSha256']:raise RuntimeError('Core binary hash mismatch')
 if not apk and hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()!=value['aarSha256']:raise RuntimeError('Core AAR changed after build')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--abi',required=True,choices=['arm64-v8a','x86_64']);p.add_argument('--contract',required=True)
 p.add_argument('--aar');p.add_argument('--apk');p.add_argument('--record',action='store_true');a=p.parse_args()
 if bool(a.aar)==bool(a.apk):raise RuntimeError('Select exactly one artifact')
 if a.record:
  if not a.aar:raise RuntimeError('Only a freshly built AAR can record provenance')
  value={'schema':1,'abi':a.abi,'version':VERSION,'revision':REVISION,'compatibility':COMPATIBILITY,'nativeSha256':inspect(a.aar,a.abi),'aarSha256':hashlib.sha256(pathlib.Path(a.aar).read_bytes()).hexdigest()}
  pathlib.Path(a.contract).write_text(json.dumps(value,indent=2)+'\n')
 else:verify(a.aar or a.apk,a.abi,a.contract,bool(a.apk))
 print('Native core ABI, version and hash contract verified: '+a.abi)
