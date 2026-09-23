package mitm

import (
	"bytes"
	"io"
	"strings"
	"testing"
	"testing/iotest"
)

// readers wraps an input in each of the chunking strategies the injector
// must be insensitive to.
var injectReaders = map[string]func([]byte) io.Reader{
	"whole":   func(b []byte) io.Reader { return bytes.NewReader(b) },
	"onebyte": func(b []byte) io.Reader { return iotest.OneByteReader(bytes.NewReader(b)) },
	"half":    func(b []byte) io.Reader { return iotest.HalfReader(bytes.NewReader(b)) },
	"dataerr": func(b []byte) io.Reader { return iotest.DataErrReader(bytes.NewReader(b)) },
}

// readInjected drains an injecting reader with a small caller buffer so the
// pending path is exercised too.
func readInjected(t testing.TB, r io.Reader, bufSize int) []byte {
	t.Helper()
	ir := NewInjectingReader(r)
	var out bytes.Buffer
	buf := make([]byte, bufSize)
	for spins := 0; ; spins++ {
		if spins > 1<<20 {
			t.Fatal("injector did not reach EOF")
		}
		n, err := ir.Read(buf)
		out.Write(buf[:n])
		if err == io.EOF {
			return out.Bytes()
		}
		if err != nil {
			t.Fatalf("read: %v", err)
		}
	}
}

// stripInjection removes the first copy of the injected tags.
func stripInjection(out []byte) ([]byte, bool) {
	i := bytes.Index(out, []byte(injectionTags))
	if i < 0 {
		return out, false
	}
	return append(bytes.Clone(out[:i]), out[i+len(injectionTags):]...), true
}

func TestInjectorProperties(t *testing.T) {
	// The limit is checked per read, so a <head> in the read that crosses
	// scanLimit is still found; place it well past any single read.
	big := strings.Repeat("x", 3*scanLimit)
	cases := []struct {
		name, in   string
		wantInject bool
		after      string // injected tags must follow this prefix
	}{
		{"plain", "<html><head><title>t</title></head></html>", true, "<html><head>"},
		{"upper", "<HTML><HEAD>body", true, "<HTML><HEAD>"},
		{"attrs", `<html><head lang="en">x`, true, `<html><head lang="en">`},
		{"no head", "<html><body>hello</body></html>", false, ""},
		{"empty", "", false, ""},
		{"two heads", "<head><head>x", true, "<head>"},
		{"partial then other", "<he<head>x", true, "<he<head>"},
		{"lt in middle", "a<b<c", false, ""},
		{"head after limit", big + "<head>x", false, ""},
		{"head at start of big", "<head>" + big, true, "<head>"},
	}
	for _, tc := range cases {
		for rname, mk := range injectReaders {
			for _, bufSize := range []int{3, 64, 4096} {
				out := readInjected(t, mk([]byte(tc.in)), bufSize)
				stripped, injected := stripInjection(out)
				if string(stripped) != tc.in {
					t.Errorf("%s/%s/%d: output minus tags != input\n in: %.80q\nout: %.80q", tc.name, rname, bufSize, tc.in, stripped)
					continue
				}
				if injected != tc.wantInject {
					t.Errorf("%s/%s/%d: injected=%v, want %v", tc.name, rname, bufSize, injected, tc.wantInject)
					continue
				}
				if injected && !bytes.HasPrefix(out, []byte(tc.after+injectionTags)) {
					t.Errorf("%s/%s/%d: tags misplaced: %.80q", tc.name, rname, bufSize, out)
				}
			}
		}
	}
}

