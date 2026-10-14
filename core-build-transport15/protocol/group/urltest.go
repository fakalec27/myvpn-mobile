package group

import (
 "crypto/sha256"
 "encoding/hex"
	"context"
	"maps"
	"net"
	"net/url"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/batch"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func RegisterURLTest(registry *outbound.Registry) {
	outbound.Register[option.URLTestOutboundOptions](registry, C.TypeURLTest, NewURLTest)
}

var (
	_ adapter.OutboundGroup           = (*URLTest)(nil)
	_ adapter.InterfaceUpdateListener = (*URLTest)(nil)
	_ adapter.Referrer                = (*URLTest)(nil)
)

type URLTest struct {
	outbound.Adapter
	ctx                          context.Context
	outbound                     adapter.OutboundManager
	connection                   adapter.ConnectionManager
	logger                       log.ContextLogger
	tags                         []string
	link                         string
	interval                     time.Duration
	tolerance                    uint16
	idleTimeout                  time.Duration
	group                        *URLTestGroup
	checkAccess                  sync.Mutex
	managedHealth                bool
	healthURLs                   []string
	interruptExternalConnections bool
}

func NewURLTest(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.URLTestOutboundOptions) (adapter.Outbound, error) {
	outbound := &URLTest{
		Adapter:                      outbound.NewAdapter(C.TypeURLTest, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:                          ctx,
		outbound:                     service.FromContext[adapter.OutboundManager](ctx),
		connection:                   service.FromContext[adapter.ConnectionManager](ctx),
		logger:                       logger,
		managedHealth:                options.MyVPNHealthCheck,
		healthURLs:                   options.MyVPNHealthURLs,
		tags:                         options.Outbounds,
		link:                         options.URL,
		interval:                     time.Duration(options.Interval),
		tolerance:                    options.Tolerance,
		idleTimeout:                  time.Duration(options.IdleTimeout),
		interruptExternalConnections: options.InterruptExistConnections,
	}
	if outbound.managedHealth && outbound.link != "" {
		parsed, err := url.Parse(outbound.link)
		if err != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil { return nil, E.New("managed health target must be HTTPS without credentials") }
	}
	if len(outbound.healthURLs) > 3 { return nil, E.New("too many health targets") }
	for _, target := range outbound.healthURLs {
		parsed, err := url.Parse(target)
		if err != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil { return nil, E.New("health target must be HTTPS without credentials") }
	}
	if len(outbound.tags) == 0 && !outbound.managedHealth {
		return nil, E.New("missing tags")
	}
	return outbound, nil
}

func (s *URLTest) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	switch stage {
	case adapter.StartStateStart:
		outbounds := make([]adapter.Outbound, 0, len(s.tags))
		for i, tag := range s.tags {
			detour, loaded := s.outbound.Outbound(tag)
			if !loaded {
				return E.New("outbound ", i, " not found: ", tag)
			}
			outbounds = append(outbounds, detour)
		}
		group, err := NewURLTestGroup(s.ctx, s.outbound, s.logger, outbounds, s.link, s.interval, s.tolerance, s.idleTimeout, s.interruptExternalConnections)
		if err != nil {
			return err
		}
		group.managedHealth = s.managedHealth
		group.healthURLs = s.healthURLs
		s.group = group
	case adapter.StartStateStarted:
		s.group.PostStart()
		scope.Add(s.group.Close)
	}
	return nil
}

func (s *URLTest) Now() string {
	if s.managedHealth {
		if node := s.group.current(N.NetworkTCP); node != nil { return node.Tag() }
		if node := s.group.current(N.NetworkUDP); node != nil { return node.Tag() }
		return ""
	}
	if s.group.selectedOutboundTCP != nil {
		return s.group.selectedOutboundTCP.Tag()
	} else if s.group.selectedOutboundUDP != nil {
		return s.group.selectedOutboundUDP.Tag()
	}
	return ""
}

func (s *URLTest) All() []string {
	return s.tags
}

