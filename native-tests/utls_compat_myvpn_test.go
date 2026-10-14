//go:build with_utls

package tls

import (
    "bytes"
    "net"
    "testing"
    utls "github.com/metacubex/utls"
)

func TestMyVPNBrowserHybridKeyShares(t *testing.T) {
    for _, preset := range []struct{name string; id utls.ClientHelloID}{
        {"chrome",utls.HelloChrome_Auto},{"firefox",utls.HelloFirefox_Auto},
    } {
        t.Run(preset.name,func(t *testing.T) {
            client:=utls.UClient(&net.TCPConn{},&utls.Config{ServerName:"example.org"},preset.id)
            if err:=client.BuildHandshakeState();err!=nil {t.Fatal(err)}
            hybrid,plain:=-1,-1
            var hybridClassical,plainData []byte
            for _,extension:=range client.Extensions {
                if shares,ok:=extension.(*utls.KeyShareExtension);ok {
                    for index,share:=range shares.KeyShares {
                        if share.Group==utls.X25519MLKEM768 {
                            hybrid=index
                            if len(share.Data)<32 {t.Fatal("Hybrid classical share missing")}
                            hybridClassical=share.Data[len(share.Data)-32:]
                        }
                        if share.Group==utls.X25519 {plain=index;plainData=share.Data}
                    }
                }
            }
            if hybrid<0 || (plain>=0 && hybrid>plain) {t.Fatal("REALITY-compatible hybrid-first share missing")}
            keys:=client.HandshakeState.State13.KeyShareKeys
            if keys==nil || (keys.Ecdhe==nil && keys.MlkemEcdhe==nil) {t.Fatal("REALITY authentication key unavailable")}
            if preset.name=="firefox" && plain>=0 && !bytes.Equal(hybridClassical,plainData) {t.Fatal("Firefox148 classical/hybrid key reuse missing")}
        })
    }
}
