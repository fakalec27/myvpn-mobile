package ssh

import (
	"bytes"
	"context"
	"errors"
	"encoding/base64"
	"math/rand"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service/filemanager"

	"golang.org/x/crypto/ssh"
)

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[option.SSHOutboundOptions](registry, C.TypeSSH, NewOutbound)
}

var (
	_ adapter.InterfaceUpdateListener = (*Outbound)(nil)
	_ adapter.IdleConnectionKeeper    = (*Outbound)(nil)
	_ adapter.OutboundWithMultiplex   = (*Outbound)(nil)
)

type Outbound struct {
	outbound.Adapter
	logger            logger.ContextLogger
	dialer            N.Dialer
	serverAddr        M.Socksaddr
	user              string
	hostKey           []ssh.PublicKey
	hostKeyAlgorithms []string
	cipher            []string
	mac               []string
	kexAlgorithm      []string
	clientVersion     string
	authMethod        []ssh.AuthMethod
	clientAccess      sync.Mutex
	clientConn        net.Conn
	client            *ssh.Client
	streams           int
	closeIdle         bool
	clientDone chan struct{}
	livenessNeeded bool
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.SSHOutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	outbound := &Outbound{
		Adapter:           outbound.NewAdapterWithDialerOptions(C.TypeSSH, tag, []string{N.NetworkTCP}, options.DialerOptions),
		logger:            logger,
		dialer:            outboundDialer,
		serverAddr:        options.ServerOptions.Build(),
		user:              options.User,
		hostKeyAlgorithms: options.HostKeyAlgorithms,
		cipher:            options.Cipher,
		mac:               options.MAC,
		kexAlgorithm:      options.KexAlgorithm,
		clientVersion:     options.ClientVersion,
	}
	if outbound.serverAddr.Port == 0 {
		outbound.serverAddr.Port = 22
	}
	if outbound.user == "" {
		outbound.user = "root"
	}
	if outbound.clientVersion == "" {
		outbound.clientVersion = randomVersion()
	}
	if options.Password != "" {
		outbound.authMethod = append(outbound.authMethod, ssh.Password(options.Password))
	}
	if len(options.PrivateKey) > 0 || options.PrivateKeyPath != "" {
		var privateKey []byte
		if len(options.PrivateKey) > 0 {
			privateKey = []byte(strings.Join(options.PrivateKey, "\n"))
		} else {
			var err error
			privateKey, err = filemanager.ReadFile(ctx, os.ExpandEnv(options.PrivateKeyPath))
			if err != nil {
				return nil, E.Cause(err, "read private key")
			}
		}
		var signer ssh.Signer
		var err error
		if options.PrivateKeyPassphrase == "" {
			signer, err = ssh.ParsePrivateKey(privateKey)
		} else {
			signer, err = ssh.ParsePrivateKeyWithPassphrase(privateKey, []byte(options.PrivateKeyPassphrase))
		}
		if err != nil {
			return nil, E.Cause(err, "parse private key")
		}
		outbound.authMethod = append(outbound.authMethod, ssh.PublicKeys(signer))
	}
	if len(options.HostKey) > 0 {
		for _, hostKey := range options.HostKey {
			key, _, _, _, err := ssh.ParseAuthorizedKey([]byte(hostKey))
			if err != nil {
				return nil, E.Cause(err, "parse host key: ", hostKey)
			}
			outbound.hostKey = append(outbound.hostKey, key)
		}
	}
	return outbound, nil
}

func randomVersion() string {
	version := "SSH-2.0-OpenSSH_"
	if rand.Intn(2) == 0 {
		version += "7." + strconv.Itoa(rand.Intn(10))
	} else {
		version += "8." + strconv.Itoa(rand.Intn(9))
	}
	return version
}

func (s *Outbound) connect(ctx context.Context) (client *ssh.Client, err error) {
	s.clientAccess.Lock()
	defer s.clientAccess.Unlock()

	if s.client != nil {
		s.streams++
		return s.client, nil
	}

	conn, err := s.dialer.DialContext(ctx, N.NetworkTCP, s.serverAddr)
	if err != nil {
		return nil, err
	}
	if ctx.Done() != nil {
		handshakeConn := conn
		stopContext := context.AfterFunc(ctx, func() {
			_ = handshakeConn.Close()
		})
		defer func() {
			if !stopContext() {
				s.clearClientLocked()
				client = nil
				err = ctx.Err()
			}
		}()
	}
	config := &ssh.ClientConfig{
		User:              s.user,
		Auth:              s.authMethod,
		ClientVersion:     s.clientVersion,
		HostKeyAlgorithms: s.hostKeyAlgorithms,
		HostKeyCallback: func(hostname string, remote net.Addr, key ssh.PublicKey) error {
			if len(s.hostKey) == 0 {
				return nil
			}
			serverKey := key.Marshal()
			for _, hostKey := range s.hostKey {
				if bytes.Equal(serverKey, hostKey.Marshal()) {
					return nil
				}
			}
			return E.New("host key mismatch, server send ", key.Type(), " ", base64.StdEncoding.EncodeToString(serverKey))
		},
	}
	if len(s.cipher) > 0 {
		config.Ciphers = s.cipher
	}
	if len(s.mac) > 0 {
		config.MACs = s.mac
	}
	if len(s.kexAlgorithm) > 0 {
		config.KeyExchanges = s.kexAlgorithm
	}
	clientConn, chans, reqs, err := ssh.NewClientConn(conn, s.serverAddr.Addr.String(), config)
	if err != nil {
		conn.Close()
		return nil, E.Cause(err, "connect to ssh server")
	}

	client = ssh.NewClient(clientConn, chans, reqs)

	s.clientConn = conn
	s.client = client
	s.streams = 1
	done := make(chan struct{})
	s.clientDone = done
	go s.keepAlive(client, done, 30*time.Second, 10*time.Second)

	go func(active *ssh.Client) {
		_ = active.Wait()
		s.journal("ssh-reset", "eof")
		s.invalidateClient(active)
	}(client)

	return client, nil
}