func (s *URLTest) References() []string {
	group := s.group
	if group == nil {
		return nil
	}
	if s.managedHealth { group.updateAccess.Lock(); defer group.updateAccess.Unlock() }
	var references []string
	if group.selectedOutboundTCP != nil {
		references = append(references, group.selectedOutboundTCP.Tag())
	}
	if group.selectedOutboundUDP != nil && group.selectedOutboundUDP != group.selectedOutboundTCP {
		references = append(references, group.selectedOutboundUDP.Tag())
	}
	return references
}

func (s *URLTest) URLTest(ctx context.Context) (map[string]uint16, error) {
	return s.group.URLTest(ctx)
}

func (s *URLTest) CheckOutbounds() {
	s.group.CheckOutbounds(s.ctx, true)
}

func (s *URLTest) PerformUpdateCheck() {
	s.group.performUpdateCheck()
}

func (s *URLTest) InterfaceUpdated(ctx context.Context) {
	group := s.group
	if group == nil {
		return
	}
	if group.pause.IsDevicePaused() || group.pause.IsNetworkPaused() {
		return
	}
	go func() {
		s.checkAccess.Lock()
		defer s.checkAccess.Unlock()
		if ctx.Err() != nil {
			return
		}
		group.CheckOutbounds(ctx, true)
	}()
}

func (s *URLTest) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	s.group.Touch()
	var outbound adapter.Outbound
	if s.managedHealth { outbound = s.group.current(N.NetworkName(network)) } else {
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		outbound = s.group.selectedOutboundTCP
	case N.NetworkUDP:
		outbound = s.group.selectedOutboundUDP
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	if outbound == nil {
		outbound, _ = s.group.Select(network)
	}
	}
	if outbound == nil {
		return nil, E.New("missing supported outbound")
	}
	conn, err := outbound.DialContext(ctx, network, destination)
	if err == nil {
		return s.group.trackConn(outbound, conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
	}
	s.logger.ErrorContext(ctx, err)
	s.group.ReportFailure(outbound.Tag())
	return nil, err
}

func (s *URLTest) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	s.group.Touch()
	var outbound adapter.Outbound
	if s.managedHealth { outbound = s.group.current(N.NetworkUDP) } else {
	outbound = s.group.selectedOutboundUDP
	if outbound == nil {
		outbound, _ = s.group.Select(N.NetworkUDP)
	}
	}
	if outbound == nil {
		return nil, E.New("missing supported outbound")
	}
	conn, err := outbound.ListenPacket(ctx, destination)
	if err == nil {
		return s.group.trackPacket(outbound, conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
	}
	s.logger.ErrorContext(ctx, err)
	s.group.ReportFailure(outbound.Tag())
	return nil, err
}

func (s *URLTest) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx)
	s.connection.NewConnection(ctx, s, conn, metadata, onClose)
}

func (s *URLTest) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx)
	s.connection.NewPacketConnection(ctx, s, conn, metadata, onClose)
}

type URLTestGroup struct {
	nodeConnections map[string]*interrupt.Group
	managedHealth bool
	healthURLs []string
	qualified *urltest.HistoryStorage
	failureAccess sync.Mutex
	failures map[string]failureWindow
	lastFailureCheck time.Time
	ctx                          context.Context
	outbound                     adapter.OutboundManager
	pause                        pause.Manager
	pauseCallback                *list.Element[pause.Callback]
	logger                       log.Logger
	outbounds                    []adapter.Outbound
	link                         string
	interval                     time.Duration
	tolerance                    uint16
	idleTimeout                  time.Duration
	history                      *urltest.HistoryStorage
	checking                     atomic.Bool
	selectedOutboundTCP          adapter.Outbound
	selectedOutboundUDP          adapter.Outbound
	interruptGroup               *interrupt.Group
	interruptExternalConnections bool
	access                       sync.Mutex
	updateAccess                 sync.Mutex
	ticker                       *time.Ticker
	close                        chan struct{}
	started                      bool
	lastActive                   common.TypedValue[time.Time]
}

