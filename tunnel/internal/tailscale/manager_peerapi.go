package tailscale

// SetTaildropReceiver registers the callback for incoming Taildrop files.
func (m *Manager) SetTaildropReceiver(rcv TaildropReceiver) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.taildropReceiver = rcv
	if m.peerAPIServer != nil {
		m.peerAPIServer.SetReceiver(rcv)
	}
}

// SetTaildropTempDir sets the directory where incoming files are temporarily staged.
func (m *Manager) SetTaildropTempDir(dir string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.taildropDir = dir
	if m.peerAPIServer != nil {
		m.peerAPIServer.SetTempDir(dir)
	}
}

// PeerAPIPort returns the active port of the PeerAPI server.
func (m *Manager) PeerAPIPort() uint16 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.peerAPIServer != nil {
		return m.peerAPIServer.Port()
	}
	return 0
}

// startPeerAPILocked starts the PeerAPI server on the DataPlane if not already running.
func (m *Manager) startPeerAPILocked() {
	if m.dataPlane == nil {
		return
	}
	if m.peerAPIServer == nil {
		m.peerAPIServer = NewPeerAPIServer(DefaultPeerAPIPort, m.taildropDir, m.taildropReceiver)
	}
	if err := m.peerAPIServer.Start(m.dataPlane); err != nil {
		logf("[Tailscale/PeerAPI] start failed: %v", err)
	}
}

// stopPeerAPILocked stops the PeerAPI server.
func (m *Manager) stopPeerAPILocked() {
	if m.peerAPIServer != nil {
		m.peerAPIServer.Stop()
	}
}
