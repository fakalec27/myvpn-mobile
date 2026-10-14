package tls

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	stdtls "crypto/tls"
	"crypto/x509"
	"io"
	"math/big"
	"net"
	"testing"
	"time"
)

func releaseTestCert(t *testing.T) stdtls.Certificate {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		DNSNames:     []string{"example.golang"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return stdtls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

func releaseTestServer(t *testing.T, conn net.Conn, cert stdtls.Certificate, maxVersion uint16) {
	server := stdtls.Server(conn, &stdtls.Config{Certificates: []stdtls.Certificate{cert}, MaxVersion: maxVersion})
	defer server.Close()
	if err := server.Handshake(); err != nil {
		return
	}
	_, _ = io.Copy(io.Discard, server)
}

func TestReleaseHandshakeState(t *testing.T) {
	for _, id := range []ClientHelloID{HelloChrome_Auto, HelloFirefox_Auto, HelloSafari_Auto} {
		t.Run(id.Str(), func(t *testing.T) {
			client, server := net.Pipe()
			go releaseTestServer(t, server, releaseTestCert(t), 0)
			uconn := UClient(client, &Config{ServerName: "example.golang", InsecureSkipVerify: true}, id)
			defer uconn.Close()

			if err := uconn.BuildHandshakeState(); err != nil {
				t.Fatal(err)
			}
			uconn.ReleaseHandshakeState()
			if uconn.clientHelloSpec == nil {
				t.Fatal("released before the handshake completed")
			}

			if err := uconn.Handshake(); err != nil {
				t.Fatal(err)
			}
			uconn.ReleaseHandshakeState()
			if uconn.clientHelloSpec != nil || uconn.echCtx != nil || uconn.ech != nil {
				t.Fatal("state not released after the handshake")
			}
			uconn.ReleaseHandshakeState()
			if err := uconn.Handshake(); err != nil {
				t.Fatalf("second Handshake after release: %v", err)
			}
			if _, err := uconn.Write([]byte{1}); err != nil {
				t.Fatalf("write after release: %v", err)
			}
		})
	}
}

// Browser presets enable renegotiation (renegotiation_info): on TLS 1.2 the state
// stays for a possible renegotiation; on TLS 1.3 there is none and it is released.
func TestReleaseHandshakeStateRenegotiation(t *testing.T) {
	for _, c := range []struct {
		name       string
		maxVersion uint16
		released   bool
	}{
		{"tls12", stdtls.VersionTLS12, false},
		{"tls13", stdtls.VersionTLS13, true},
	} {
		t.Run(c.name, func(t *testing.T) {
			client, server := net.Pipe()
			go releaseTestServer(t, server, releaseTestCert(t), c.maxVersion)
			uconn := UClient(client, &Config{ServerName: "example.golang", InsecureSkipVerify: true}, HelloChrome_Auto)
			defer uconn.Close()
			if err := uconn.Handshake(); err != nil {
				t.Fatal(err)
			}
			if uconn.config.Renegotiation == RenegotiateNever {
				t.Fatal("chrome preset is expected to enable renegotiation")
			}
			uconn.ReleaseHandshakeState()
			if released := uconn.clientHelloSpec == nil; released != c.released {
				t.Fatalf("released = %v, want %v", released, c.released)
			}
		})
	}
}
