package urltest

import (
 "context"
 "net"
 "net/http"
 "net/http/httptest"
 "testing"
 M "github.com/sagernet/sing/common/metadata"
)

type healthTestDialer struct{}
func (healthTestDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn,error) {
 return (&net.Dialer{}).DialContext(ctx,network,destination.String())
}
func (healthTestDialer) ListenPacket(ctx context.Context,destination M.Socksaddr) (net.PacketConn,error) {
 return (&net.ListenConfig{}).ListenPacket(ctx,"udp","127.0.0.1:0")
}
func TestMyVPNHealthStatus(t *testing.T) {
 for _,code:=range []int{200,204,302,403,500} {
  t.Run(http.StatusText(code),func(t *testing.T) {
   server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) { w.WriteHeader(code) }))
   defer server.Close()
   _,err:=URLTest(context.Background(),server.URL,healthTestDialer{})
   if (err==nil)!=(code>=200 && code<300) { t.Fatalf("unexpected eligibility for HTTP %d: %v",code,err) }
   _,err=URLTest(context.Background(),server.URL+"/generate_204",healthTestDialer{})
   if (err==nil)!=(code==204) { t.Fatalf("204 semantics not enforced for HTTP %d: %v",code,err) }
  })
 }
}
