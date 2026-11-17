package ssh

import (
	"encoding/json"
	"fmt"
)

// Config represents an SSH tunnel outbound proxy configuration.
type Config struct {
	Server     string `json:"server"`
	Port       int    `json:"port"`
	Username   string `json:"username"`
	Password   string `json:"password,omitempty"`
	PrivateKey string `json:"privateKey,omitempty"`
	Passphrase string `json:"passphrase,omitempty"`
	Tag        string `json:"tag,omitempty"`
}

// ToJSON serializes the config to a JSON string.
func (c *Config) ToJSON() string {
	b, _ := json.Marshal(c)
	return string(b)
}

// FromJSON parses a JSON string into a Config.
func FromJSON(data string) (*Config, error) {
	type rawConfig struct {
		Server     string `json:"server"`
		Port       int    `json:"port"`
		Username   string `json:"username,omitempty"`
		User       string `json:"user,omitempty"`
		Password   string `json:"password,omitempty"`
		Pass       string `json:"pass,omitempty"`
		PrivateKey string `json:"privateKey,omitempty"`
		Passphrase string `json:"passphrase,omitempty"`
		Tag        string `json:"tag,omitempty"`
	}
	var raw rawConfig
	if err := json.Unmarshal([]byte(data), &raw); err != nil {
		return nil, err
	}

	username := raw.Username
	if username == "" {
		username = raw.User
	}
	password := raw.Password
	if password == "" {
		password = raw.Pass
	}
	port := raw.Port
	if port <= 0 {
		port = 22
	}

	if raw.Server == "" || username == "" {
		return nil, fmt.Errorf("invalid ssh config: missing server or username")
	}
	if password == "" && raw.PrivateKey == "" {
		return nil, fmt.Errorf("invalid ssh config: missing password or privateKey")
	}

	return &Config{
		Server:     raw.Server,
		Port:       port,
		Username:   username,
		Password:   password,
		PrivateKey: raw.PrivateKey,
		Passphrase: raw.Passphrase,
		Tag:        raw.Tag,
	}, nil
}
