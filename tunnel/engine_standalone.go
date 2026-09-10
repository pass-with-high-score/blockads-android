package tunnel

import (
	"fmt"
	"strings"
	"time"

	"github.com/miekg/dns"
)

func (e *Engine) standaloneBlock(w dns.ResponseWriter, r *dns.Msg, blockedBy, appName string, startTime time.Time) {
	m := new(dns.Msg)
	m.SetReply(r)

	switch e.responseType {
	case ResponseNXDomain:
		m.Rcode = dns.RcodeNameError
	case ResponseRefused:
		m.Rcode = dns.RcodeRefused
	default:
		m.Rcode = dns.RcodeSuccess
		if r.Question[0].Qtype == dns.TypeA {
			rr, _ := dns.NewRR(fmt.Sprintf("%s 300 IN A 0.0.0.0", r.Question[0].Name))
			m.Answer = append(m.Answer, rr)
		} else if r.Question[0].Qtype == dns.TypeAAAA {
			rr, _ := dns.NewRR(fmt.Sprintf("%s 300 IN AAAA ::", r.Question[0].Name))
			m.Answer = append(m.Answer, rr)
		}
	}

	_ = w.WriteMsg(m)
	e.totalQueries.Add(1)
	e.blockedQueries.Add(1)
	elapsed := time.Since(startTime).Milliseconds()
	e.notifyLog(strings.TrimSuffix(r.Question[0].Name, "."), true, r.Question[0].Qtype, elapsed, appName, "", blockedBy)
}

func (e *Engine) standaloneForward(w dns.ResponseWriter, r *dns.Msg, appName string, startTime time.Time) {
	raw, err := r.Pack()
	if err != nil {
		dns.HandleFailed(w, r)
		return
	}

	e.mu.Lock()
	resolver := e.resolver
	e.mu.Unlock()
	if resolver == nil {
		dns.HandleFailed(w, r)
		return
	}

	respRaw, err := resolver.Resolve(raw)
	if err != nil {
		logf("DNS resolve failed standalone %s: %v", r.Question[0].Name, err)
		dns.HandleFailed(w, r)
		e.totalQueries.Add(1)
		elapsed := time.Since(startTime).Milliseconds()
		e.notifyLog(strings.TrimSuffix(r.Question[0].Name, "."), false, r.Question[0].Qtype, elapsed, appName, "", "")
		return
	}

	var respMsg dns.Msg
	if err := respMsg.Unpack(respRaw); err != nil {
		dns.HandleFailed(w, r)
		return
	}

	if isUpstreamBlocked(respRaw) {
		e.totalQueries.Add(1)
		e.blockedQueries.Add(1)
		elapsed := time.Since(startTime).Milliseconds()
		e.notifyLog(strings.TrimSuffix(r.Question[0].Name, "."), true, r.Question[0].Qtype, elapsed, appName, "", "upstream_dns")
	} else {
		e.totalQueries.Add(1)
		elapsed := time.Since(startTime).Milliseconds()
		e.notifyLog(strings.TrimSuffix(r.Question[0].Name, "."), false, r.Question[0].Qtype, elapsed, appName, "", "")
	}

	respMsg.Id = r.Id
	_ = w.WriteMsg(&respMsg)
}

func (e *Engine) standaloneRedirect(w dns.ResponseWriter, r *dns.Msg, redirectDomain, appName string, startTime time.Time) bool {
	ip := e.safeSearch.GetCachedIP(redirectDomain)
	if ip == nil {
		e.mu.Lock()
		resolver := e.resolver
		e.mu.Unlock()
		if resolver == nil {
			return false
		}

		var err error
		ip, err = resolver.ResolveARecord(redirectDomain, e.primaryDNS)
		if err != nil {
			return false
		}
		e.safeSearch.CacheIP(redirectDomain, ip)
	}

	m := new(dns.Msg)
	m.SetReply(r)
	m.Rcode = dns.RcodeSuccess

	if r.Question[0].Qtype == dns.TypeA {
		rr, _ := dns.NewRR(fmt.Sprintf("%s 300 IN A %s", r.Question[0].Name, ip.String()))
		m.Answer = append(m.Answer, rr)
	}

	_ = w.WriteMsg(m)
	e.totalQueries.Add(1)
	elapsed := time.Since(startTime).Milliseconds()
	e.notifyLog(strings.TrimSuffix(r.Question[0].Name, "."), false, r.Question[0].Qtype, elapsed, appName, ip.String(), "")
	return true
}

