package group
import (
 "context"
 "testing"
 "github.com/sagernet/sing-box/option"
 N "github.com/sagernet/sing/common/network"
)
func TestMyVPNEmptyManagedAutoAllowedAndCannotRoute(t *testing.T) {
 if _,err:=NewURLTest(context.Background(),nil,nil,"managed-empty",option.URLTestOutboundOptions{MyVPNHealthCheck:true});err!=nil {t.Fatal(err)}
 if _,err:=NewURLTest(context.Background(),nil,nil,"legacy-empty",option.URLTestOutboundOptions{});err==nil {t.Fatal("legacy validation changed")}
 g:=URLTestGroup{managedHealth:true}
 if node,usable:=g.Select(N.NetworkTCP);node!=nil || usable {t.Fatal("empty Auto must fail closed")}
}
