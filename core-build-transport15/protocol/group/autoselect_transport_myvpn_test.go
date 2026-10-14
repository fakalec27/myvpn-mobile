package group

import (
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"net"
	"testing"
	"time"
)

func transportGroup() (*URLTestGroup, *healthNode, *healthNode) {
	a, b := &healthNode{tag: "a"}, &healthNode{tag: "b"}
	h := urltest.NewHistoryStorage()
	h.StoreURLTestHistory("a", &adapter.URLTestHistory{Time: time.Now(), Delay: 100})
	h.StoreURLTestHistory("b", &adapter.URLTestHistory{Time: time.Now(), Delay: 200})
	return &URLTestGroup{managedHealth: true, interval: time.Minute, tolerance: 10, qualified: h, history: urltest.NewHistoryStorage(),
		outbounds: []adapter.Outbound{a, b}, selectedOutboundTCP: a, interruptGroup: interrupt.NewGroup(), logger: log.NewNOPFactory().Logger(), lastFailureCheck: time.Now()}, a, b
}
func trackedTransport(t *testing.T, g *URLTestGroup, node adapter.Outbound, external bool) net.Conn {
	t.Helper()
	a, b := net.Pipe()
	t.Cleanup(func() { a.Close(); b.Close() })
	return g.trackConn(node, a, external)
}
func transportAlive(t *testing.T, c net.Conn, want bool) {
	t.Helper()
	alive := c.SetReadDeadline(time.Now().Add(time.Second)) == nil
	if alive != want {
		t.Fatalf("transport alive=%v, want=%v", alive, want)
	}
}
func TestMyVPNTransportHealthyReselectRetainsOldAndLaterFailureClosesOnlyOld(t *testing.T) {
	g, a, b := transportGroup()
	ssh := trackedTransport(t, g, a, false)
	browser := trackedTransport(t, g, a, true)
	g.qualified.StoreURLTestHistory("b", &adapter.URLTestHistory{Time: time.Now(), Delay: 1})
	g.performUpdateCheck()
	if g.selectedOutboundTCP != b {
		t.Fatal("future dials did not move to faster verified node")
	}
	transportAlive(t, ssh, true)
	transportAlive(t, browser, true)
	current := trackedTransport(t, g, b, true)
	g.ReportFailure("a")
	transportAlive(t, ssh, true)
	g.ReportFailure("a")
	transportAlive(t, ssh, false)
	transportAlive(t, browser, false)
	transportAlive(t, current, true)
}
func TestMyVPNTransportFailedCurrentReselectAndAllDown(t *testing.T) {
	g, a, b := transportGroup()
	old := trackedTransport(t, g, a, false)
	g.ReportFailure("a")
	g.ReportFailure("a")
	if g.selectedOutboundTCP != b {
		t.Fatal("failed active node did not reselect")
	}
	transportAlive(t, old, false)
	current := trackedTransport(t, g, b, true)
	g.qualified.DeleteURLTestHistory("b")
	g.performUpdateCheck()
	if g.current("tcp") != nil {
		t.Fatal("all down leaked fallback")
	}
	transportAlive(t, current, false)
	late := trackedTransport(t, g, a, false)
	transportAlive(t, late, false)
}
func TestMyVPNTransportExpiredRetainedPathClosesWithoutHealthyPath(t *testing.T) {
	g, a, b := transportGroup()
	old := trackedTransport(t, g, a, false)
	current := trackedTransport(t, g, b, false)
	g.qualified.StoreURLTestHistory("a", &adapter.URLTestHistory{Time: time.Now().Add(-2 * time.Minute), Delay: 1})
	g.performUpdateCheck()
	transportAlive(t, old, false)
	transportAlive(t, current, true)
}
func TestMyVPNTransportLegacyPerformanceSwitchStillInterrupts(t *testing.T) {
	g, a, _ := transportGroup()
	g.managedHealth = false
	g.history = g.qualified
	old := trackedTransport(t, g, a, false)
	g.history.StoreURLTestHistory("b", &adapter.URLTestHistory{Time: time.Now(), Delay: 1})
	g.performUpdateCheck()
	transportAlive(t, old, false)
}
func TestMyVPNTransportCloseReapsRetainedConnectionsWithoutTicker(t *testing.T) {
	g, a, _ := transportGroup()
	old := trackedTransport(t, g, a, false)
	g.Close()
	transportAlive(t, old, false)
}
func TestMyVPNTransportExposedHistoryUsesManagedInterval(t *testing.T) {
	g, _, _ := transportGroup()
	g.interval = 2 * time.Minute
	g.qualified.StoreURLTestHistory("a", &adapter.URLTestHistory{Time: time.Now().Add(-136 * time.Second), Delay: 1})
	s := URLTest{managedHealth: true, group: g}
	if h, managed := s.HealthHistory("a"); h != nil || !managed {
		t.Fatal("UI exposed expired managed qualification")
	}
	g.qualified.StoreURLTestHistory("a", &adapter.URLTestHistory{Time: time.Now().Add(-120 * time.Second), Delay: 1})
	if h, managed := s.HealthHistory("a"); h == nil || !managed {
		t.Fatal("fresh managed qualification hidden")
	}
	s.managedHealth = false
	if h, managed := s.HealthHistory("a"); h != nil || managed {
		t.Fatal("legacy exposure contract changed")
	}
}
func TestMyVPNTransportPacketPathUsesSameTargetedInvalidation(t *testing.T) {
	g, a, b := transportGroup()
	p, e := net.ListenPacket("udp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer p.Close()
	tracked := g.trackPacket(a, p, true)
	healthy := trackedTransport(t, g, b, false)
	g.qualified.StoreURLTestHistory("b", &adapter.URLTestHistory{Time: time.Now(), Delay: 1})
	g.performUpdateCheck()
	if e := tracked.SetReadDeadline(time.Now()); e != nil {
		t.Fatal("healthy packet path closed on performance reselect")
	}
	g.qualified.DeleteURLTestHistory("a")
	g.performUpdateCheck()
	if e := tracked.SetReadDeadline(time.Now()); e == nil {
		t.Fatal("failed packet path stayed open")
	}
	transportAlive(t, healthy, true)
}
