package group

import (
 "context"
 "net"
 "net/http"
 "net/http/httptest"
 "testing"
 "time"
 "github.com/sagernet/sing-box/adapter"
 "github.com/sagernet/sing-box/common/interrupt"
 "github.com/sagernet/sing-box/common/urltest"
 "github.com/sagernet/sing-box/log"
 M "github.com/sagernet/sing/common/metadata"
)
type healthNode struct { adapter.Outbound; tag string }
func (n *healthNode) Tag() string { return n.tag }
func (n *healthNode) Network() []string { return []string{"tcp"} }
func TestMyVPNManagedFreshness(t *testing.T) {
 g:=URLTestGroup{managedHealth:true,interval:time.Minute}
 if g.usable(nil) { t.Fatal("missing measurement accepted") }
 if g.usable(&adapter.URLTestHistory{Time:time.Now().Add(-2*time.Minute),Delay:1}) { t.Fatal("stale fast candidate accepted") }
 if g.usable(&adapter.URLTestHistory{Time:time.Now().Add(time.Minute),Delay:1}) { t.Fatal("future measurement accepted") }
 if !g.usable(&adapter.URLTestHistory{Time:time.Now(),Delay:100}) { t.Fatal("fresh measurement rejected") }
 g.managedHealth=false
 if !g.usable(&adapter.URLTestHistory{Time:time.Now().Add(-2*time.Minute),Delay:1}) { t.Fatal("custom legacy group semantics changed") }
}
func TestMyVPNCustomGroupRetainsOriginalMeasurementResolution(t *testing.T) {
 legacy:=URLTest{}
 if history,managed:=legacy.HealthHistory("nested-selector");history!=nil || managed { t.Fatal("legacy nested graph must keep original RealTag resolution") }
}
func TestMyVPNNoUnverifiedFallback(t *testing.T) {
 g:=URLTestGroup{managedHealth:true,interval:time.Minute,history:urltest.NewHistoryStorage(),qualified:urltest.NewHistoryStorage(),outbounds:[]adapter.Outbound{&healthNode{tag:"fast"}}}
 node,verified:=g.Select("tcp")
 if node!=nil || verified { t.Fatal("no viable nodes must fail closed") }
}
func TestMyVPNRepeatedFailureReselectsAndClosesOnlyAffectedTCP(t *testing.T) {
 fast,slow:=&healthNode{tag:"fast"},&healthNode{tag:"slow"}
 history:=urltest.NewHistoryStorage()
 history.StoreURLTestHistory("fast",&adapter.URLTestHistory{Time:time.Now(),Delay:1})
 history.StoreURLTestHistory("slow",&adapter.URLTestHistory{Time:time.Now(),Delay:100})
 g:=URLTestGroup{managedHealth:true,interval:time.Minute,tolerance:50,history:history,qualified:history,outbounds:[]adapter.Outbound{fast,slow},
  selectedOutboundTCP:fast,interruptGroup:interrupt.NewGroup(),interruptExternalConnections:true,
  logger:log.NewNOPFactory().Logger(),lastFailureCheck:time.Now()}
 old,peer:=net.Pipe();defer peer.Close();old=g.trackConn(fast,old,true)
 healthy,healthyPeer:=net.Pipe();defer healthy.Close();defer healthyPeer.Close()
 other:=interrupt.NewGroup();healthy=other.NewConn(healthy,true)
 g.ReportFailure("fast")
 if g.selectedOutboundTCP!=fast { t.Fatal("one destination failure must not flap route") }
 g.ReportFailure("fast")
 if g.selectedOutboundTCP!=slow { t.Fatal("repeated failure did not choose verified slower node") }
 if err:=old.SetReadDeadline(time.Now());err==nil { t.Fatal("old external TCP was not closed for reconnect") }
 if err:=healthy.SetReadDeadline(time.Now());err!=nil { t.Fatal("unrelated healthy group closed") }
}
func TestMyVPNIndividualDelayCannotQualifyCandidate(t *testing.T) {
 fast,slow:=&healthNode{tag:"fast"},&healthNode{tag:"slow"}
 public:=urltest.NewHistoryStorage();qualified:=urltest.NewHistoryStorage()
 public.StoreURLTestHistory("fast",&adapter.URLTestHistory{Time:time.Now(),Delay:1})
 qualified.StoreURLTestHistory("slow",&adapter.URLTestHistory{Time:time.Now(),Delay:100})
 g:=URLTestGroup{managedHealth:true,interval:time.Minute,history:public,qualified:qualified,outbounds:[]adapter.Outbound{fast,slow}}
 node,ok:=g.Select("tcp")
 if !ok || node!=slow { t.Fatal("unqualified fast individual ping contaminated managed Auto") }
}
type healthEndpointNode struct { healthNode; address string }
func(n *healthEndpointNode) DialContext(ctx context.Context,network string,_ M.Socksaddr) (net.Conn,error) {
 return (&net.Dialer{}).DialContext(ctx,network,n.address)
}
func TestMyVPNFast204WithoutTargetAccessIsIneligible(t *testing.T) {
 bad:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
  if r.URL.Path=="/generate_204" { w.WriteHeader(204) } else { w.WriteHeader(403) }
 }));defer bad.Close()
 good:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
  time.Sleep(20*time.Millisecond)
  if r.URL.Path=="/generate_204" { w.WriteHeader(204) } else { w.WriteHeader(200) }
 }));defer good.Close()
 nodes:=[]adapter.Outbound{&healthEndpointNode{healthNode:healthNode{tag:"fast-unsuitable"},address:bad.Listener.Addr().String()},
  &healthEndpointNode{healthNode:healthNode{tag:"slower-eligible"},address:good.Listener.Addr().String()}}
 result:=urlTestOutboundsHealth(context.Background(),nil,urltest.NewHistoryStorage(),log.NewNOPFactory().Logger(),nodes,
  "http://primary.invalid/generate_204",time.Minute,true,[]string{"http://target.invalid/"})
 if _,ok:=result["fast-unsuitable"];ok { t.Fatal("fast 204 alone qualified inaccessible target") }
 if _,ok:=result["slower-eligible"];!ok { t.Fatal("eligible target was rejected") }
}
func TestMyVPNPeriodicCheckKeepsFreshSkippedCandidates(t *testing.T) {
 qualified:=urltest.NewHistoryStorage()
 qualified.StoreURLTestHistory("healthy",&adapter.URLTestHistory{Time:time.Now(),Delay:100})
 node:=&healthNode{tag:"healthy"}
 g:=URLTestGroup{managedHealth:true,ctx:context.Background(),interval:time.Minute,history:urltest.NewHistoryStorage(),qualified:qualified,
  outbounds:[]adapter.Outbound{node},logger:log.NewNOPFactory().Logger(),interruptGroup:interrupt.NewGroup()}
 _,err:=g.urlTest(context.Background(),false)
 if err!=nil || qualified.LoadURLTestHistory("healthy")==nil || g.selectedOutboundTCP!=node { t.Fatal("periodic cached skip erased verified candidate") }
}
func TestMyVPNFirstHealthyNodeServesTrafficBeforeWholePoolFinishes(t *testing.T) {
 release:=make(chan struct{})
 fastServer:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) { time.Sleep(5*time.Millisecond);w.WriteHeader(204) }));defer fastServer.Close()
 slowServer:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) { <-release;w.WriteHeader(204) }));defer slowServer.Close()
 fast:=&healthEndpointNode{healthNode:healthNode{tag:"fast"},address:fastServer.Listener.Addr().String()}
 slow:=&healthEndpointNode{healthNode:healthNode{tag:"pending"},address:slowServer.Listener.Addr().String()}
 g:=URLTestGroup{managedHealth:true,ctx:context.Background(),interval:time.Minute,link:"http://target.invalid/generate_204",
  history:urltest.NewHistoryStorage(),qualified:urltest.NewHistoryStorage(),outbounds:[]adapter.Outbound{fast,slow},
  logger:log.NewNOPFactory().Logger(),interruptGroup:interrupt.NewGroup()}
 public:=URLTest{managedHealth:true,group:&g}
 done:=make(chan struct{});go func(){g.urlTest(context.Background(),true);close(done)}()
 deadline:=time.Now().Add(2*time.Second)
 for g.current("tcp")!=fast && time.Now().Before(deadline) { time.Sleep(time.Millisecond) }
 ready:=g.current("tcp")==fast && public.Now()=="fast"
 select { case <-done: ready=false;default: }
 close(release);<-done
 if !ready { t.Fatal("runtime Now and traffic failed to expose first healthy node during partial probe batch") }
}