func NewURLTestGroup(ctx context.Context, outboundManager adapter.OutboundManager, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, tolerance uint16, idleTimeout time.Duration, interruptExternalConnections bool) (*URLTestGroup, error) {
	if interval == 0 {
		interval = C.DefaultURLTestInterval
	}
	if tolerance == 0 {
		tolerance = 50
	}
	if idleTimeout == 0 {
		idleTimeout = C.DefaultURLTestIdleTimeout
	}
	if interval > idleTimeout {
		return nil, E.New("interval must be less or equal than idle_timeout")
	}
	history := service.PtrFromContext[urltest.HistoryStorage](ctx)
	if history == nil {
		return nil, E.New("missing URL test history storage")
	}
	return &URLTestGroup{
		ctx:                          ctx,
		outbound:                     outboundManager,
		logger:                       logger,
		outbounds:                    outbounds,
		link:                         link,
		interval:                     interval,
		tolerance:                    tolerance,
		idleTimeout:                  idleTimeout,
		history:                      history,
		qualified:                    urltest.NewHistoryStorage(),
		close:                        make(chan struct{}),
		pause:                        service.FromContext[pause.Manager](ctx),
		interruptGroup:               interrupt.NewGroup(),
		interruptExternalConnections: interruptExternalConnections,
	}, nil
}

func (g *URLTestGroup) PostStart() {
	g.access.Lock()
	defer g.access.Unlock()
	g.started = true
	g.lastActive.Store(time.Now())
	go g.CheckOutbounds(g.ctx, false)
}

func (g *URLTestGroup) Touch() {
	if !g.started {
		return
	}
	g.access.Lock()
	defer g.access.Unlock()
	if g.ticker != nil {
		g.lastActive.Store(time.Now())
		return
	}
	ticker := time.NewTicker(g.interval)
	g.ticker = ticker
	g.pauseCallback = pause.RegisterTicker(g.pause, ticker, g.interval, nil)
	go g.loopCheck(ticker, g.close)
}

func (g *URLTestGroup) Close() error {
	if g.managedHealth {
		var closeGroups []*interrupt.Group
		g.updateAccess.Lock()
		for tag, connections := range g.nodeConnections { closeGroups = append(closeGroups, connections); delete(g.nodeConnections, tag) }
		g.updateAccess.Unlock()
		for _, connections := range closeGroups { connections.Interrupt(true) }
	}
	g.access.Lock()
	defer g.access.Unlock()
	if g.ticker == nil {
		return nil
	}
	g.ticker.Stop()
	g.ticker = nil
	g.pause.UnregisterCallback(g.pauseCallback)
	g.pauseCallback = nil
	close(g.close)
	return nil
}

func (g *URLTestGroup) Select(network string) (adapter.Outbound, bool) {
	var minDelay uint16
	var minOutbound adapter.Outbound
	switch network {
	case N.NetworkTCP:
		if g.selectedOutboundTCP != nil {
			if history := g.loadHistory(RealTag(g.outbound, g.selectedOutboundTCP)); g.usable(history) {
				minOutbound = g.selectedOutboundTCP
				minDelay = history.Delay
			}
		}
	case N.NetworkUDP:
		if g.selectedOutboundUDP != nil {
			if history := g.loadHistory(RealTag(g.outbound, g.selectedOutboundUDP)); g.usable(history) {
				minOutbound = g.selectedOutboundUDP
				minDelay = history.Delay
			}
		}
	}
	for _, detour := range g.outbounds {
		if !common.Contains(detour.Network(), network) {
			continue
		}
		history := g.loadHistory(RealTag(g.outbound, detour))
		if !g.usable(history) {
			continue
		}
		if minDelay == 0 || minDelay > history.Delay+g.tolerance {
			minDelay = history.Delay
			minOutbound = detour
		}
	}
	if minOutbound == nil {
		if g.managedHealth { return nil, false }
		for _, detour := range g.outbounds {
			if !common.Contains(detour.Network(), network) {
				continue
			}
			return detour, false
		}
		return nil, false
	}
	return minOutbound, true
}

