package tunnel

import (
	"bufio"
	"bytes"
	"flag"
	"fmt"
	"go/importer"
	"go/token"
	"go/types"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

var updateAPI = flag.Bool("update", false, "rewrite testdata/api.golden from the current exported API")

const apiGolden = "testdata/api.golden"

// TestExportedAPI pins the surface gomobile binds into tunnel.aar, so a change to what Kotlin can call shows up
// as a golden diff. Aliased internal types are expanded to their exported fields and methods.
// Regenerate with: go test -run TestExportedAPI -update .
func TestExportedAPI(t *testing.T) {
	got := describeAPI(t)
	if *updateAPI {
		if err := os.MkdirAll(filepath.Dir(apiGolden), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(apiGolden, []byte(got), 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	want, err := os.ReadFile(apiGolden)
	if err != nil {
		t.Fatalf("%v (generate it with -update)", err)
	}
	if got != string(want) {
		t.Errorf("exported API differs from %s; if intended, rerun with -update and review the diff.\n%s",
			apiGolden, lineDiff(string(want), got))
	}
}

func describeAPI(t *testing.T) string {
	t.Helper()
	pkg := loadTypes(t)
	modPrefix := pkg.Path() + "/"
	qualify := func(p *types.Package) string {
		if p == pkg {
			return ""
		}
		return strings.TrimPrefix(p.Path(), modPrefix)
	}

	var lines []string
	add := func(format string, args ...any) { lines = append(lines, fmt.Sprintf(format, args...)) }
	scope := pkg.Scope()
	for _, name := range scope.Names() {
		obj := scope.Lookup(name)
		if !obj.Exported() {
			continue
		}
		switch o := obj.(type) {
		case *types.Const:
			add("const %s %s = %s", name, types.TypeString(o.Type(), qualify), o.Val().ExactString())
		case *types.Var:
			add("var %s %s", name, types.TypeString(o.Type(), qualify))
		case *types.Func:
			add("func %s%s", name, strings.TrimPrefix(types.TypeString(o.Type(), qualify), "func"))
		case *types.TypeName:
			describeType(o, qualify, add)
		}
	}
	return strings.Join(lines, "\n") + "\n"
}

func describeType(o *types.TypeName, qualify types.Qualifier, add func(string, ...any)) {
	name := o.Name()
	if o.IsAlias() {
		add("type %s = %s", name, types.TypeString(types.Unalias(o.Type()), qualify))
	} else {
		add("type %s %s", name, kindOf(o.Type().Underlying()))
	}
	named, ok := types.Unalias(o.Type()).(*types.Named)
	if !ok {
		return
	}
	switch u := named.Underlying().(type) {
	case *types.Struct:
		for i := 0; i < u.NumFields(); i++ {
			if f := u.Field(i); f.Exported() {
				add("\tfield %s.%s %s", name, f.Name(), types.TypeString(f.Type(), qualify))
			}
		}
	case *types.Interface:
		for i := 0; i < u.NumMethods(); i++ {
			if m := u.Method(i); m.Exported() {
				add("\tmethod %s.%s%s", name, m.Name(), strings.TrimPrefix(types.TypeString(m.Type(), qualify), "func"))
			}
		}
		return
	}
	mset := types.NewMethodSet(types.NewPointer(named))
	for i := 0; i < mset.Len(); i++ {
		m := mset.At(i).Obj()
		if !m.Exported() {
			continue
		}
		add("\tmethod %s.%s%s", name, m.Name(), strings.TrimPrefix(types.TypeString(m.Type(), qualify), "func"))
	}
}

func kindOf(t types.Type) string {
	switch t.(type) {
	case *types.Struct:
		return "struct"
	case *types.Interface:
		return "interface"
	default:
		return t.String()
	}
}

// loadTypes type-checks this package through export data from `go list -export`, which uses only the standard
// library and the build cache.
func loadTypes(t *testing.T) *types.Package {
	t.Helper()
	out, err := exec.Command("go", "list", "-export", "-deps", "-f", "{{.ImportPath}}\t{{.Export}}", ".").Output()
	if err != nil {
		if ee, ok := err.(*exec.ExitError); ok {
			t.Fatalf("go list: %v\n%s", err, ee.Stderr)
		}
		t.Fatalf("go list: %v", err)
	}
	exports := map[string]string{}
	var self string
	sc := bufio.NewScanner(bytes.NewReader(out))
	for sc.Scan() {
		path, file, _ := strings.Cut(sc.Text(), "\t")
		exports[path] = file
		self = path // -deps lists the named package last
	}
	lookup := func(path string) (io.ReadCloser, error) {
		file, ok := exports[path]
		if !ok || file == "" {
			return nil, fmt.Errorf("no export data for %q", path)
		}
		return os.Open(file)
	}
	pkg, err := importer.ForCompiler(token.NewFileSet(), "gc", lookup).Import(self)
	if err != nil {
		t.Fatal(err)
	}
	return pkg
}

// lineDiff lists lines only in want (-) or only in got (+); enough to spot a changed signature.
func lineDiff(want, got string) string {
	count := func(s string) map[string]int {
		m := map[string]int{}
		for _, l := range strings.Split(s, "\n") {
			m[l]++
		}
		return m
	}
	w, g := count(want), count(got)
	var out []string
	for l, n := range w {
		if g[l] < n {
			out = append(out, "- "+l)
		}
	}
	for l, n := range g {
		if w[l] < n {
			out = append(out, "+ "+l)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i][2:] < out[j][2:] })
	return strings.Join(out, "\n")
}
