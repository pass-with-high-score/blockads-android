package socks

import (
	"encoding/json"
	"fmt"
	"strings"
)

// Config represents a SOCKS4/4a/5 outbound proxy configuration.
type Config struct {
	Server   string `json:"server"`
	Port     int    `json:"port"`
	Username string `json:"username,omitempty"`
	Password string `json:"password,omitempty"`
	Version  string `json:"version,omitempty"` // "socks5" (default), "socks4", "socks4a"
	Tag      string `json:"tag,omitempty"`
}

// ToJSON serializes the config to a JSON string.
func (c *Config) ToJSON() string {
	b, _ := json.Marshal(c)
	return string(b)
}

// FromJSON parses a JSON string into a Config.
func FromJSON(data string) (*Config, error) {
	type rawConfig struct {
		Server   string `json:"server"`
		Port     int    `json:"port"`
		Username string `json:"username,omitempty"`
		User     string `json:"user,omitempty"`
		Password string `json:"password,omitempty"`
		Pass     string `json:"pass,omitempty"`
		Version  string `json:"version,omitempty"`
		Protocol string `json:"protocol,omitempty"`
		Tag      string `json:"tag,omitempty"`
	}
	var raw rawConfig
	if err := json.Unmarshal([]byte(data), &raw); err != nil {
		return nil, err
	}
	if raw.Server == "" || raw.Port <= 0 {
		return nil, fmt.Errorf("invalid socks config: missing server or port")
	}

	username := raw.Username
	if username == "" {
		username = raw.User
	}
	password := raw.Password
	if password == "" {
		password = raw.Pass
	}

	version := strings.ToLower(strings.TrimSpace(raw.Version))
	if version == "" {
		p := strings.ToLower(strings.TrimSpace(raw.Protocol))
		if p == "socks4" || p == "socks4a" {
			version = p
		} else {
			version = "socks5"
		}
	}
	switch version {
	case "socks5", "5", "socks":
		version = "socks5"
	case "socks4", "4":
		version = "socks4"
	case "socks4a", "4a":
		version = "socks4a"
	default:
		return nil, fmt.Errorf("unsupported socks version: %s", version)
	}

	return &Config{
		Server:   raw.Server,
		Port:     raw.Port,
		Username: username,
		Password: password,
		Version:  version,
		Tag:      raw.Tag,
	}, nil
}
