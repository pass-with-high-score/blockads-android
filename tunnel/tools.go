//go:build tools

package tunnel

import (
	_ "golang.org/x/mobile/bind"
	_ "golang.org/x/mobile/cmd/gobind"
	_ "golang.org/x/mobile/cmd/gomobile"
	_ "github.com/sagernet/gomobile/bind"
	_ "github.com/sagernet/gomobile/cmd/gomobile"
)
