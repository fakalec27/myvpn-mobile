package ssh

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/crypto/ssh"
	"net"
	"os"
	"testing"
	"time"
)

// Real local SSH framing with configurable non-reply and channel rejection.
// No network outside loopback, credentials, or production private keys.
func localTransport(t *testing.T, replyRequests, replyChannels bool) (*ssh.Client, net.Conn, func()) {
	t.Helper()
	_, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.NewSignerFromKey(key)
	if err != nil {
		t.Fatal(err)
	}
	cfg := &ssh.ServerConfig{NoClientAuth: true}
	cfg.AddHostKey(signer)
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	serverReady := make(chan *ssh.ServerConn, 1)
	go func() {
		conn, e := l.Accept()
		if e != nil {
			return
		}
		server, channels, requests, e := ssh.NewServerConn(conn, cfg)
		if e != nil {
			conn.Close()
			return
		}
		serverReady <- server
		go func() {
			for r := range requests {
				if replyRequests {
					_ = r.Reply(false, nil)
				}
			}
		}()
		for ch := range channels {
			if replyChannels {
				_ = ch.Reject(ssh.ConnectionFailed, "test destination refused")
			}
		}
	}()
	conn, err := net.Dial("tcp", l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	cc, ch, req, err := ssh.NewClientConn(conn, l.Addr().String(), &ssh.ClientConfig{User: "test", HostKeyCallback: ssh.InsecureIgnoreHostKey()})
	if err != nil {
		t.Fatal(err)
	}
	client := ssh.NewClient(cc, ch, req)
	server := <-serverReady
	stop := func() { server.Close(); client.Close(); conn.Close(); l.Close() }
	t.Cleanup(stop)
	return client, conn, stop
}
func cachedTransport(client *ssh.Client, conn net.Conn) *Outbound {
	return &Outbound{client: client, clientConn: conn, clientDone: make(chan struct{}), streams: 1}
}
func waitTransportGone(t *testing.T, s *Outbound) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		s.clientAccess.Lock()
		gone := s.client == nil
		s.clientAccess.Unlock()
		if gone {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("dead SSH generation remained cached")
}
func TestMyVPNSSHTransportNegativeHeartbeatReplyIsHealthy(t *testing.T) {
	c, conn, _ := localTransport(t, true, true)
	s := cachedTransport(c, conn)
	go s.keepAlive(c, s.clientDone, 5*time.Millisecond, 50*time.Millisecond)
	time.Sleep(60 * time.Millisecond)
	s.clientAccess.Lock()
	alive := s.client == c
	s.clientAccess.Unlock()
	s.closeClient()
	if !alive {
		t.Fatal("negative keepalive reply killed healthy SSH")
	}
}
func TestMyVPNSSHTransportBlackholeHeartbeatClearsCacheAndChannelOpen(t *testing.T) {
	c, conn, _ := localTransport(t, false, false)
	s := cachedTransport(c, conn)
	result := make(chan error, 1)
	go func() {
		_, e := s.DialContext(context.Background(), "tcp", M.ParseSocksaddr("example.invalid:443"))
		result <- e
	}()
	go s.keepAlive(c, s.clientDone, 5*time.Millisecond, 20*time.Millisecond)
	waitTransportGone(t, s)
	select {
	case e := <-result:
		if e == nil {
			t.Fatal("blackhole channel unexpectedly succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("closing failed SSH did not release channel-open goroutine")
	}
}
func TestMyVPNSSHTransportChannelCancellationDoesNotKillSharedClient(t *testing.T) {
	c, conn, _ := localTransport(t, true, false)
	s := cachedTransport(c, conn)
	defer s.closeClient()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	_, e := s.DialContext(ctx, "tcp", M.ParseSocksaddr("example.invalid:443"))
	if e == nil {
		t.Fatal("cancelled channel-open succeeded")
	}
	s.clientAccess.Lock()
	alive := s.client == c
	s.clientAccess.Unlock()
	if !alive {
		t.Fatal("one cancelled destination killed shared SSH")
	}
}
func TestMyVPNSSHTransportChannelRefusalDoesNotKillSharedClient(t *testing.T) {
	c, conn, _ := localTransport(t, true, true)
	s := cachedTransport(c, conn)
	defer s.closeClient()
	_, e := s.DialContext(context.Background(), "tcp", M.ParseSocksaddr("example.invalid:443"))
	if e == nil {
		t.Fatal("refused channel unexpectedly succeeded")
	}
	s.clientAccess.Lock()
	alive := s.client == c
	s.clientAccess.Unlock()
	if !alive {
		t.Fatal("destination refusal killed shared SSH")
	}
}
func TestMyVPNSSHTransportOldGenerationCannotInvalidateReplacement(t *testing.T) {
	old, _, _ := localTransport(t, false, false)
	fresh, conn, _ := localTransport(t, true, true)
	s := cachedTransport(fresh, conn)
	defer s.closeClient()
	s.invalidateClient(old)
	if s.client != fresh {
		t.Fatal("old generation invalidated replacement")
	}
}
func TestMyVPNSSHTransportInterfaceUpdateInvalidatesSynchronously(t *testing.T) {
	c, conn, _ := localTransport(t, true, true)
	s := cachedTransport(c, conn)
	done := s.clientDone
	s.InterfaceUpdated(context.Background())
	waitTransportGone(t, s)
	select {
	case <-done:
	default:
		t.Fatal("heartbeat did not stop on interface update")
	}
}
func TestMyVPNSSHTransportIdleSessionDoesNotSendHeartbeat(t *testing.T) {
	c, conn, _ := localTransport(t, false, false)
	s := cachedTransport(c, conn)
	s.streams = 0
	go s.keepAlive(c, s.clientDone, 5*time.Millisecond, 20*time.Millisecond)
	time.Sleep(60 * time.Millisecond)
	s.clientAccess.Lock()
	alive := s.client == c
	s.clientAccess.Unlock()
	s.closeClient()
	if !alive {
		t.Fatal("idle SSH session sent heartbeat and timed out")
	}
}
func TestMyVPNSSHTransportTimedOutOnlyChannelStillProbesLiveness(t *testing.T) {
	c, conn, _ := localTransport(t, false, false)
	s := cachedTransport(c, conn)
	s.streams = 0
	defer s.closeClient()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	_, e := s.DialContext(ctx, "tcp", M.ParseSocksaddr("example.invalid:443"))
	if e == nil {
		t.Fatal("blackhole channel did not time out")
	}
	s.clientAccess.Lock()
	needsProbe := s.livenessNeeded && s.streams == 0
	s.clientAccess.Unlock()
	if !needsProbe {
		t.Fatal("timed out sole channel abandoned SSH liveness check")
	}
	go s.keepAlive(c, s.clientDone, 5*time.Millisecond, 20*time.Millisecond)
	waitTransportGone(t, s)
}

type handshakeTransportDialer struct{ address string }

func (d handshakeTransportDialer) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, "tcp", d.address)
}
func (d handshakeTransportDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}
func TestMyVPNSSHTransportHandshakeHonorsCallerDeadline(t *testing.T) {
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		c, e := l.Accept()
		if e == nil {
			accepted <- c
		}
	}()
	s := &Outbound{dialer: handshakeTransportDialer{l.Addr().String()}, serverAddr: M.ParseSocksaddr(l.Addr().String()), user: "test", clientVersion: "SSH-2.0-myVPN-test"}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, e = s.DialContext(ctx, "tcp", M.ParseSocksaddr("example.invalid:443"))
	if e == nil || time.Since(start) > time.Second {
		t.Fatal("SSH handshake ignored caller deadline")
	}
	select {
	case c := <-accepted:
		c.Close()
	case <-time.After(time.Second):
		t.Fatal("local TCP not accepted")
	}
	if s.client != nil {
		t.Fatal("cancelled handshake cached a client")
	}
}
func TestMyVPNSSHTransportEOFClearsCacheAndReconnectsWithoutDialContextLifetime(t *testing.T) {
	_, key, e := ed25519.GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	signer, e := ssh.NewSignerFromKey(key)
	if e != nil {
		t.Fatal(e)
	}
	cfg := &ssh.ServerConfig{NoClientAuth: true}
	cfg.AddHostKey(signer)
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	accepted := make(chan *ssh.ServerConn, 2)
	go func() {
		for {
			conn, e := l.Accept()
			if e != nil {
				return
			}
			go func(conn net.Conn) {
				server, channels, requests, e := ssh.NewServerConn(conn, cfg)
				if e != nil {
					conn.Close()
					return
				}
				accepted <- server
				go func() {
					for r := range requests {
						_ = r.Reply(false, nil)
					}
				}()
				for ch := range channels {
					_ = ch.Reject(ssh.ConnectionFailed, "test destination refused")
				}
			}(conn)
		}
	}()
	s := &Outbound{dialer: handshakeTransportDialer{l.Addr().String()}, serverAddr: M.ParseSocksaddr(l.Addr().String()), user: "test", clientVersion: "SSH-2.0-myVPN-test"}
	defer s.closeClient()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	_, e = s.DialContext(ctx, "tcp", M.ParseSocksaddr("example.invalid:443"))
	cancel()
	if e == nil {
		t.Fatal("test channel was not rejected")
	}
	first := s.client
	if first == nil {
		t.Fatal("successful SSH transport lost when destination rejected")
	}
	time.Sleep(10 * time.Millisecond)
	if s.client != first {
		t.Fatal("dial context cancellation killed completed SSH transport")
	}
	server := <-accepted
	server.Close()
	waitTransportGone(t, s)
	_, e = s.DialContext(context.Background(), "tcp", M.ParseSocksaddr("example.invalid:443"))
	if e == nil || s.client == nil || s.client == first {
		t.Fatal("EOF did not create fresh SSH transport on next request")
	}
	second := <-accepted
	defer second.Close()
}