func (s *Outbound) InterfaceUpdated(ctx context.Context) {
	s.journal("ssh-reset", "interface_change")
	s.closeClient()
}

func (s *Outbound) MultiplexEnabled() bool {
	return true
}

func (s *Outbound) SetKeepIdleConnections(keep bool) {
	s.clientAccess.Lock()
	s.closeIdle = !keep
	s.clientAccess.Unlock()
	if !keep {
		s.CloseIdleConnections()
	}
}

func (s *Outbound) CloseIdleConnections() {
	s.clientAccess.Lock()
	if s.client == nil || s.streams > 0 {
		s.clientAccess.Unlock()
		return
	}
	clientConn := s.clientConn
	s.clearClientLocked()
	s.clientAccess.Unlock()
	common.Close(clientConn)
}

func (s *Outbound) releaseStream(client *ssh.Client, keepSession bool) {
	s.clientAccess.Lock()
	if s.client != client {
		s.clientAccess.Unlock()
		return
	}
	s.streams--
	if !s.closeIdle || keepSession || s.streams > 0 {
		s.clientAccess.Unlock()
		return
	}
	clientConn := s.clientConn
	s.clearClientLocked()
	s.clientAccess.Unlock()
	common.Close(clientConn)
}

func (s *Outbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateInitialize {
		return nil
	}
	scope.Add(func() error {
		s.closeClient()
		return nil
	})
	return nil
}

func (s *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	client, err := s.connect(dialCtx)
	if err != nil {
		return nil, err
	}
	conn, err := client.DialContext(dialCtx, network, destination.String())
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			s.clientAccess.Lock()
			if s.client == client { s.livenessNeeded = true }
			s.clientAccess.Unlock()
		}
		s.releaseStream(client, false)
		return nil, err
	}
	keepSession := adapter.KeepSessionFromContext(ctx)
	return &chanConnWrapper{Conn: conn, onClose: func() { s.releaseStream(client, keepSession) }}, nil
}

func (s *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}

type chanConnWrapper struct {
	net.Conn
	closeOnce sync.Once
	onClose   func()
}

func (c *chanConnWrapper) Close() error {
	err := c.Conn.Close()
	c.closeOnce.Do(c.onClose)
	return err
}

func (c *chanConnWrapper) SetDeadline(t time.Time) error {
	return os.ErrInvalid
}

func (c *chanConnWrapper) SetReadDeadline(t time.Time) error {
	return os.ErrInvalid
}

func (c *chanConnWrapper) SetWriteDeadline(t time.Time) error {
	return os.ErrInvalid
}

// Caller holds clientAccess. Never log credentials or transport destinations.
func (s *Outbound) clearClientLocked() {
	if s.clientDone != nil { close(s.clientDone); s.clientDone = nil }
	s.client = nil
	s.clientConn = nil
	s.streams = 0
	s.livenessNeeded = false
}
func (s *Outbound) invalidateClient(client *ssh.Client) {
	s.clientAccess.Lock()
	if s.client != client { s.clientAccess.Unlock(); return }
	conn := s.clientConn
	s.clearClientLocked()
	s.clientAccess.Unlock()
	if client != nil { _ = client.Close() }
	_ = common.Close(conn)
}
func (s *Outbound) closeClient() {
	s.clientAccess.Lock()
	client, conn := s.client, s.clientConn
	s.clearClientLocked()
	s.clientAccess.Unlock()
	if client != nil { _ = client.Close() }
	_ = common.Close(conn)
}
func (s *Outbound) keepAlive(client *ssh.Client, done <-chan struct{}, interval, timeout time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select { case <-done: return; case <-ticker.C: }
		s.clientAccess.Lock()
		active := s.client == client && (s.streams > 0 || s.livenessNeeded)
		s.clientAccess.Unlock()
		if !active { continue }
		reply := make(chan error, 1)
		go func() {
			// OpenSSH may reject this request. A negative reply still proves liveness.
			_, _, err := client.SendRequest("keepalive@openssh.com", true, nil)
			reply <- err
		}()
		timer := time.NewTimer(timeout)
		select {
		case <-done: timer.Stop(); return
		case err := <-reply:
			timer.Stop()
			if err != nil {s.journal("ssh-reset", "heartbeat_error")}
			if err == nil {
				s.clientAccess.Lock()
				if s.client == client { s.livenessNeeded = false }
				s.clientAccess.Unlock()
				continue
			}
		case <-timer.C:
			s.journal("ssh-reset", "heartbeat_timeout")
		}
		s.invalidateClient(client)
		return
	}
}

func (s *Outbound) journal(name, reason string) {
 if s.logger != nil {s.logger.Info("myvpn_event name=",name," reason=",reason)}
}