func (g *URLTestGroup) loopCheck(ticker *time.Ticker, closeChan <-chan struct{}) {
	if time.Since(g.lastActive.Load()) > g.interval {
		g.lastActive.Store(time.Now())
		g.CheckOutbounds(g.ctx, false)
	}
	for {
		select {
		case <-closeChan:
			return
		case <-ticker.C:
		}
		if time.Since(g.lastActive.Load()) > g.idleTimeout {
			g.access.Lock()
			if g.ticker == ticker {
				g.ticker.Stop()
				g.ticker = nil
				g.pause.UnregisterCallback(g.pauseCallback)
				g.pauseCallback = nil
			}
			g.access.Unlock()
			return
		}
		g.CheckOutbounds(g.ctx, false)
	}
}

func (g *URLTestGroup) CheckOutbounds(ctx context.Context, force bool) {
	_, _ = g.urlTest(ctx, force)
}

func (g *URLTestGroup) URLTest(ctx context.Context) (map[string]uint16, error) {
	return g.urlTest(ctx, true)
}

func (g *URLTestGroup) urlTest(ctx context.Context, force bool) (map[string]uint16, error) {
	if g.checking.Swap(true) {
		return make(map[string]uint16), nil
	}
	defer g.checking.Store(false)
	history := g.history
	var notify func()
	if g.managedHealth { history = g.qualified }
	if g.managedHealth { notify = func() { g.performUpdateCheck(); g.history.NotifyUpdated() } }
	result := urlTestOutboundsHealth(ctx, g.outbound, history, g.logger, g.outbounds, g.link, g.interval, force, g.healthURLs, notify)
	g.failureAccess.Lock()
	for tag := range result { delete(g.failures, tag) }
	g.failureAccess.Unlock()
	g.performUpdateCheck()
	if g.managedHealth { g.history.NotifyUpdated() }
	return result, nil
}

type urlTestResult struct {
	delay uint16
	err   error
}

type urlTestBatch struct {
	onUpdate func()
	healthURLs []string
	ctx      context.Context
	outbound adapter.OutboundManager
	history  *urltest.HistoryStorage
	logger   log.Logger
	batch    *batch.Batch[any]
	checked  map[string]bool
	groups   []adapter.OutboundGroup
	access   sync.Mutex
	result   map[string]uint16
}

func URLTestOutbounds(ctx context.Context, outboundManager adapter.OutboundManager, history *urltest.HistoryStorage, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, force bool) map[string]uint16 {
	return urlTestOutboundsHealth(ctx,outboundManager,history,logger,outbounds,link,interval,force,nil)
}

func urlTestOutboundsHealth(ctx context.Context, outboundManager adapter.OutboundManager, history *urltest.HistoryStorage, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, force bool, healthURLs []string, update ...func()) map[string]uint16 {
	var notify func()
	if len(update)>0 { notify=update[0] }
	b, _ := batch.New(ctx, batch.WithConcurrencyNum[any](10))
	testBatch := &urlTestBatch{
		onUpdate: notify,
		healthURLs: healthURLs,
		ctx:      ctx,
		outbound: outboundManager,
		history:  history,
		logger:   logger,
		batch:    b,
		checked:  make(map[string]bool),
		result:   make(map[string]uint16),
	}
	testBatch.test(outbounds, link, interval, force)
	b.Wait()
	for _, outboundGroup := range testBatch.groups {
		groupHistory := history.LoadURLTestHistory(RealTag(outboundManager, outboundGroup))
		if groupHistory != nil {
			testBatch.result[outboundGroup.Tag()] = groupHistory.Delay
		}
	}
	return testBatch.result
}

