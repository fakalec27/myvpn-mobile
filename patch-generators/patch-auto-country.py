"""Allow a managed empty Auto to fail closed while keeping manual nodes; emit safe transport events."""
import argparse,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--core',required=True);a=p.parse_args();root=pathlib.Path(a.core)
assert subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()=='7054cac5124465ffdb145870d7c01885566711b1'
def change(text,old,new):
 assert text.count(old)==1,'Auto country patch anchor drift: '+old[:60]
 return text.replace(old,new,1)
path=root/'protocol/group/urltest.go';s=path.read_text(encoding='utf-8')
s=change(s,'if len(outbound.tags) == 0 {','if len(outbound.tags) == 0 && !outbound.managedHealth {')
s=change(s,'import (','import (\n "crypto/sha256"\n "encoding/hex"')
s=change(s,'\t\t\t\tdelete(g.nodeConnections, tag)','\t\t\t\tg.journal("auto-node-close", "qualification", tag)\n\t\t\t\tdelete(g.nodeConnections, tag)')
s=change(s,'\tif g.managedHealth {\n\t\t// A faster healthy candidate','\tif g.managedHealth {\n\t\tif updated {g.journal("auto-selection", "performance_or_failure", "")}\n\t\t// A faster healthy candidate')
s+='''
func (g *URLTestGroup) journal(name, reason, tag string) {
 if g.logger == nil {return}
 digest := sha256.Sum256([]byte(tag))
 g.logger.Info("myvpn_event name=",name," reason=",reason," node=",hex.EncodeToString(digest[:6]))
}
'''
path.write_text(s,encoding='utf-8',newline='\n')
path=root/'daemon/started_service.go'
s=path.read_text(encoding='utf-8')
assert 'qualified.HealthHistory(itemTag)' in s, 'Qualified measurements must survive composition with the Auto patch'
start=s.index('func (s *StartedService) URLTest(')
end=s.index('\nfunc ',start+1)
block=s[start:end]
block=change(block,'go urlTest.CheckOutbounds()','_, err := urlTest.URLTest(ctx)\n\t\tif err != nil {return nil, err}')
block=change(block,'go group.URLTestOutbounds(boxService.ctx,','group.URLTestOutbounds(ctx,')
block=change(block,'\t\tgo func() {','\t\tfunc() {')
block=change(block,'urltest.URLTest(boxService.ctx,','urltest.URLTest(ctx,')
s=s[:start]+block+s[end:]
path.write_text(s,encoding='utf-8',newline='\n')
path=root/'protocol/ssh/outbound.go';s=path.read_text(encoding='utf-8')
s=change(s,'\t\t_ = active.Wait()\n\t\ts.invalidateClient(active)','\t\t_ = active.Wait()\n\t\ts.journal("ssh-reset", "eof")\n\t\ts.invalidateClient(active)')
s=change(s,'func (s *Outbound) InterfaceUpdated(ctx context.Context) {\n\ts.closeClient()','func (s *Outbound) InterfaceUpdated(ctx context.Context) {\n\ts.journal("ssh-reset", "interface_change")\n\ts.closeClient()')
s=change(s,'\t\t\tif err == nil {','\t\t\tif err != nil {s.journal("ssh-reset", "heartbeat_error")}\n\t\t\tif err == nil {')
s=change(s,'\t\tcase <-timer.C:\n\t\t}','\t\tcase <-timer.C:\n\t\t\ts.journal("ssh-reset", "heartbeat_timeout")\n\t\t}')
s+='''
func (s *Outbound) journal(name, reason string) {
 if s.logger != nil {s.logger.Info("myvpn_event name=",name," reason=",reason)}
}
'''
path.write_text(s,encoding='utf-8',newline='\n')
print('Empty managed Auto fail-closed and safe native diagnostics patch applied.')
