"""Pinned, explicit opt-in health/failover contract for managed Android URLTest groups."""
import argparse,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--core',required=True);a=p.parse_args();core=pathlib.Path(a.core)
def original(name):return subprocess.check_output(['git','-C',str(core),'show','HEAD:'+name],text=True)
def write(name,text):(core/name).write_text(text,encoding='utf-8',newline='\n')
assert subprocess.check_output(['git','-C',str(core),'rev-parse','HEAD'],text=True).strip()=='7054cac5124465ffdb145870d7c01885566711b1'
name='option/group.go';text=original(name)
text=text.replace('type URLTestOutboundOptions struct {','type URLTestOutboundOptions struct {\n\tMyVPNHealthCheck bool `json:"myvpn_health_check,omitempty"`\n\tMyVPNHealthURLs []string `json:"myvpn_health_urls,omitempty"`')
text=text.replace('type SelectorOutboundOptions struct {','type SelectorOutboundOptions struct {\n\tMyVPNPreserveSelection bool `json:"myvpn_preserve_selection,omitempty"`',1)
write(name,text)
name='option/experimental.go';text=original(name)
text=text.replace('type CacheFileOptions struct {','type CacheFileOptions struct {\n\tMyVPNLegacySelectionMigration bool `json:"myvpn_legacy_selection_migration,omitempty"`',1)
write(name,text)
name='experimental/cachefile/cache.go';text=original(name)
text=text.replace('type CacheFile struct {','type CacheFile struct {\n\tselectionMigration bool',1)
text=text.replace('\t\tcacheIDText:  options.CacheID,','\t\tcacheIDText:  options.CacheID,\n\t\tselectionMigration: options.MyVPNLegacySelectionMigration,',1)
start=text.index('func (c *CacheFile) LoadSelected(');end=text.index('\nfunc ',start+1)
text=text[:start]+'''func (c *CacheFile) LoadSelected(group string) string {
\tvar selected string
\tc.view(func(t *bbolt.Tx) error {
\t\tif bucket := c.bucket(t,bucketSelected); bucket != nil {
\t\t\tif value := bucket.Get([]byte(group)); len(value)>0 { selected=string(value); return nil }
\t\t}
\t\tif c.selectionMigration && c.cacheID != nil {
\t\t\tif bucket := t.Bucket(bucketSelected); bucket != nil { selected=string(bucket.Get([]byte(group))) }
\t\t}
\t\treturn nil
\t})
\treturn selected
}

func (c *CacheFile) SelectionMigrationEnabled() bool { return c.selectionMigration }
'''+text[end:]
write(name,text)
name='protocol/group/selector.go';text=original(name)
text=text.replace('type Selector struct {','type Selector struct {\n\tpreserveSelection bool',1)
text=text.replace('\t\ttags:                         options.Outbounds,','\t\tpreserveSelection:             options.MyVPNPreserveSelection,\n\t\ttags:                         options.Outbounds,',1)
text=text.replace('\t\t\t\t\ts.selected.Store(detour)\n\t\t\t\t\treturn nil','''\t\t\t\t\ts.selected.Store(detour)
\t\t\t\t\ts.persistInitialChoice(detour.Tag())
\t\t\t\t\ts.logger.Info("selector choice restored from cache")
\t\t\t\t\treturn nil''',1)
anchor='\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n\n\tif s.defaultTag != ""'
assert anchor in text
text=text.replace(anchor,'''\t\t\t\t}
\t\t\t\tif s.preserveSelection { return E.New("saved VPN choice is no longer available; choose Auto or an available server before updating") }
\t\t\t\ts.logger.Warn("saved selector choice unavailable; using profile default")
\t\t\t}
\t\t}
\t}

\tif s.defaultTag != ""''',1)
text=text.replace('\t\ts.selected.Store(detour)\n\t\treturn nil','\t\ts.selected.Store(detour)\n\t\ts.persistInitialChoice(detour.Tag())\n\t\ts.logger.Info("selector choice initialized from profile default")\n\t\treturn nil',1)
text=text.replace('\ts.selected.Store(s.outbounds[s.tags[0]])','\ts.selected.Store(s.outbounds[s.tags[0]])\n\ts.persistInitialChoice(s.tags[0])',1)
text+='''
func (s *Selector) persistInitialChoice(tag string) {
\tcache := service.FromContext[adapter.CacheFile](s.ctx)
\tif migration, ok := cache.(interface { SelectionMigrationEnabled() bool }); ok && migration.SelectionMigrationEnabled() {
\t\tif err := cache.StoreSelected(s.Tag(),tag); err != nil { s.logger.Error("cannot persist initial selector mode") }
\t}
}
'''
write(name,text)
name='common/urltest/urltest.go';text=original(name)
old='\tresp.Body.Close()\n\tt ='
assert old in text
text=text.replace(old,'''\tresp.Body.Close()
\tif resp.StatusCode < 200 || resp.StatusCode >= 300 || (linkURL.Path == "/generate_204" && resp.StatusCode != 204) {
\t\treturn 0, fmt.Errorf("health endpoint returned HTTP %d", resp.StatusCode)
\t}

\tt =''')
text=text.replace('import (','import (\n\t"fmt"',1);write(name,text)
text=text.replace('\tt = uint16(time.Since(start) / time.Millisecond)','\tt = uint16(time.Since(start) / time.Millisecond)\n\tif t == 0 { t = 1 }',1);write(name,text)
name='protocol/group/urltest.go';text=original(name)
text=text.replace('\tcheckAccess                  sync.Mutex','\tcheckAccess                  sync.Mutex\n\tmanagedHealth                bool')
text=text.replace('\tmanagedHealth                bool','\tmanagedHealth                bool\n\thealthURLs                   []string',1)
text=text.replace('\t\ttags:                         options.Outbounds,','\t\tmanagedHealth:                options.MyVPNHealthCheck,\n\t\ttags:                         options.Outbounds,',1)
text=text.replace('\t\tmanagedHealth:                options.MyVPNHealthCheck,','\t\tmanagedHealth:                options.MyVPNHealthCheck,\n\t\thealthURLs:                   options.MyVPNHealthURLs,',1)
text=text.replace('\tif len(outbound.tags) == 0 {','''\tif outbound.managedHealth && outbound.link != "" {
\t\tparsed, err := url.Parse(outbound.link)
\t\tif err != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil { return nil, E.New("managed health target must be HTTPS without credentials") }
\t}
\tif len(outbound.healthURLs) > 3 { return nil, E.New("too many health targets") }
\tfor _, target := range outbound.healthURLs {
\t\tparsed, err := url.Parse(target)
\t\tif err != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil { return nil, E.New("health target must be HTTPS without credentials") }
\t}
\tif len(outbound.tags) == 0 {''',1)
text=text.replace('\t"net"','\t"net"\n\t"net/url"',1)
text=text.replace('\t\ts.group = group','\t\tgroup.managedHealth = s.managedHealth\n\t\ts.group = group',1)
text=text.replace('\t\tgroup.managedHealth = s.managedHealth','\t\tgroup.managedHealth = s.managedHealth\n\t\tgroup.healthURLs = s.healthURLs',1)
start=text.index('\tvar outbound adapter.Outbound',text.index('func (s *URLTest) DialContext'))
end=text.index('\tif outbound == nil {\n\t\treturn nil',start)
legacy=text[start:end]
text=text[:start]+'''\tvar outbound adapter.Outbound
\tif s.managedHealth { outbound = s.group.current(N.NetworkName(network)) } else {
'''+legacy.replace('\tvar outbound adapter.Outbound\n','')+'\t}\n'+text[end:]
start=text.index('\toutbound := s.group.selectedOutboundUDP',text.index('func (s *URLTest) ListenPacket'))
end=text.index('\tif outbound == nil {\n\t\treturn nil',start)
legacy=text[start:end].replace('\toutbound :=','\toutbound =',1)
text=text[:start]+'''\tvar outbound adapter.Outbound
\tif s.managedHealth { outbound = s.group.current(N.NetworkUDP) } else {
'''+legacy+'\t}\n'+text[end:]
text=text.replace('func (s *URLTest) Now() string {','''func (s *URLTest) Now() string {
\tif s.managedHealth {
\t\tif node := s.group.current(N.NetworkTCP); node != nil { return node.Tag() }
\t\tif node := s.group.current(N.NetworkUDP); node != nil { return node.Tag() }
\t\treturn ""
\t}''',1)
start=text.index('func (s *URLTest) References()')
end=text.index('\nfunc ',start+1)
references=text[start:end]
anchor='\tvar references []string'
assert anchor in references
references=references.replace(anchor,'\tif s.managedHealth { group.updateAccess.Lock(); defer group.updateAccess.Unlock() }\n'+anchor,1)
text=text[:start]+references+text[end:]
text=text.replace('\ts.group.history.DeleteURLTestHistory(outbound.Tag())','\ts.group.ReportFailure(outbound.Tag())')
# The group field layout is pinned; locate struct independently from outer URLTest.
start=text.index('type URLTestGroup struct {')
text=text[:start]+text[start:].replace('type URLTestGroup struct {','''type URLTestGroup struct {
\tmanagedHealth bool
\thealthURLs []string
\tqualified *urltest.HistoryStorage
\tfailureAccess sync.Mutex
\tfailures map[string]failureWindow
\tlastFailureCheck time.Time''',1)
text=text.replace('if history := g.history.LoadURLTestHistory(RealTag(g.outbound, g.selectedOutboundTCP)); history != nil {','if history := g.history.LoadURLTestHistory(RealTag(g.outbound, g.selectedOutboundTCP)); g.usable(history) {')
text=text.replace('if history := g.history.LoadURLTestHistory(RealTag(g.outbound, g.selectedOutboundUDP)); history != nil {','if history := g.history.LoadURLTestHistory(RealTag(g.outbound, g.selectedOutboundUDP)); g.usable(history) {')
text=text.replace('\t\tif history == nil {\n\t\t\tcontinue','\t\tif !g.usable(history) {\n\t\t\tcontinue',1)
text=text.replace('g.history.LoadURLTestHistory(', 'g.loadHistory(')
text=text.replace('\t\thistory:                      history,','\t\thistory:                      history,\n\t\tqualified:                    urltest.NewHistoryStorage(),',1)
text=text.replace('result := URLTestOutbounds(ctx, g.outbound, g.history, g.logger, g.outbounds, g.link, g.interval, force)', '''history := g.history
\tvar notify func()
\tif g.managedHealth { history = g.qualified }
\tif g.managedHealth { notify = func() { g.performUpdateCheck(); g.history.NotifyUpdated() } }
\tresult := urlTestOutboundsHealth(ctx, g.outbound, history, g.logger, g.outbounds, g.link, g.interval, force, g.healthURLs, notify)''',1)
signature='func URLTestOutbounds(ctx context.Context, outboundManager adapter.OutboundManager, history *urltest.HistoryStorage, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, force bool) map[string]uint16 {'
assert signature in text
text=text.replace(signature,signature+'''
\treturn urlTestOutboundsHealth(ctx,outboundManager,history,logger,outbounds,link,interval,force,nil)
}

func urlTestOutboundsHealth(ctx context.Context, outboundManager adapter.OutboundManager, history *urltest.HistoryStorage, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, force bool, healthURLs []string, update ...func()) map[string]uint16 {
\tvar notify func()
\tif len(update)>0 { notify=update[0] }''',1)
text=text.replace('type urlTestBatch struct {','type urlTestBatch struct {\n\tonUpdate func()\n\thealthURLs []string',1)
text=text.replace('\ttestBatch := &urlTestBatch{','\ttestBatch := &urlTestBatch{\n\t\tonUpdate: notify,\n\t\thealthURLs: healthURLs,',1)
text=text.replace('\t\t\t\t\tb.history.DeleteURLTestHistory(tag)','\t\t\t\t\tb.history.DeleteURLTestHistory(tag)\n\t\t\t\t\tif b.onUpdate != nil { b.onUpdate() }',1)
anchor='\t\t\t\t\t\tDelay: testResult.delay,\n\t\t\t\t\t})'
assert anchor in text
text=text.replace(anchor,anchor+'\n\t\t\t\t\tif b.onUpdate != nil { b.onUpdate() }',1)
text=text.replace('\t\t\t\t\tdelay, testErr := urltest.URLTest(testCtx, link, detour)', '''\t\t\t\t\tdelay, testErr := urltest.URLTest(testCtx, link, detour)
\t\t\t\t\tfor _, target := range b.healthURLs {
\t\t\t\t\t\tif testErr != nil { break }
\t\t\t\t\t\tvar extra uint16
\t\t\t\t\t\textra, testErr = urltest.URLTest(testCtx,target,detour)
\t\t\t\t\t\tif extra > delay { delay = extra }
\t\t\t\t\t}''',1)
text=text.replace('\tif minOutbound == nil {\n\t\tfor _, detour', '\tif minOutbound == nil {\n\t\tif g.managedHealth { return nil, false }\n\t\tfor _, detour',1)
text=text.replace('\tg.performUpdateCheck()\n\treturn result, nil', '''\tg.failureAccess.Lock()
\tfor tag := range result { delete(g.failures, tag) }
\tg.failureAccess.Unlock()
\tg.performUpdateCheck()
\tif g.managedHealth { g.history.NotifyUpdated() }
\treturn result, nil''',1)
text=text.replace('\tif updated {\n\t\tg.interruptGroup.Interrupt', '''\tif g.managedHealth {
\t\tif candidate, _ := g.Select(N.NetworkTCP); candidate == nil && g.selectedOutboundTCP != nil { g.selectedOutboundTCP = nil; updated = true; selected = true }
\t\tif candidate, _ := g.Select(N.NetworkUDP); candidate == nil && g.selectedOutboundUDP != nil { g.selectedOutboundUDP = nil; updated = true; selected = true }
\t}
\tif updated {
\t\tg.logger.Info("verified auto candidate changed; reconnecting affected group")
\t\tg.interruptGroup.Interrupt''',1)
# Immediate scoped switch after repeated dial failures; rate-limit active forced scans.
text+='''
type failureWindow struct { since time.Time; count int }

func (g *URLTestGroup) current(network string) adapter.Outbound {
\tg.updateAccess.Lock()
\tdefer g.updateAccess.Unlock()
\tvar node adapter.Outbound
\tswitch network { case N.NetworkTCP: node=g.selectedOutboundTCP; case N.NetworkUDP: node=g.selectedOutboundUDP }
\tif node == nil || !g.usable(g.loadHistory(RealTag(g.outbound,node))) { return nil }
\treturn node
}

func (g *URLTestGroup) loadHistory(tag string) *adapter.URLTestHistory {
\tif g.managedHealth { return g.qualified.LoadURLTestHistory(tag) }
\treturn g.history.LoadURLTestHistory(tag)
}

func (s *URLTest) HealthHistory(tag string) (*adapter.URLTestHistory, bool) {
\tif !s.managedHealth { return nil, false }
\treturn s.group.loadHistory(tag), true
}

func (g *URLTestGroup) usable(history *adapter.URLTestHistory) bool {
\tif history == nil { return false }
\tif !g.managedHealth { return true }
\tage := time.Since(history.Time)
\treturn age >= 0 && age <= g.interval + 15*time.Second
}

func (g *URLTestGroup) ReportFailure(tag string) {
\tif !g.managedHealth { g.history.DeleteURLTestHistory(tag); return }
\tnow := time.Now()
\tg.failureAccess.Lock()
\tif g.failures == nil { g.failures = make(map[string]failureWindow) }
\twindow := g.failures[tag]
\tif now.Sub(window.since) > 15*time.Second { window = failureWindow{since:now} }
\twindow.count++
\tg.failures[tag] = window
\tif window.count < 2 { g.failureAccess.Unlock(); return }
\trecheck := now.Sub(g.lastFailureCheck) >= 10*time.Second
\tif recheck { g.lastFailureCheck = now }
\tg.failureAccess.Unlock()
\tg.qualified.DeleteURLTestHistory(tag)
\tif recheck { g.logger.Warn("active path failed repeatedly; reselecting verified candidate") }
\tg.performUpdateCheck()
\tif recheck { go g.CheckOutbounds(g.ctx, true) }
}
'''
write(name,text)
name='daemon/started_service.go';text=original(name)
old='\t\t\tif history := historyStorage.LoadURLTestHistory(group.RealTag(boxService.outboundManager, itemOutbound)); history != nil {'
assert old in text
text=text.replace(old,'''\t\t\thistory := historyStorage.LoadURLTestHistory(group.RealTag(boxService.outboundManager, itemOutbound))
\t\t\tif qualified, ok := iGroup.(interface { HealthHistory(string) (*adapter.URLTestHistory, bool) }); ok {
\t\t\t\tif measured, managed := qualified.HealthHistory(itemTag); managed { history = measured }
\t\t\t}
\t\t\tif history != nil {''',1)
write(name,text)
print('Managed URLTest freshness/failure reselect patch applied; legacy graphs retain fallback semantics.')