func (b *urlTestBatch) test(outbounds []adapter.Outbound, link string, interval time.Duration, force bool) {
	for _, detour := range outbounds {
		tag := detour.Tag()
		if b.checked[tag] {
			continue
		}
		switch nested := detour.(type) {
		case *URLTest:
			b.checked[tag] = true
			b.groups = append(b.groups, nested)
			b.batch.Go(tag, func() (any, error) {
				nestedResult, _ := nested.group.urlTest(b.ctx, force)
				b.access.Lock()
				maps.Copy(b.result, nestedResult)
				b.access.Unlock()
				return nil, nil
			})
		case adapter.OutboundGroup:
			b.checked[tag] = true
			b.groups = append(b.groups, nested)
			b.test(common.FilterNotNil(common.Map(nested.All(), func(it string) adapter.Outbound {
				member, _ := b.outbound.Outbound(it)
				return member
			})), link, interval, force)
		default:
			history := b.history.LoadURLTestHistory(tag)
			if !force && history != nil && time.Since(history.Time) < interval {
				continue
			}
			b.checked[tag] = true
			b.batch.Go(tag, func() (any, error) {
				testCtx, cancel := context.WithTimeout(b.ctx, C.TCPTimeout)
				defer cancel()
				testChan := make(chan urlTestResult, 1)
				go func() {
					delay, testErr := urltest.URLTest(testCtx, link, detour)
					for _, target := range b.healthURLs {
						if testErr != nil { break }
						var extra uint16
						extra, testErr = urltest.URLTest(testCtx,target,detour)
						if extra > delay { delay = extra }
					}
					testChan <- urlTestResult{delay, testErr}
				}()
				var testResult urlTestResult
				select {
				case testResult = <-testChan:
				case <-testCtx.Done():
					testResult.err = testCtx.Err()
				}
				if testResult.err != nil {
					if b.ctx.Err() != nil {
						return nil, nil
					}
					b.logger.Debug("outbound ", tag, " unavailable: ", testResult.err)
					b.history.DeleteURLTestHistory(tag)
					if b.onUpdate != nil { b.onUpdate() }
				} else {
					b.logger.Debug("outbound ", tag, " available: ", testResult.delay, "ms")
					b.history.StoreURLTestHistory(tag, &adapter.URLTestHistory{
						Time:  time.Now(),
						Delay: testResult.delay,
					})
					if b.onUpdate != nil { b.onUpdate() }
					b.access.Lock()
					b.result[tag] = testResult.delay
					b.access.Unlock()
				}
				return nil, nil
			})
		}
	}
}

func (g *URLTestGroup) performUpdateCheck() {
	var closeGroups []*interrupt.Group
	g.updateAccess.Lock()
	defer func() {
		g.updateAccess.Unlock()
		for _, connections := range closeGroups { connections.Interrupt(true) }
	}()
	var (
		updated  bool
		selected bool
	)
	if outbound, exists := g.Select(N.NetworkTCP); outbound != nil && (g.selectedOutboundTCP == nil || (exists && outbound != g.selectedOutboundTCP)) {
		if g.selectedOutboundTCP != nil {
			updated = true
		}
		g.selectedOutboundTCP = outbound
		selected = true
	}
	if outbound, exists := g.Select(N.NetworkUDP); outbound != nil && (g.selectedOutboundUDP == nil || (exists && outbound != g.selectedOutboundUDP)) {
		if g.selectedOutboundUDP != nil {
			updated = true
		}
		g.selectedOutboundUDP = outbound
		selected = true
	}
	if g.managedHealth {
		if candidate, _ := g.Select(N.NetworkTCP); candidate == nil && g.selectedOutboundTCP != nil { g.selectedOutboundTCP = nil; updated = true; selected = true }
		if candidate, _ := g.Select(N.NetworkUDP); candidate == nil && g.selectedOutboundUDP != nil { g.selectedOutboundUDP = nil; updated = true; selected = true }
	}
	if g.managedHealth {
		if updated {g.journal("auto-selection", "performance_or_failure", "")}
		// A faster healthy candidate affects future dials only. Retained old nodes
		// are still reaped when their qualification disappears or expires.
		for tag, connections := range g.nodeConnections {
			if !g.usable(g.loadHistory(tag)) {
				closeGroups = append(closeGroups, connections)
				g.journal("auto-node-close", "qualification", tag)
				delete(g.nodeConnections, tag)
			}
		}
	} else if updated {
		g.interruptGroup.Interrupt(g.interruptExternalConnections)
	}
	if selected {
		g.history.NotifyUpdated()
	}
}