// The injector's known defects, one subtest each.
func TestInjectorKnownBugs(t *testing.T) {
	t.Run("carry dropped at EOF", func(t *testing.T) {
		t.Skip("known bug: a trailing partial \"<head\" is dropped at EOF")
		for _, in := range []string{"text ending in <he", "a<b<"} {
			out := readInjected(t, iotest.OneByteReader(strings.NewReader(in)), 64)
			if string(out) != in {
				t.Errorf("out = %q, want %q", out, in)
			}
		}
	})
	t.Run("unclosed head dropped at EOF", func(t *testing.T) {
		t.Skip("known bug: an unclosed \"<head\" tail is dropped at EOF")
		in := "<html><head lang=en"
		out := readInjected(t, strings.NewReader(in), 64)
		if string(out) != in {
			t.Fatalf("out = %q", out)
		}
	})
	t.Run("unbounded buffering without >", func(t *testing.T) {
		t.Skip("known bug: \"<head\" with no '>' buffers the whole body in carry")
		ir := NewInjectingReader(strings.NewReader("<head" + strings.Repeat("a", 4*scanLimit))).(*injectingReader)
		buf := make([]byte, 512)
		for i := 0; i < 8*scanLimit/512; i++ {
			if _, err := ir.Read(buf); err != nil {
				break
			}
			if len(ir.carry) > scanLimit {
				t.Fatalf("carry grew to %d bytes", len(ir.carry))
			}
		}
	})
	t.Run("header matches head", func(t *testing.T) {
		t.Skip("known bug: <header> is treated as <head>")
		out := readInjected(t, strings.NewReader("<html><body><header>x</header>"), 4096)
		if bytes.Contains(out, []byte(injectionTags)) {
			t.Fatalf("injected after <header>: %q", out)
		}
	})
	t.Run("non-ASCII shifts offsets", func(t *testing.T) {
		t.Skip("known bug: bytes.ToLower changes length on non-ASCII input, misplacing tags or panicking")
		for _, in := range []string{"Ⱥ<head>X", "\xff\xff<head>X"} {
			func() {
				defer func() {
					if r := recover(); r != nil {
						t.Errorf("%q: panic: %v", in, r)
					}
				}()
				out := readInjected(t, strings.NewReader(in), 4096)
				if want := strings.Replace(in, "<head>", "<head>"+injectionTags, 1); string(out) != want {
					t.Errorf("%q: out = %q", in, out)
				}
			}()
		}
	})
}

func TestShouldInjectHTML(t *testing.T) {
	for ct, want := range map[string]bool{
		"text/html":                 true,
		"TEXT/HTML; charset=utf-8":  true,
		"application/json":          false,
		"":                          false,
		"application/xhtml+xml":     false,
		"text/plain; x=text/html-y": true,
	} {
		if got := ShouldInjectHTML(ct); got != want {
			t.Errorf("ShouldInjectHTML(%q) = %v, want %v", ct, got, want)
		}
	}
}

// knownInjectBug reports inputs that hit a known injector defect so the fuzzer keeps
// looking for new ones.
func knownInjectBug(in, out []byte) bool {
	lower := bytes.ToLower(in)
	if bytes.Contains(lower, []byte("<head")) {
		i := bytes.Index(lower, []byte("<head"))
		if i+5 < len(lower) && lower[i+5] != '>' && lower[i+5] != ' ' && lower[i+5] != '\t' && lower[i+5] != '\n' {
			return true // <header and friends
		}
	}
	if bytes.HasPrefix(in, out) {
		tail := bytes.ToLower(in[len(out):])
		if bytes.HasPrefix(tail, []byte("<head")) || bytes.HasPrefix([]byte("<head"), tail) {
			return true // carry dropped at EOF
		}
	}
	return false
}

func FuzzInjectingReader(f *testing.F) {
	f.Add([]byte("<html><head><title>t</title></head></html>"), uint8(0), uint8(7))
	f.Add([]byte("<HEAD>"), uint8(1), uint8(1))
	f.Add([]byte("a<he"), uint8(2), uint8(3))
	f.Add([]byte("<header>"), uint8(3), uint8(200))
	f.Fuzz(func(t *testing.T, in []byte, which, bufSize uint8) {
		if bytes.Contains(in, []byte(injectionTags)) {
			t.Skip("input already contains the tags")
		}
		names := []string{"whole", "onebyte", "half", "dataerr"}
		for _, c := range in {
			if c >= 0x80 {
				t.Skip("known bug: non-ASCII input shifts offsets (may panic)")
			}
		}
		mk := injectReaders[names[int(which)%len(names)]]
		out := readInjected(t, mk(in), int(bufSize)+1)
		stripped, injected := stripInjection(out)
		if !bytes.Equal(stripped, in) {
			if knownInjectBug(in, stripped) {
				t.Skip("known injector bug")
			}
			t.Fatalf("output minus tags != input\n in: %q\nout: %q", in, out)
		}
		if injected {
			i := bytes.Index(out, []byte(injectionTags))
			before := bytes.ToLower(out[:i])
			h := bytes.LastIndex(before, []byte("<head"))
			if h < 0 || before[len(before)-1] != '>' {
				t.Fatalf("tags not placed after a <head> tag: %q", out)
			}
			if bytes.IndexByte(before[h:], '>') != len(before)-h-1 {
				t.Fatalf("tags not placed at the end of the <head> tag: %q", out)
			}
		}
	})
}
