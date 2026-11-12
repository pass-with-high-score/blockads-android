package app.pwhs.blockadstv.companion

object CompanionHtmlPage {

    fun render(tvUrl: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BlockAds TV Companion</title>
<style>
  :root{--bg:#0d1117;--card:#161b22;--border:#30363d;--text:#e6edf3;--muted:#8b949e;--accent:#39ff14;--err:#f85149}
  *{box-sizing:border-box}
  body{margin:0;padding:16px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--text);line-height:1.45}
  .wrap{max-width:600px;margin:0 auto}
  header{margin-bottom:18px}
  h1{font-size:1.35rem;margin:0 0 4px;color:var(--accent)}
  .sub{font-size:.85rem;color:var(--muted);word-break:break-all}
  .card{background:var(--card);border:1px solid var(--border);border-radius:12px;padding:16px;margin-bottom:14px}
  h2{font-size:1.05rem;margin:0 0 10px}
  label{display:block;font-size:.8rem;color:var(--muted);margin-bottom:4px}
  input[type=text],input[type=url],textarea{width:100%;background:#0d1117;color:var(--text);border:1px solid var(--border);border-radius:8px;padding:10px;font-size:.9rem;margin-bottom:10px;font-family:inherit}
  textarea{min-height:110px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.82rem}
  input[type=file]{width:100%;margin-bottom:10px;color:var(--muted);font-size:.85rem}
  .row{display:flex;gap:8px;flex-wrap:wrap}
  button,.btn{background:var(--accent);color:#000;border:none;border-radius:8px;padding:10px 16px;font-weight:700;font-size:.9rem;cursor:pointer;text-decoration:none;display:inline-block}
  .btn-sec{background:#21262d;color:var(--text);border:1px solid var(--border)}
  #toast{position:sticky;top:10px;padding:12px 14px;border-radius:10px;margin-bottom:14px;display:none;font-size:.9rem;font-weight:600}
  .ok{background:#122d1a;border:1px solid var(--accent);color:var(--accent)}
  .bad{background:#301417;border:1px solid var(--err);color:#ffb4ae}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <h1>BlockAds TV Companion</h1>
    <div class="sub">Connected to $tvUrl</div>
  </header>
  <div id="toast"></div>

  <div class="card">
    <h2>1. Push WireGuard Config</h2>
    <label for="wgFile">Choose .conf file (optional)</label>
    <input id="wgFile" type="file" accept=".conf,.txt" onchange="loadFile(this,'wgConfig','wgName')">
    <label for="wgName">Profile Name (optional)</label>
    <input id="wgName" type="text" placeholder="Home VPN">
    <label for="wgConfig">Paste .conf, wireguard://, vpn:// or awg://</label>
    <textarea id="wgConfig" placeholder="[Interface]&#10;PrivateKey = ..."></textarea>
    <button onclick="pushWireGuard()">Push WireGuard</button>
  </div>

  <div class="card">
    <h2>2. Push Proxy Node or Subscription</h2>
    <label for="proxyLink">Proxy Link (vless://, vmess://, trojan://, ss://, hy2://, tuic://, masque://)</label>
    <input id="proxyLink" type="text" placeholder="vless://...">
    <button onclick="pushProxy()">Push Proxy Node</button>
    <hr style="border:none;border-top:1px solid var(--border);margin:14px 0">
    <label for="subName">Subscription Name (optional)</label>
    <input id="subName" type="text" placeholder="My Subscription">
    <label for="subUrl">Subscription URL (https://...)</label>
    <input id="subUrl" type="url" placeholder="https://example.com/sub">
    <button onclick="pushSubscription()">Import Subscription</button>
  </div>

  <div class="card">
    <h2>3. Settings Backup</h2>
    <label for="bkFile">Upload Backup JSON</label>
    <input id="bkFile" type="file" accept=".json,application/json" onchange="loadFile(this,'bkJson')">
    <label for="bkJson">Or paste Settings Backup JSON</label>
    <textarea id="bkJson" placeholder='{"version":1,...}'></textarea>
    <div class="row">
      <button onclick="pushBackup()">Restore Backup</button>
      <a class="btn btn-sec" href="/api/backup" download="blockadstv-backup.json">Download TV Backup</a>
    </div>
  </div>
</div>
<script>
function showToast(msg, ok){
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.className = ok ? 'ok' : 'bad';
  t.style.display = 'block';
  window.scrollTo({top:0,behavior:'smooth'});
}
function loadFile(input, targetId, nameId){
  const f = input.files && input.files[0];
  if(!f) return;
  if(nameId && !document.getElementById(nameId).value){
    document.getElementById(nameId).value = f.name.replace(/\.[^.]+$/,'');
  }
  const r = new FileReader();
  r.onload = () => { document.getElementById(targetId).value = r.result; };
  r.readAsText(f);
}
async function postJson(path, payload){
  try{
    const res = await fetch(path, {
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    showToast(data.message || (res.ok ? 'Done' : 'Error'), res.ok);
  }catch(e){
    showToast('Request failed: ' + e.message, false);
  }
}
function pushWireGuard(){
  const config = document.getElementById('wgConfig').value.trim();
  const name = document.getElementById('wgName').value.trim();
  if(!config) return showToast('Paste or select a WireGuard config first', false);
  postJson('/api/wireguard', {name, config});
}
function pushProxy(){
  const link = document.getElementById('proxyLink').value.trim();
  if(!link) return showToast('Enter a proxy URI first', false);
  postJson('/api/proxy', {link});
}
function pushSubscription(){
  const url = document.getElementById('subUrl').value.trim();
  const name = document.getElementById('subName').value.trim();
  if(!url) return showToast('Enter a subscription URL first', false);
  postJson('/api/subscription', {url, name});
}
function pushBackup(){
  const raw = document.getElementById('bkJson').value.trim();
  if(!raw) return showToast('Select or paste a backup JSON first', false);
  postJson('/api/backup', {json: raw});
}
</script>
</body>
</html>
""".trimIndent()
}
