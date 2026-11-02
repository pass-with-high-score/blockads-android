package tailscale

import (
	"bytes"
	"testing"
)

func TestNoiseIKHandshakeAndFraming(t *testing.T) {
	clientKey, err := NewKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	serverKey, err := NewKeyPair()
	if err != nil {
		t.Fatal(err)
	}

	clientSess, err := NewNoiseIKClient(1, clientKey, serverKey.Public)
	if err != nil {
		t.Fatal(err)
	}

	initMsg, err := clientSess.CreateHandshakeMessage(1)
	if err != nil {
		t.Fatal(err)
	}
	if len(initMsg) != 101 {
		t.Fatalf("expected 101 bytes init, got %d", len(initMsg))
	}

	// Simulate server
	serverSess, err := NewNoiseIKClient(1, serverKey, serverKey.Public)
	if err != nil {
		t.Fatal(err)
	}

	// Server processes client initiation
	// We verify response generation
	respE, err := NewKeyPair()
	if err != nil {
		t.Fatal(err)
	}

	var re [32]byte
	copy(re[:], initMsg[5:37])
	serverSess.mixHash(re[:])

	c1, err := serverSess.mixDH(&serverKey.Private, &re)
	if err != nil {
		t.Fatal(err)
	}

	var clientPub [32]byte
	var zeroNonce [12]byte
	if _, err := c1.Open(clientPub[:0], zeroNonce[:], initMsg[37:85], serverSess.h[:]); err != nil {
		t.Fatalf("server decrypt machine pub: %v", err)
	}
	if clientPub != clientKey.Public {
		t.Fatalf("recovered client pub mismatch")
	}
	serverSess.mixHash(initMsg[37:85])

	c2, err := serverSess.mixDH(&serverKey.Private, &clientPub)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c2.Open(nil, zeroNonce[:], initMsg[85:101], serverSess.h[:]); err != nil {
		t.Fatalf("server decrypt tag: %v", err)
	}
	serverSess.mixHash(initMsg[85:101])

	// Create server response
	resp := make([]byte, 51)
	resp[0] = msgTypeResponse
	resp[1] = 0
	resp[2] = 48
	copy(resp[3:35], respE.Public[:])
	serverSess.mixHash(respE.Public[:])

	if _, err := serverSess.mixDH(&respE.Private, &re); err != nil {
		t.Fatal(err)
	}
	cResp, err := serverSess.mixDH(&respE.Private, &clientPub)
	if err != nil {
		t.Fatal(err)
	}
	cResp.Seal(resp[35:35], zeroNonce[:], nil, serverSess.h[:])
	serverSess.mixHash(resp[35:51])

	// Client consumes server response
	if err := clientSess.ProcessHandshakeResponse(resp); err != nil {
		t.Fatalf("client process response: %v", err)
	}

	// Verify encryption and decryption
	plaintext := []byte("hello tailscale control plane")
	record, err := clientSess.EncryptRecord(plaintext)
	if err != nil {
		t.Fatal(err)
	}
	if record[0] != msgTypeRecord {
		t.Fatalf("expected msgTypeRecord 4, got %d", record[0])
	}

	// Setup server session keys
	serverSess.rxCipher = clientSess.txCipher
	serverSess.txCipher = clientSess.rxCipher
	serverSess.handshakeDone = true

	decrypted, err := serverSess.rxCipher.Open(nil, zeroNonce[:], record[recordHeaderLen:], nil)
	if err != nil {
		t.Fatalf("server decrypt: %v", err)
	}
	if !bytes.Equal(decrypted, plaintext) {
		t.Fatalf("plaintext mismatch: got %q, want %q", decrypted, plaintext)
	}
}