type failureWindow struct { since time.Time; count int }

func (g *URLTestGroup) current(network string) adapter.Outbound {
	g.updateAccess.Lock()
	defer g.updateAccess.Unlock()
	var node adapter.Outbound
	switch network { case N.NetworkTCP: node=g.selectedOutboundTCP; case N.NetworkUDP: node=g.selectedOutboundUDP }
	if node == nil || !g.usable(g.loadHistory(RealTag(g.outbound,node))) { return nil }
	return node
}

func (g *URLTestGroup) loadHistory(tag string) *adapter.URLTestHistory {
	if g.managedHealth { return g.qualified.LoadURLTestHistory(tag) }
	return g.history.LoadURLTestHistory(tag)
}

func (s *URLTest) HealthHistory(tag string) (*adapter.URLTestHistory, bool) {
	if !s.managedHealth { return nil, false }
	history := s.group.loadHistory(tag)
	if !s.group.usable(history) { return nil, true }
	return history, true
}

func (g *URLTestGroup) usable(history *adapter.URLTestHistory) bool {
	if history == nil { return false }
	if !g.managedHealth { return true }
	age := time.Since(history.Time)
	return age >= 0 && age <= g.interval + 15*time.Second
}

func (g *URLTestGroup) ReportFailure(tag string) {
	if !g.managedHealth { g.history.DeleteURLTestHistory(tag); return }
	now := time.Now()
	g.failureAccess.Lock()
	if g.failures == nil { g.failures = make(map[string]failureWindow) }
	window := g.failures[tag]
	if now.Sub(window.since) > 15*time.Second { window = failureWindow{since:now} }
	window.count++
	g.failures[tag] = window
	if window.count < 2 { g.failureAccess.Unlock(); return }
	recheck := now.Sub(g.lastFailureCheck) >= 10*time.Second
	if recheck { g.lastFailureCheck = now }
	g.failureAccess.Unlock()
	g.qualified.DeleteURLTestHistory(tag)
	if recheck { g.logger.Warn("active path failed repeatedly; reselecting verified candidate") }
	g.performUpdateCheck()
	if recheck { go g.CheckOutbounds(g.ctx, true) }
}

// Registration and qualification are checked under the same lock as reselection:
// a late dial completing after invalidation must not resurrect a failed path.
func (g *URLTestGroup) trackConn(node adapter.Outbound, conn net.Conn, external bool) net.Conn {
	if !g.managedHealth { return g.interruptGroup.NewConn(conn, external) }
	g.updateAccess.Lock()
	tag := RealTag(g.outbound, node)
	if !g.usable(g.loadHistory(tag)) { g.updateAccess.Unlock(); _ = conn.Close(); return conn }
	tracked := g.nodeGroup(tag).NewConn(conn, external)
	g.updateAccess.Unlock()
	return tracked
}
func (g *URLTestGroup) trackPacket(node adapter.Outbound, conn net.PacketConn, external bool) net.PacketConn {
	if !g.managedHealth { return g.interruptGroup.NewPacketConn(conn, external) }
	g.updateAccess.Lock()
	tag := RealTag(g.outbound, node)
	if !g.usable(g.loadHistory(tag)) { g.updateAccess.Unlock(); _ = conn.Close(); return conn }
	tracked := g.nodeGroup(tag).NewPacketConn(conn, external)
	g.updateAccess.Unlock()
	return tracked
}
// Caller holds updateAccess.
func (g *URLTestGroup) nodeGroup(tag string) *interrupt.Group {
	if g.nodeConnections == nil { g.nodeConnections = make(map[string]*interrupt.Group) }
	connections := g.nodeConnections[tag]
	if connections == nil { connections = interrupt.NewGroup(); g.nodeConnections[tag] = connections }
	return connections
}

func (g *URLTestGroup) journal(name, reason, tag string) {
 if g.logger == nil {return}
 digest := sha256.Sum256([]byte(tag))
 g.logger.Info("myvpn_event name=",name," reason=",reason," node=",hex.EncodeToString(digest[:6]))
}
