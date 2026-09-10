package scriptlet

import (
	"encoding/json"
	"strings"
)

// RuntimeJS is the runtime injected into every MITM'd HTML
// page via /scriptlets.js. It exposes window.__ba.invoke(name, args)
// which per-host invocation blocks call. Keep it small — every byte
// here loads on every page.
const RuntimeJS = `(function(){"use strict";
if(window.__ba)return;
var ns=Object.create(null);

function getOwner(path){
  var parts=path.split(".");var owner=window;
  for(var i=0;i<parts.length-1;i++){
    if(owner==null)return null;
    owner=owner[parts[i]];
  }
  return owner==null?null:{owner:owner,prop:parts[parts.length-1]};
}

ns["set-constant"]=function(path,value){
  var o=getOwner(path);if(!o)return;
  var v=value;
  if(value==="true")v=true;else if(value==="false")v=false;
  else if(value==="null")v=null;else if(value==="undefined")v=undefined;
  else if(value==="noopFunc")v=function(){};
  else if(value==="trueFunc")v=function(){return true;};
  else if(value==="falseFunc")v=function(){return false;};
  else if(/^-?\d+(\.\d+)?$/.test(value))v=parseFloat(value);
  try{Object.defineProperty(o.owner,o.prop,{get:function(){return v;},set:function(){},configurable:false});}catch(e){}
};

ns["abort-on-property-read"]=function(path){
  var o=getOwner(path);if(!o)return;
  var msg="Aborted by BlockAds: read "+path;
  try{Object.defineProperty(o.owner,o.prop,{get:function(){throw new ReferenceError(msg);},configurable:false});}catch(e){}
};

ns["abort-on-property-write"]=function(path){
  var o=getOwner(path);if(!o)return;
  var msg="Aborted by BlockAds: write "+path;
  try{Object.defineProperty(o.owner,o.prop,{set:function(){throw new ReferenceError(msg);},configurable:false});}catch(e){}
};

ns["prevent-fetch"]=function(pattern){
  var rx=pattern?new RegExp(pattern.replace(/\*/g,".*")):/.*/;
  var orig=window.fetch;if(!orig)return;
  window.fetch=function(input){
    var url=typeof input==="string"?input:(input&&input.url)||"";
    if(rx.test(url))return Promise.resolve(new Response("",{status:200}));
    return orig.apply(this,arguments);
  };
};

ns["prevent-xhr"]=function(pattern){
  var rx=pattern?new RegExp(pattern.replace(/\*/g,".*")):/.*/;
  var X=window.XMLHttpRequest;if(!X||!X.prototype||!X.prototype.open)return;
  var origOpen=X.prototype.open;
  X.prototype.open=function(method,url){
    if(rx.test(String(url))){this.__baBlocked=true;return origOpen.call(this,method,"data:text/plain,");}
    return origOpen.apply(this,arguments);
  };
};

ns["noeval"]=function(){
  try{Object.defineProperty(window,"eval",{value:function(){throw new Error("eval blocked by BlockAds");},configurable:false});}catch(e){}
};

window.__ba={
  loaded:true,
  version:"S-B",
  invoke:function(name,args){
    var fn=ns[name];if(!fn){console.debug("[BlockAds] unknown scriptlet:",name);return;}
    try{fn.apply(null,args||[]);}catch(e){console.debug("[BlockAds] scriptlet failed:",name,e);}
  }
};
})();
`

// Store holds parsed rules. Built once per filter-list update
// from Kotlin; consulted on every per-host scriptlet request.
type Store struct {
	All    []Rule
	ByHost map[string][]Rule
}

// Matches returns the rules that apply to the given hostname.
func (s *Store) Matches(host string) []Rule {
	host = strings.ToLower(host)
	var out []Rule
	out = append(out, s.All...)
	for d, rules := range s.ByHost {
		if host == d || strings.HasSuffix(host, "."+d) {
			for _, r := range rules {
				if !ruleNegatedFor(r, host) {
					out = append(out, r)
				}
			}
		}
	}
	return out
}

func (s *Store) buildHostInvocations(host string) string {
	return s.BuildHostInvocations(host)
}

func ruleNegatedFor(r Rule, host string) bool {
	for _, d := range r.Domains {
		if !strings.HasPrefix(d, "~") {
			continue
		}
		bare := strings.TrimPrefix(d, "~")
		if host == bare || strings.HasSuffix(host, "."+bare) {
			return true
		}
	}
	return false
}

// BuildHostInvocations returns the JS string that, when executed,
// invokes every applicable scriptlet against window.__ba.invoke.
func (s *Store) BuildHostInvocations(host string) string {
	rules := s.Matches(host)
	if len(rules) == 0 {
		return ""
	}
	var b strings.Builder
	b.WriteString("(function(){\n")
	b.WriteString("var run=function(){\n")
	for _, r := range rules {
		argsJSON, _ := json.Marshal(r.Args)
		nameJSON, _ := json.Marshal(r.Name)
		b.WriteString("  window.__ba.invoke(")
		b.Write(nameJSON)
		b.WriteString(",")
		b.Write(argsJSON)
		b.WriteString(");\n")
	}
	b.WriteString("};\n")
	b.WriteString("if(window.__ba&&window.__ba.loaded){run();}else{\n")
	b.WriteString("  var t=setInterval(function(){if(window.__ba&&window.__ba.loaded){clearInterval(t);run();}},10);\n")
	b.WriteString("  setTimeout(function(){clearInterval(t);},5000);\n")
	b.WriteString("}\n")
	b.WriteString("})();\n")
	return b.String()
}
