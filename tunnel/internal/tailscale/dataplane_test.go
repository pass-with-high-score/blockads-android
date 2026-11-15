package tailscale

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strconv"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/nacl/box"
)

func TestDiscoRoundTrip(t *testing.T) {
	stA, err := NewState("")
	if err != nil {
		t.Fatalf("NewState A: %v", err)
	}
	stB, err := NewState("")
	if err != nil {
		t.Fatalf("NewState B: %v", err)
	}

	var txID [12]byte
	_, _ = rand.Read(txID[:])

	pingPkt, err := SealDiscoPing(stA.DiscoKey(), stB.DiscoKey().Public, txID, stA.NodeKey().Public)
	if err != nil {
		t.Fatalf("SealDiscoPing: %v", err)
	}
	if !IsDiscoPacket(pingPkt) {
		t.Fatalf("expected IsDiscoPacket=true")
	}

	msg, err := OpenDiscoPacket(stB.DiscoKey(), pingPkt)
	if err != nil {
		t.Fatalf("OpenDiscoPacket Ping: %v", err)
	}
	if msg.MsgType != DiscoMsgPing || msg.TxID != txID || msg.SenderDiscoPub != stA.DiscoKey().Public || msg.NodeKey != stA.NodeKey().Public {
		t.Fatalf("unexpected ping msg: %+v", msg)
	}

	srcAP := netip.MustParseAddrPort("192.168.1.50:51820")
	pongPkt, err := SealDiscoPong(stB.DiscoKey(), stA.DiscoKey().Public, txID, srcAP)
	if err != nil {
		t.Fatalf("SealDiscoPong: %v", err)
	}
	pongMsg, err := OpenDiscoPacket(stA.DiscoKey(), pongPkt)
	if err != nil {
		t.Fatalf("OpenDiscoPacket Pong: %v", err)
	}
	if pongMsg.MsgType != DiscoMsgPong || pongMsg.TxID != txID || pongMsg.PongSrc != srcAP {
		t.Fatalf("unexpected pong msg: %+v", pongMsg)
	}

	cmmAddrs := []netip.AddrPort{
		netip.MustParseAddrPort("192.168.1.10:41641"),
		netip.MustParseAddrPort("203.0.113.5:41641"),
	}
	cmmPkt, err := SealDiscoCallMeMaybe(stA.DiscoKey(), stB.DiscoKey().Public, cmmAddrs)
	if err != nil {
		t.Fatalf("SealDiscoCallMeMaybe: %v", err)
	}
	cmmMsg, err := OpenDiscoPacket(stB.DiscoKey(), cmmPkt)
	if err != nil {
		t.Fatalf("OpenDiscoPacket CallMeMaybe: %v", err)
	}
	if cmmMsg.MsgType != DiscoMsgCallMeMaybe || len(cmmMsg.MyNumber) != 2 || cmmMsg.MyNumber[0] != cmmAddrs[0] {
		t.Fatalf("unexpected CallMeMaybe msg: %+v", cmmMsg)
	}
}

