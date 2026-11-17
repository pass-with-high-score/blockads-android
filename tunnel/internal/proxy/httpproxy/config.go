package httpproxy

import (
	"encoding/json"
	"fmt"
	"strings"
)

// Config represents an HTTP or HTTPS (TLS) CONNECT proxy configuration.
type Config struct {
	Server        string `json:"server"`
	Port          int    `json:"port"`
	Username      string `json:"username,omitempty"`
	Password      string `json:"password,omitempty"`
	TLS           bool   `json:"tls,omitempty"`
	SNI           string `json:"sni,omitempty"`
	AllowInsecure bool   `json:"allowInsecure,omitempty"`
	Tag           string `json:"tag,omitempty"`
}

// ToJSON serializes the config to a JSON string.
func (c *Config) ToJSON() string {
	b, _ := json.Marshal(c)
	return string(b)
}

// FromJSON parses a JSON string into a Config.
func FromJSON(data string) (*Config, error) {
	type rawConfig struct {
		Server        string `json:"server"`
		Port          int    `json:"port"`
		Username      string `json:"username,omitempty"`
		User          string `json:"user,omitempty"`
		Password      string `json:"password,omitempty"`
		Pass          string `json:"pass,omitempty"`
		TLS           bool   `json:"tls,omitempty"`
		Security      string `json:"security,omitempty"`
		Protocol      string `json:"protocol,omitempty"`
		SNI           string `json:"sni,omitempty"`
		AllowInsecure bool   `json:"allowInsecure,omitempty"`
		Tag           string `json:"tag,omitempty"`
	}
	var raw rawConfig
	if err := json.Unmarshal([]byte(data), &raw); err != nil {
		return nil, err
	}
	if raw.Server == "" || raw.Port <= 0 {
		return nil, fmt.Errorf("invalid http proxy config: missing server or port")
	}

	username := raw.Username
	if username == "" {
		username = raw.User
	}
	password := raw.Password
	if password == "" {
		password = raw.Pass
	}

	useTLS := raw.TLS ||
		strings.EqualFold(raw.Security, "tls") ||
		strings.EqualFold(raw.Protocol, "https")
	sni := raw.SNI
	if sni == "" {
		sni = raw.Server
	}

	return &Config{
		Server:        raw.Server,
		Port:          raw.Port,
		Username:      username,
		Password:      password,
		TLS:           useTLS,
		SNI:           sni,
		AllowInsecure: raw.AllowInsecure,
		Tag:           raw.Tag,
	}, nil
}