// StartStandalone starts the engine in DNS-only standalone mode on 127.0.0.1:port
// It bypasses TUN and directly serves incoming UDP/TCP DNS queries.
func (e *Engine) StartStandalone(port int) error {
	e.mu.Lock()

	var oldUdp, oldTcp, oldUdp6, oldTcp6 *dns.Server
	var oldResolver *Resolver

	if e.running {
		oldUdp = e.standaloneUdp
		e.standaloneUdp = nil
		oldTcp = e.standaloneTcp
		e.standaloneTcp = nil
		oldUdp6 = e.standaloneUdp6
		e.standaloneUdp6 = nil
		oldTcp6 = e.standaloneTcp6
		e.standaloneTcp6 = nil
		oldResolver = e.resolver
		e.resolver = nil
		e.running = false
	}

	e.running = true
	e.totalQueries.Store(0)
	e.blockedQueries.Store(0)

	e.resolver = NewResolver(nil)
	e.resolver.Configure(ParseProtocol(e.protocol), e.primaryDNS, e.fallbackDNS, e.dohURL)
	e.mu.Unlock()

	if oldUdp != nil {
		oldUdp.Shutdown()
	}
	if oldTcp != nil {
		oldTcp.Shutdown()
	}
	if oldUdp6 != nil {
		oldUdp6.Shutdown()
	}
	if oldTcp6 != nil {
		oldTcp6.Shutdown()
	}
	if oldResolver != nil {
		oldResolver.Shutdown()
	}

	addr4 := fmt.Sprintf("127.0.0.1:%d", port)
	addr6 := fmt.Sprintf("[::1]:%d", port)

	udpServer := &dns.Server{Addr: addr4, Net: "udp", Handler: dns.HandlerFunc(e.ServeDNS)}
	tcpServer := &dns.Server{Addr: addr4, Net: "tcp", Handler: dns.HandlerFunc(e.ServeDNS)}

	udpServer6 := &dns.Server{Addr: addr6, Net: "udp6", Handler: dns.HandlerFunc(e.ServeDNS)}
	tcpServer6 := &dns.Server{Addr: addr6, Net: "tcp6", Handler: dns.HandlerFunc(e.ServeDNS)}

	e.mu.Lock()
	e.standaloneUdp = udpServer
	e.standaloneTcp = tcpServer
	e.standaloneUdp6 = udpServer6
	e.standaloneTcp6 = tcpServer6
	e.mu.Unlock()

	errChan := make(chan error, 4)

	go func() {
		if err := udpServer.ListenAndServe(); err != nil {
			logf("Standalone UDP IPv4 stopped: %v", err)
			errChan <- err
		}
	}()
	go func() {
		if err := tcpServer.ListenAndServe(); err != nil {
			logf("Standalone TCP IPv4 stopped: %v", err)
			errChan <- err
		}
	}()
	go func() {
		if err := udpServer6.ListenAndServe(); err != nil {
			logf("Standalone UDP IPv6 stopped: %v", err)
		}
	}()
	go func() {
		if err := tcpServer6.ListenAndServe(); err != nil {
			logf("Standalone TCP IPv6 stopped: %v", err)
		}
	}()

	time.Sleep(100 * time.Millisecond)

	select {
	case err := <-errChan:
		return fmt.Errorf("IPv4 Server failed to start: %v", err)
	default:
	}

	logf("Engine started in STANDALONE mode on %s and %s", addr4, addr6)
	return nil
}