func startLocalWebServerOnPeer(t *testing.T, dp *DataPlane, ipStr string, port int) func() {
	t.Helper()
	ln, err := dp.ListenTCP(net.ParseIP(ipStr), port)
	if err != nil {
		t.Fatalf("dp.ListenTCP(%s:%d): %v", ipStr, port, err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"server":"mac-local-web","status":"ok"}`))
	})
	srv := &http.Server{Handler: mux}
	go func() { _ = srv.Serve(ln) }()
	return func() {
		_ = srv.Close()
		_ = ln.Close()
	}
}

func TestDataPlaneDirectUDP_LocalWebServer(t *testing.T) {
	stA, err := NewState("")
	if err != nil {
		t.Fatalf("NewState A: %v", err)
	}
	stB, err := NewState("")
	if err != nil {
		t.Fatalf("NewState B: %v", err)
	}

	mgrA := NewManager(stA, nil)
	defer mgrA.Stop()
	mgrB := NewManager(stB, nil)
	defer mgrB.Stop()

	epsA := mgrA.DataPlane().Bind().LocalEndpoints()
	epsB := mgrB.DataPlane().Bind().LocalEndpoints()
	if len(epsA) == 0 || len(epsB) == 0 {
		t.Fatalf("expected non-empty local endpoints: A=%v B=%v", epsA, epsB)
	}

	online := true
	nodeA := &Node{
		ID:         1,
		Name:       "android-phone.tailnet.ts.net.",
		Key:        stA.NodeKey().PublicHex("nodekey:"),
		DiscoKey:   stA.DiscoKey().PublicHex("discokey:"),
		Addresses:  []string{"100.64.0.1/32"},
		AllowedIPs: []string{"100.64.0.1/32"},
		Endpoints:  epsA,
		Online:     &online,
		Hostinfo:   &Hostinfo{Hostname: "android-phone", OS: "android"},
	}
	nodeB := &Node{
		ID:         2,
		Name:       "macbook-pro.tailnet.ts.net.",
		Key:        stB.NodeKey().PublicHex("nodekey:"),
		DiscoKey:   stB.DiscoKey().PublicHex("discokey:"),
		Addresses:  []string{"100.64.0.2/32"},
		AllowedIPs: []string{"100.64.0.2/32", "0.0.0.0/0", "::/0"},
		Endpoints:  epsB,
		Online:     &online,
		Hostinfo:   &Hostinfo{Hostname: "macbook-pro", OS: "macos"},
	}

	mgrA.handleMapResponse(&MapResponse{
		Node:  nodeA,
		Peers: []*Node{nodeB},
	})
	mgrB.handleMapResponse(&MapResponse{
		Node:  nodeB,
		Peers: []*Node{nodeA},
	})

	stopWeb := startLocalWebServerOnPeer(t, mgrB.DataPlane(), "100.64.0.2", 8080)
	defer stopWeb()

	// Verify MagicDNS resolution for both FQDN and short hostname
	resolvedIP := mgrA.ResolveMagicDNS("macbook-pro.tailnet.ts.net")
	if resolvedIP == nil || !resolvedIP.Equal(net.ParseIP("100.64.0.2")) {
		t.Fatalf("MagicDNS FQDN resolve failed: got %v", resolvedIP)
	}
	shortIP := mgrA.ResolveMagicDNS("macbook-pro")
	if shortIP == nil || !shortIP.Equal(net.ParseIP("100.64.0.2")) {
		t.Fatalf("MagicDNS short name resolve failed: got %v", shortIP)
	}

	// Dial HTTP over Tailscale WireGuard + Disco direct UDP
	client := &http.Client{
		Timeout: 8 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				host, portStr, err := net.SplitHostPort(addr)
				if err != nil {
					return nil, err
				}
				port, _ := strconv.Atoi(portStr)
				ip := net.ParseIP(host)
				if ip == nil {
					ip = mgrA.ResolveMagicDNS(host)
				}
				conn, used, err := mgrA.DialTCP(ctx, ip, port)
				if !used {
					return nil, fmt.Errorf("expected Tailscale to handle dial to %s", addr)
				}
				return conn, err
			},
		},
	}

	resp, err := client.Get("http://macbook-pro.tailnet.ts.net:8080/api/health")
	if err != nil {
		t.Fatalf("HTTP GET over Tailscale direct UDP failed: %v", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read response body: %v", err)
	}
	if resp.StatusCode != http.StatusOK || string(body) != `{"server":"mac-local-web","status":"ok"}` {
		t.Fatalf("unexpected HTTP response: status=%d body=%s", resp.StatusCode, string(body))
	}
}

// mockDERPServer implements a minimal Tailscale DERP v2 server for E2E relay testing.
type mockDERPServer struct {
	privKey [32]byte
	pubKey  [32]byte
	mu      sync.RWMutex
	clients map[[32]byte]net.Conn
}

func newMockDERPServer(t *testing.T) (*httptest.Server, *DERPMap) {
	t.Helper()
	pub, priv, err := box.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("box.GenerateKey: %v", err)
	}
	s := &mockDERPServer{
		privKey: *priv,
		pubKey:  *pub,
		clients: make(map[[32]byte]net.Conn),
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/derp", s.serveDERP)
	ts := httptest.NewServer(mux)

	host, portStr, _ := net.SplitHostPort(ts.Listener.Addr().String())
	port, _ := strconv.Atoi(portStr)

	derpMap := &DERPMap{
		Regions: map[int]*DERPRegion{
			900: {
				RegionID:   900,
				RegionCode: "localtest",
				Nodes: []*DERPNode{
					{
						Name:     "900a",
						RegionID: 900,
						HostName: host,
						IPv4:     host,
						Port:     port,
					},
				},
			},
		},
	}
	return ts, derpMap
}

func writeTestDERPFrame(bw *bufio.Writer, frameType byte, payload []byte) error {
	var hdr [5]byte
	hdr[0] = frameType
	binary.BigEndian.PutUint32(hdr[1:5], uint32(len(payload)))
	if _, err := bw.Write(hdr[:]); err != nil {
		return err
	}
	if len(payload) > 0 {
		if _, err := bw.Write(payload); err != nil {
			return err
		}
	}
	return bw.Flush()
}

func (s *mockDERPServer) serveDERP(w http.ResponseWriter, r *http.Request) {
	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "hijack not supported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Upgrade", "DERP")
	w.Header().Set("Connection", "Upgrade")
	w.WriteHeader(http.StatusSwitchingProtocols)

	conn, brw, err := hj.Hijack()
	if err != nil {
		return
	}
	defer conn.Close()

	// 1. Send derpFrameServerKey
	serverKeyPayload := make([]byte, len(DERPMagic)+32)
	copy(serverKeyPayload, DERPMagic)
	copy(serverKeyPayload[len(DERPMagic):], s.pubKey[:])
	if err := writeTestDERPFrame(brw.Writer, derpFrameServerKey, serverKeyPayload); err != nil {
		return
	}

	// 2. Read derpFrameClientInfo
	ft, fl, err := readDERPFrameHeader(brw.Reader)
	if err != nil || ft != derpFrameClientInfo || fl < 32+24+box.Overhead {
		return
	}
	clientBuf := make([]byte, fl)
	if _, err := io.ReadFull(brw.Reader, clientBuf); err != nil {
		return
	}
	var clientKey [32]byte
	copy(clientKey[:], clientBuf[:32])
	var nonce [24]byte
	copy(nonce[:], clientBuf[32:56])
	opened, ok := box.Open(nil, clientBuf[56:], &nonce, &clientKey, &s.privKey)
	if !ok {
		return
	}
	var info map[string]any
	if err := json.Unmarshal(opened, &info); err != nil {
		return
	}

	// 3. Send derpFrameServerInfo
	plainServerInfo := []byte(`{"version":2}`)
	var sNonce [24]byte
	_, _ = rand.Read(sNonce[:])
	boxed := box.Seal(nil, plainServerInfo, &sNonce, &clientKey, &s.privKey)
	sInfoPayload := make([]byte, 24+len(boxed))
	copy(sInfoPayload[:24], sNonce[:])
	copy(sInfoPayload[24:], boxed)
	if err := writeTestDERPFrame(brw.Writer, derpFrameServerInfo, sInfoPayload); err != nil {
		return
	}

	s.mu.Lock()
	s.clients[clientKey] = conn
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		delete(s.clients, clientKey)
		s.mu.Unlock()
	}()

	for {
		ft, fl, err := readDERPFrameHeader(brw.Reader)
		if err != nil {
			return
		}
		payload := make([]byte, fl)
		if fl > 0 {
			if _, err := io.ReadFull(brw.Reader, payload); err != nil {
				return
			}
		}
		switch ft {
		case derpFrameSendPacket:
			if len(payload) < 32 {
				continue
			}
			var dstKey [32]byte
			copy(dstKey[:], payload[:32])
			pkt := payload[32:]
			s.mu.RLock()
			dstConn := s.clients[dstKey]
			s.mu.RUnlock()
			if dstConn != nil {
				recvPayload := make([]byte, 32+len(pkt))
				copy(recvPayload[:32], clientKey[:])
				copy(recvPayload[32:], pkt)
				var frameHdr [5]byte
				frameHdr[0] = derpFrameRecvPacket
				binary.BigEndian.PutUint32(frameHdr[1:5], uint32(len(recvPayload)))
				_, _ = dstConn.Write(append(frameHdr[:], recvPayload...))
			}
		case derpFramePing:
			if len(payload) == 8 {
				bw := bufio.NewWriter(conn)
				_ = writeTestDERPFrame(bw, derpFramePong, payload)
			}
		}
	}
}

func TestDataPlaneDERPRelay_NoDirectUDP(t *testing.T) {
	derpSrv, derpMap := newMockDERPServer(t)
	defer derpSrv.Close()

	stA, err := NewState("")
	if err != nil {
		t.Fatalf("NewState A: %v", err)
	}
	stB, err := NewState("")
	if err != nil {
		t.Fatalf("NewState B: %v", err)
	}

	mgrA := NewManager(stA, nil)
	defer mgrA.Stop()
	mgrB := NewManager(stB, nil)
	defer mgrB.Stop()

	mgrA.DataPlane().Bind().SetDERPTestOptions(true, true)
	mgrA.DataPlane().Bind().SetForceDERP(true)
	mgrB.DataPlane().Bind().SetDERPTestOptions(true, true)
	mgrB.DataPlane().Bind().SetForceDERP(true)

	online := true
	// Note: Endpoints is intentionally empty on both nodes to force 100% DERP relay!
	nodeA := &Node{
		ID:         10,
		Name:       "android-derp.tailnet.ts.net.",
		Key:        stA.NodeKey().PublicHex("nodekey:"),
		DiscoKey:   stA.DiscoKey().PublicHex("discokey:"),
		Addresses:  []string{"100.64.0.10/32"},
		AllowedIPs: []string{"100.64.0.10/32"},
		HomeDERP:   900,
		Online:     &online,
	}
	nodeB := &Node{
		ID:         20,
		Name:       "mac-derp.tailnet.ts.net.",
		Key:        stB.NodeKey().PublicHex("nodekey:"),
		DiscoKey:   stB.DiscoKey().PublicHex("discokey:"),
		Addresses:  []string{"100.64.0.20/32"},
		AllowedIPs: []string{"100.64.0.20/32"},
		HomeDERP:   900,
		Online:     &online,
	}

	mgrB.handleMapResponse(&MapResponse{
		Node:    nodeB,
		Peers:   []*Node{nodeA},
		DERPMap: derpMap,
	})
	mgrA.handleMapResponse(&MapResponse{
		Node:    nodeA,
		Peers:   []*Node{nodeB},
		DERPMap: derpMap,
	})

	stopWeb := startLocalWebServerOnPeer(t, mgrB.DataPlane(), "100.64.0.20", 8080)
	defer stopWeb()

	// Give both nodes a moment to establish their home DERP connection
	time.Sleep(150 * time.Millisecond)

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	conn, used, err := mgrA.DialTCP(ctx, net.ParseIP("100.64.0.20"), 8080)
	if !used || err != nil {
		t.Fatalf("DialTCP over DERP relay failed (used=%v): %v", used, err)
	}
	defer conn.Close()

	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "http://100.64.0.20:8080/api/health", nil)
	if err := req.Write(conn); err != nil {
		t.Fatalf("write HTTP request over DERP: %v", err)
	}
	resp, err := http.ReadResponse(bufio.NewReader(conn), req)
	if err != nil {
		t.Fatalf("read HTTP response over DERP: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK || string(body) != `{"server":"mac-local-web","status":"ok"}` {
		t.Fatalf("unexpected DERP HTTP response: status=%d body=%s", resp.StatusCode, string(body))
	}
}
