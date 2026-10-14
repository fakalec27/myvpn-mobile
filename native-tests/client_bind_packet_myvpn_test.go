package wireguard

import (
    "bytes"
    "net"
    "net/netip"
    "testing"
    "time"
    "github.com/sagernet/wireguard-go/conn"
)

type headerPacketFixture struct { incoming []byte; written []byte; address net.Addr }
func (p *headerPacketFixture) ReadFrom(buffer []byte) (int,net.Addr,error) { return copy(buffer,p.incoming),p.address,nil }
func (p *headerPacketFixture) WriteTo(buffer []byte,address net.Addr) (int,error) { p.written=append([]byte(nil),buffer...);return len(buffer),nil }
func (p *headerPacketFixture) Close() error { return nil }
func (p *headerPacketFixture) LocalAddr() net.Addr { return p.address }
func (p *headerPacketFixture) SetDeadline(time.Time) error { return nil }
func (p *headerPacketFixture) SetReadDeadline(time.Time) error { return nil }
func (p *headerPacketFixture) SetWriteDeadline(time.Time) error { return nil }

func TestMyVPNClientBindActualSendReceiveHeaders(t *testing.T) {
    address:=netip.MustParseAddrPort("127.0.0.1:51820")
    original:=[]byte{0xf1,0xab,0xfe,0x80,0x10,0x20,0x30,0x40}
    for _,test:=range []struct{name string;reserved [3]uint8;send,receive []byte}{
        {"AWG full header",[3]uint8{},original,original},
        {"WARP reserved",[3]uint8{1,2,3},[]byte{0xf1,1,2,3,0x10,0x20,0x30,0x40},[]byte{0xf1,0,0,0,0x10,0x20,0x30,0x40}},
    } {
        t.Run(test.name,func(t *testing.T){
            packet:=&headerPacketFixture{incoming:append([]byte(nil),original...),address:net.UDPAddrFromAddrPort(address)}
            bind:=&ClientBind{reserved:test.reserved,reservedForEndpoint:make(map[netip.AddrPort][3]uint8),done:make(chan struct{}),conn:&wireConn{PacketConn:packet,done:make(chan struct{})}}
            if err:=bind.Send([][]byte{append([]byte(nil),original...)},remoteEndpoint(address),0);err!=nil { t.Fatal(err) }
            if !bytes.Equal(packet.written,test.send) { t.Fatal("send corrupted the packet-header contract") }
            buffers:=[][]byte{make([]byte,64)};sizes:=make([]int,1);endpoints:=make([]conn.Endpoint,1)
            count,err:=bind.receive(buffers,sizes,endpoints)
            if err!=nil || count!=1 { t.Fatalf("receive failed: count=%d error=%v",count,err) }
            if !bytes.Equal(buffers[0][:sizes[0]],test.receive) { t.Fatal("receive corrupted the packet-header contract") }
        })
    }
}
