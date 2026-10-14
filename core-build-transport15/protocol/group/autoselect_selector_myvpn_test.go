package group

import (
 "context"
 "testing"
 "github.com/sagernet/sing-box/adapter"
 "github.com/sagernet/sing-box/adapter/outbound"
 "github.com/sagernet/sing-box/log"
 "github.com/sagernet/sing/service"
)
type selectionTestCache struct {adapter.CacheFile;selected string}
func(c *selectionTestCache) LoadSelected(string) string {return c.selected}
func(c *selectionTestCache) StoreSelected(_ string,value string) error {c.selected=value;return nil}
func(c *selectionTestCache) SelectionMigrationEnabled() bool {return true}
type selectionTestManager struct {adapter.OutboundManager;nodes map[string]adapter.Outbound}
func(m *selectionTestManager) Outbound(tag string) (adapter.Outbound,bool) {node,ok:=m.nodes[tag];return node,ok}
func TestMyVPNSelectorPreservesCachedManualAndRejectsMissingOnRefresh(t *testing.T) {
 for _,test:=range []struct{cached string;strict bool;want string;fail bool}{
  {"manual",true,"manual",false},{"missing",true,"",true},{"missing",false,"auto",false},{"",true,"auto",false},
 } {
  cache:=&selectionTestCache{selected:test.cached}
  ctx:=service.ContextWith[adapter.CacheFile](context.Background(),cache)
  manager:=&selectionTestManager{nodes:map[string]adapter.Outbound{"auto":&healthNode{tag:"auto"},"manual":&healthNode{tag:"manual"}}}
  selector:=Selector{Adapter:outbound.NewAdapter("selector","choose",nil,nil),ctx:ctx,outbound:manager,tags:[]string{"auto","manual"},defaultTag:"auto",
   outbounds:make(map[string]adapter.Outbound),preserveSelection:test.strict,logger:log.NewNOPFactory().Logger()}
  err:=selector.Start(adapter.StartStateStart,nil)
  if (err!=nil)!=test.fail {t.Fatalf("unexpected missing-selection result: %v",err)}
  if !test.fail && (selector.Now()!=test.want || cache.selected!=test.want) {t.Fatal("restored/default choice was not preserved in migrated scope")}
 }
}
