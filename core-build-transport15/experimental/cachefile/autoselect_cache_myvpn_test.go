package cachefile

import (
 "path/filepath"
 "testing"
 "github.com/sagernet/bbolt"
)
func TestMyVPNProfileSelectionMigrationAndIsolation(t *testing.T) {
 db,err:=bbolt.Open(filepath.Join(t.TempDir(),"cache.db"),0600,nil);if err!=nil {t.Fatal(err)};defer db.Close()
 old:=&CacheFile{DB:db}
 if err:=old.StoreSelected("same-selector","manual");err!=nil {t.Fatal(err)}
 one:=&CacheFile{DB:db,cacheID:append([]byte{0},[]byte("profile:1")...),selectionMigration:true}
 two:=&CacheFile{DB:db,cacheID:append([]byte{0},[]byte("profile:2")...),selectionMigration:true}
 if one.LoadSelected("same-selector")!="manual" {t.Fatal("old manual choice not available for initial migration")}
 if err:=one.StoreSelected("same-selector",one.LoadSelected("same-selector"));err!=nil {t.Fatal(err)}
 if err:=two.StoreSelected("same-selector","auto");err!=nil {t.Fatal(err)}
 if err:=old.StoreSelected("same-selector","other-manual");err!=nil {t.Fatal(err)}
 if one.LoadSelected("same-selector")!="manual" || two.LoadSelected("same-selector")!="auto" {t.Fatal("profiles or later legacy changes contaminated saved modes")}
 custom:=&CacheFile{DB:db,cacheID:append([]byte{0},[]byte("custom")...)}
 if custom.LoadSelected("same-selector")!="" {t.Fatal("custom namespace borrowed legacy state without opt-in")}
}
