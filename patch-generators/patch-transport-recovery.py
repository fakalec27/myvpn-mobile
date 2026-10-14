"""Pinned managed-node retention and generation-safe bounded SSH recovery.
Run after patch-autoselect-core.py; legacy URLTest semantics stay unchanged.
"""
import argparse, pathlib, subprocess
p=argparse.ArgumentParser();p.add_argument('--core',required=True);a=p.parse_args();core=pathlib.Path(a.core)
assert subprocess.check_output(['git','-C',str(core),'rev-parse','HEAD'],text=True).strip()=='7054cac5124465ffdb145870d7c01885566711b1'
def change(text,old,new):
    assert text.count(old)==1, 'Transport patch anchor mismatch: '+old[:90]
    return text.replace(old,new,1)
def write(name,text): (core/name).write_text(text,encoding='utf-8',newline='\n')
name='protocol/group/urltest.go';text=(core/name).read_text(encoding='utf-8')
text=change(text,'type URLTestGroup struct {','type URLTestGroup struct {\n\tnodeConnections map[string]*interrupt.Group')
text=change(text,'\treturn s.group.loadHistory(tag), true','\thistory := s.group.loadHistory(tag)\n\tif !s.group.usable(history) { return nil, true }\n\treturn history, true')
text=change(text,'return s.group.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil','return s.group.trackConn(outbound, conn, interrupt.IsExternalConnectionFromContext(ctx)), nil')
text=change(text,'return s.group.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil','return s.group.trackPacket(outbound, conn, interrupt.IsExternalConnectionFromContext(ctx)), nil')
text=change(text,'func (g *URLTestGroup) Close() error {','''func (g *URLTestGroup) Close() error {
\tif g.managedHealth {
\t\tvar closeGroups []*interrupt.Group
\t\tg.updateAccess.Lock()
\t\tfor tag, connections := range g.nodeConnections { closeGroups = append(closeGroups, connections); delete(g.nodeConnections, tag) }
\t\tg.updateAccess.Unlock()
\t\tfor _, connections := range closeGroups { connections.Interrupt(true) }
\t}''')
text=change(text,'func (g *URLTestGroup) performUpdateCheck() {\n\tg.updateAccess.Lock()\n\tdefer g.updateAccess.Unlock()', '''func (g *URLTestGroup) performUpdateCheck() {
\tvar closeGroups []*interrupt.Group
\tg.updateAccess.Lock()
\tdefer func() {
\t\tg.updateAccess.Unlock()
\t\tfor _, connections := range closeGroups { connections.Interrupt(true) }
\t}()''')
text=change(text,'\tif updated {\n\t\tg.logger.Info("verified auto candidate changed; reconnecting affected group")\n\t\tg.interruptGroup.Interrupt(g.interruptExternalConnections)\n\t}', '''\tif g.managedHealth {
\t\t// A faster healthy candidate affects future dials only. Retained old nodes
\t\t// are still reaped when their qualification disappears or expires.
\t\tfor tag, connections := range g.nodeConnections {
\t\t\tif !g.usable(g.loadHistory(tag)) {
\t\t\t\tcloseGroups = append(closeGroups, connections)
\t\t\t\tdelete(g.nodeConnections, tag)
\t\t\t}
\t\t}
\t} else if updated {
\t\tg.interruptGroup.Interrupt(g.interruptExternalConnections)
\t}''')
text+='''
// Registration and qualification are checked under the same lock as reselection:
// a late dial completing after invalidation must not resurrect a failed path.
func (g *URLTestGroup) trackConn(node adapter.Outbound, conn net.Conn, external bool) net.Conn {
\tif !g.managedHealth { return g.interruptGroup.NewConn(conn, external) }
\tg.updateAccess.Lock()
\ttag := RealTag(g.outbound, node)
\tif !g.usable(g.loadHistory(tag)) { g.updateAccess.Unlock(); _ = conn.Close(); return conn }
\ttracked := g.nodeGroup(tag).NewConn(conn, external)
\tg.updateAccess.Unlock()
\treturn tracked
}
func (g *URLTestGroup) trackPacket(node adapter.Outbound, conn net.PacketConn, external bool) net.PacketConn {
\tif !g.managedHealth { return g.interruptGroup.NewPacketConn(conn, external) }
\tg.updateAccess.Lock()
\ttag := RealTag(g.outbound, node)
\tif !g.usable(g.loadHistory(tag)) { g.updateAccess.Unlock(); _ = conn.Close(); return conn }
\ttracked := g.nodeGroup(tag).NewPacketConn(conn, external)
\tg.updateAccess.Unlock()
\treturn tracked
}
// Caller holds updateAccess.
func (g *URLTestGroup) nodeGroup(tag string) *interrupt.Group {
\tif g.nodeConnections == nil { g.nodeConnections = make(map[string]*interrupt.Group) }
\tconnections := g.nodeConnections[tag]
\tif connections == nil { connections = interrupt.NewGroup(); g.nodeConnections[tag] = connections }
\treturn connections
}
'''
write(name,text)
name='protocol/ssh/outbound.go';text=subprocess.check_output(['git','-C',str(core),'show','HEAD:'+name],text=True)
text=change(text,'\t"context"','\t"context"\n\t"errors"')
text=change(text,'\tcloseIdle         bool','\tcloseIdle         bool\n\tclientDone chan struct{}\n\tlivenessNeeded bool')
text=change(text,'\t\t\t\ts.client = nil\n\t\t\t\ts.clientConn = nil','\t\t\t\ts.clearClientLocked()')
text=change(text,'\ts.client = client\n\ts.streams = 1','\ts.client = client\n\ts.streams = 1\n\tdone := make(chan struct{})\n\ts.clientDone = done\n\tgo s.keepAlive(client, done, 30*time.Second, 10*time.Second)')
start=text.index('\tgo func() {\n\t\tclient.Wait()');end=text.index('\n\treturn client, nil',start)
text=text[:start]+'''\tgo func(active *ssh.Client) {
\t\t_ = active.Wait()
\t\ts.invalidateClient(active)
\t}(client)
'''+text[end:]
text=change(text,'func (s *Outbound) InterfaceUpdated(ctx context.Context) {\n\tcommon.Close(s.clientConn)\n}', '''func (s *Outbound) InterfaceUpdated(ctx context.Context) {
\ts.closeClient()
}''')
old='\tclientConn := s.clientConn\n\ts.client = nil\n\ts.clientConn = nil\n\ts.clientAccess.Unlock()\n\tcommon.Close(clientConn)'
assert text.count(old)==2
text=text.replace(old,'\tclientConn := s.clientConn\n\ts.clearClientLocked()\n\ts.clientAccess.Unlock()\n\tcommon.Close(clientConn)')
text=change(text,'\t\treturn common.Close(s.clientConn)','\t\ts.closeClient()\n\t\treturn nil')
text=change(text,'\tclient, err := s.connect(ctx)','\tdialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)\n\tdefer cancel()\n\tclient, err := s.connect(dialCtx)')
text=change(text,'\tconn, err := client.Dial(network, destination.String())','\tconn, err := client.DialContext(dialCtx, network, destination.String())')
text=change(text,'\t\ts.releaseStream(client, false)','''\t\tif errors.Is(err, context.DeadlineExceeded) {
\t\t\ts.clientAccess.Lock()
\t\t\tif s.client == client { s.livenessNeeded = true }
\t\t\ts.clientAccess.Unlock()
\t\t}
\t\ts.releaseStream(client, false)''')
text+='''
// Caller holds clientAccess. Never log credentials or transport destinations.
func (s *Outbound) clearClientLocked() {
\tif s.clientDone != nil { close(s.clientDone); s.clientDone = nil }
\ts.client = nil
\ts.clientConn = nil
\ts.streams = 0
\ts.livenessNeeded = false
}
func (s *Outbound) invalidateClient(client *ssh.Client) {
\ts.clientAccess.Lock()
\tif s.client != client { s.clientAccess.Unlock(); return }
\tconn := s.clientConn
\ts.clearClientLocked()
\ts.clientAccess.Unlock()
\tif client != nil { _ = client.Close() }
\t_ = common.Close(conn)
}
func (s *Outbound) closeClient() {
\ts.clientAccess.Lock()
\tclient, conn := s.client, s.clientConn
\ts.clearClientLocked()
\ts.clientAccess.Unlock()
\tif client != nil { _ = client.Close() }
\t_ = common.Close(conn)
}
func (s *Outbound) keepAlive(client *ssh.Client, done <-chan struct{}, interval, timeout time.Duration) {
\tticker := time.NewTicker(interval)
\tdefer ticker.Stop()
\tfor {
\t\tselect { case <-done: return; case <-ticker.C: }
\t\ts.clientAccess.Lock()
\t\tactive := s.client == client && (s.streams > 0 || s.livenessNeeded)
\t\ts.clientAccess.Unlock()
\t\tif !active { continue }
\t\treply := make(chan error, 1)
\t\tgo func() {
\t\t\t// OpenSSH may reject this request. A negative reply still proves liveness.
\t\t\t_, _, err := client.SendRequest("keepalive@openssh.com", true, nil)
\t\t\treply <- err
\t\t}()
\t\ttimer := time.NewTimer(timeout)
\t\tselect {
\t\tcase <-done: timer.Stop(); return
\t\tcase err := <-reply:
\t\t\ttimer.Stop()
\t\t\tif err == nil {
\t\t\t\ts.clientAccess.Lock()
\t\t\t\tif s.client == client { s.livenessNeeded = false }
\t\t\t\ts.clientAccess.Unlock()
\t\t\t\tcontinue
\t\t\t}
\t\tcase <-timer.C:
\t\t}
\t\ts.invalidateClient(client)
\t\treturn
\t}
}
'''
write(name,text)
print('Managed transport retention and SSH recovery patch applied.')
