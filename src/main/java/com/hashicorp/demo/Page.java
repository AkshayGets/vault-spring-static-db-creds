package com.hashicorp.demo;

/** The single page, inlined so the image needs no static resources. */
final class Page {

    static String html(String path, int poolSize) {
        return """
<!doctype html>
<title>Vault Static Roles + HikariCP</title>
<style>
 :root{--bg:#0d1117;--card:#161b22;--line:#2d3748;--fg:#e6edf3;--muted:#8b949e;
       --g:#3fb950;--r:#f85149;--a:#d29922;--b:#58a6ff;--p:#bc8cff}
 *{box-sizing:border-box} body{margin:0;padding:22px;background:var(--bg);color:var(--fg);
   font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;font-size:14px}
 h1{margin:0 0 4px;font-size:21px} .sub{color:var(--muted);margin-bottom:16px;font-size:13px}
 .chips{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}
 .chip{background:var(--card);border:1px solid var(--line);border-radius:6px;padding:5px 10px;
       font-size:11px;color:var(--muted)} .chip b{color:var(--fg)}
 .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:14px}
 @media(max-width:900px){.grid{grid-template-columns:repeat(2,1fr)}}
 .card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:13px 15px}
 .k{font-size:10px;text-transform:uppercase;letter-spacing:.5px;color:var(--muted);margin-bottom:5px}
 .v{font-size:23px;font-weight:600;font-variant-numeric:tabular-nums}
 .mono{font-family:"SF Mono",Menlo,Consolas,monospace}
 .small{font-size:12px;font-weight:400}
 .g{color:var(--g)}.r{color:var(--r)}.a{color:var(--a)}.b{color:var(--b)}.p{color:var(--p)}.m{color:var(--muted)}
 .track{height:6px;background:#1c2330;border-radius:3px;overflow:hidden;margin-top:8px}
 .bar{height:100%;background:var(--g);transition:width .3s}
 .log{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 15px;
      max-height:260px;overflow-y:auto}
 .ev{display:flex;gap:10px;padding:3px 0;font-size:11px;font-family:"SF Mono",Menlo,monospace;line-height:1.6}
 .ev .t{color:var(--muted);flex-shrink:0} .ev .kk{width:100px;flex-shrink:0;font-weight:700;text-transform:uppercase}
 button{background:#1c2330;color:var(--fg);border:1px solid var(--line);border-radius:6px;
        padding:7px 13px;font-size:12px;cursor:pointer;margin-bottom:14px}
 button:hover{border-color:var(--b)}
</style>

<h1>Static database roles, no restarts</h1>
<div class="sub">Vault rotates the password. HikariCP keeps serving. Nothing restarts, nothing is cached.</div>

<div class="chips">
  <div class="chip">Vault path <b>__PATH__</b></div>
  <div class="chip">auth <b>Kubernetes</b></div>
  <div class="chip">pool size <b>__POOLSIZE__</b></div>
  <div class="chip">pool <b>HikariCP</b></div>
</div>

<button onclick="refresh()">force a refresh from Vault</button>

<div class="grid">
  <div class="card">
    <div class="k">Credential in use</div>
    <div class="v mono small b" id="user">--</div>
    <div class="k" style="margin-top:8px">fetched <span id="ago">--</span>s ago
      &middot; <span id="refreshes">0</span> refreshes</div>
  </div>
  <div class="card">
    <div class="k">Vault rotates in</div>
    <div class="v g" id="ttl">--</div>
    <div class="track"><div class="bar" id="bar"></div></div>
  </div>
  <div class="card">
    <div class="k">Queries succeeded</div>
    <div class="v g" id="ok">0</div>
    <div class="k" style="margin-top:8px">pool: <span id="pool">--</span></div>
  </div>
  <div class="card">
    <div class="k">Queries failed</div>
    <div class="v" id="failed">0</div>
    <div class="k" style="margin-top:8px" id="lastErr">&nbsp;</div>
  </div>
</div>

<div class="log"><div id="events"></div></div>

<script>
const COLOR={startup:"m","auth-failure":"a",recovered:"g","query-failed":"r"};
let maxTtl=0;

async function poll(){
  try{
    const d=await (await fetch("/api/status")).json();
    const c=d.credential;
    document.getElementById("user").textContent=c.username;
    document.getElementById("ago").textContent=c.fetchedSecondsAgo;
    document.getElementById("refreshes").textContent=c.refreshCount;

    if(c.ttlSeconds>maxTtl) maxTtl=c.ttlSeconds;
    const rem=c.ttlRemainingSeconds;
    const t=document.getElementById("ttl");
    t.textContent=rem+"s";
    t.className="v "+(rem<=maxTtl*0.2?"r":rem<=maxTtl*0.5?"a":"g");
    document.getElementById("bar").style.width=
      (maxTtl?Math.round(rem/maxTtl*100):0)+"%";

    document.getElementById("ok").textContent=d.queriesOk.toLocaleString();
    const f=document.getElementById("failed");
    f.textContent=d.queriesFailed.toLocaleString();
    f.className="v "+(d.queriesFailed?"r":"m");
    document.getElementById("lastErr").textContent=d.lastError||"\\u00a0";
    document.getElementById("pool").textContent=
      d.pool.total+" total, "+d.pool.active+" active, "+d.pool.idle+" idle";

    document.getElementById("events").innerHTML=d.events.map(e=>
      '<div class="ev"><div class="t">'+e.at+'</div><div class="kk '+
      (COLOR[e.kind]||"b")+'">'+e.kind+'</div><div>'+e.detail+'</div></div>').join("");
  }catch(e){}
}
async function refresh(){
  await fetch("/api/refresh",{method:"POST"});
  poll();
}
poll(); setInterval(poll,1000);
</script>
"""
                .replace("__PATH__", path)
                .replace("__POOLSIZE__", String.valueOf(poolSize));
    }

    private Page() {}
}
