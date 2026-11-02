package tailscale

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestControlClientRegistrationMock(t *testing.T) {
	serverStatic, err := NewKeyPair()
	if err != nil {
		t.Fatalf("NewKeyPair failed: %v", err)
	}

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/key":
			resp := ControlKeyResponse{
				PublicKey: "mkey:" + serverStatic.PublicHex(""),
			}
			json.NewEncoder(w).Encode(resp)
		case "/ts2021":
			b64Handshake := r.Header.Get("X-Tailscale-Handshake")
			msg1, err := base64.StdEncoding.DecodeString(b64Handshake)
			if err != nil || len(msg1) != 101 {
				http.Error(w, "bad handshake", http.StatusBadRequest)
				return
			}

			// Perform minimal Noise responder
			serverSess, _ := NewNoiseIKClient(1, serverStatic, [32]byte{})
			var re [32]byte
			copy(re[:], msg1[5:37])
			serverSess.mixHash(re[:])

			c1, _ := serverSess.mixDH(&serverStatic.Private, &re)
			var clientPub [32]byte
			var zeroNonce [12]byte
			c1.Open(clientPub[:0], zeroNonce[:], msg1[37:85], serverSess.h[:])
			serverSess.mixHash(msg1[37:85])

			c2, _ := serverSess.mixDH(&serverStatic.Private, &clientPub)
			c2.Open(nil, zeroNonce[:], msg1[85:101], serverSess.h[:])
			serverSess.mixHash(msg1[85:101])

			respE, _ := NewKeyPair()
			resp := make([]byte, 51)
			resp[0] = msgTypeResponse
			resp[2] = 48
			copy(resp[3:35], respE.Public[:])
			serverSess.mixHash(respE.Public[:])
			serverSess.mixDH(&respE.Private, &re)
			cResp, _ := serverSess.mixDH(&respE.Private, &clientPub)
			cResp.Seal(resp[35:35], zeroNonce[:], nil, serverSess.h[:])
			serverSess.mixHash(resp[35:51])

			w.Header().Set("Connection", "upgrade")
			w.Header().Set("Upgrade", "tailscale-control-protocol")
			w.Header().Set("X-Tailscale-Handshake", base64.StdEncoding.EncodeToString(resp))
			w.WriteHeader(http.StatusSwitchingProtocols)
		default:
			http.NotFound(w, r)
		}
	})

	server := httptest.NewServer(handler)
	defer server.Close()

	st, err := NewState("")
	if err != nil {
		t.Fatalf("NewState failed: %v", err)
	}

	client := NewControlClient(st, nil)
	client.controlURL = server.URL

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = client.FetchServerPublicKey(ctx)
	if err != nil {
		t.Fatalf("FetchServerPublicKey failed: %v", err)
	}

	if client.serverKey != serverStatic.Public {
		t.Fatalf("serverKey mismatch")
	}
}

func TestRealTailscaleNoiseHandshake(t *testing.T) {
	st, err := NewState("")
	if err != nil {
		t.Fatal(err)
	}
	client := NewControlClient(st, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	conn, err := client.dialNoiseTLS(ctx)
	if err != nil {
		t.Fatalf("dialNoiseTLS failed: %v", err)
	}
	defer conn.Close()
	t.Logf("Handshake success! conn=%v", conn != nil)

	authURL, _, err := client.Register(ctx, "blockads-test", "")
	if err != nil {
		t.Fatalf("Register failed: %v", err)
	}
	t.Logf("Got AuthURL: %s", authURL)
	if authURL == "" {
		t.Fatalf("Expected non-empty authURL")
	}
}
