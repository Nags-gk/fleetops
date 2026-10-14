const $ = (s) => document.querySelector(s);
const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
const api = new URLSearchParams(location.search).get("api");
const FLOOR_W = 40, FLOOR_H = 26;

let frames = [], incidents = [], start = 0, idx = 30, playing = true, selected = null;

const fmtClock = (ms) => {
  const s = Math.max(0, Math.round(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
};

function drawMap(robots, openRobots) {
  const c = $("#map"), dpr = window.devicePixelRatio || 1;
  const w = c.clientWidth, h = w * (FLOOR_H / FLOOR_W);
  if (c.width !== Math.round(w * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
  const g = c.getContext("2d");
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  const k = w / FLOOR_W;
  g.strokeStyle = css("--rule");
  g.lineWidth = 1;
  for (let x = 0; x <= FLOOR_W; x += 5) { g.beginPath(); g.moveTo(x * k, 0); g.lineTo(x * k, h); g.stroke(); }
  for (let y = 0; y <= FLOOR_H; y += 5) { g.beginPath(); g.moveTo(0, y * k); g.lineTo(w, y * k); g.stroke(); }
  const color = { MOVING: css("--moving"), LIFTING: css("--lifting"), IDLE: css("--idle"), CHARGING: css("--charging"), FAULT: css("--fault") };
  for (const [id, x, y, state] of robots) {
    g.beginPath();
    g.arc(x * k, y * k, 5, 0, Math.PI * 2);
    g.fillStyle = color[state] || css("--idle");
    g.fill();
    if (openRobots.has(id)) {
      g.beginPath(); g.arc(x * k, y * k, 10, 0, Math.PI * 2);
      g.strokeStyle = css("--sev1"); g.lineWidth = 2.5; g.stroke(); g.lineWidth = 1;
    }
  }
}

function kpis(robots, open) {
  $("#kRobots").textContent = robots.length;
  $("#kOffline").textContent = robots.filter((r) => r[3] === "FAULT" || r[3] === "OFFLINE").length;
  const b = robots.reduce((s, r) => s + r[4], 0) / Math.max(1, robots.length);
  $("#kBattery").textContent = `${Math.round(b)}%`;
  $("#kOpen").textContent = open.length;
}

function renderFeed(list, now) {
  $("#incidents").innerHTML = list.length ? list.map((i) => `
    <li class="${i.status === "RESOLVED" ? "resolved" : ""}"><button type="button" data-id="${i.id}">
      <span class="sev ${i.severity}">${i.severity}</span>
      <span><span class="kind">${i.kind.replaceAll("_", " ").toLowerCase()}</span><br><span class="sub">${i.robotId}${i.occurrences > 1 ? `, seen ${i.occurrences} times` : ""}</span></span>
      <span class="when">${api ? new Date(i.openedAt).toLocaleTimeString() : fmtClock(i.openedAt - start)}</span>
    </button></li>`).join("") : `<li><p class="sub">No incidents yet. The detector needs about a minute of driving to learn each robot's baseline.</p></li>`;
}

function showDetail(inc) {
  selected = inc;
  $("#detail").hidden = false;
  $("#dTitle").textContent = `${inc.severity} ${inc.kind.replaceAll("_", " ").toLowerCase()} on ${inc.robotId}`;
  $("#dMeta").textContent = `${inc.detail}. Status ${inc.status.toLowerCase()}, seen ${inc.occurrences} time${inc.occurrences > 1 ? "s" : ""}.`;
  $("#dTriage").textContent = inc.triage || "No triage yet.";
  $("#dAsk").hidden = !api;
  $("#detail").scrollIntoView({ block: "nearest", behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
}

/* ---------- replay mode ---------- */
function replayStep() {
  const f = frames[idx];
  const visible = incidents.filter((i) => i.openedAt <= f.t);
  const open = visible.filter((i) => i.openedAt <= f.t && f.t - i.lastSeenAt < 120_000);
  drawMap(f.robots, new Set(open.map((i) => i.robotId)));
  kpis(f.robots, open);
  renderFeed([...visible].reverse().map((i) => ({ ...i, status: f.t - i.lastSeenAt < 120_000 ? "OPEN" : "RESOLVED" })), f.t);
  $("#scrub").value = idx;
  $("#clock").textContent = fmtClock(f.t - start);
}

async function startReplay() {
  const d = await (await fetch("data/replay.json")).json();
  frames = d.frames; incidents = d.incidents; start = d.start;
  $("#mode").textContent = `Replay: recorded ${d.minutes}-minute simulated shift, ${d.injected.length} injected faults`;
  $("#scrub").max = frames.length - 1;
  $("#scrub").addEventListener("input", (e) => { idx = +e.target.value; replayStep(); });
  $("#play").addEventListener("click", (e) => { playing = !playing; e.target.textContent = playing ? "Pause" : "Play"; });
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) { playing = false; $("#play").textContent = "Play"; idx = frames.length - 1; }
  replayStep();
  setInterval(() => { if (playing) { idx = (idx + 1) % frames.length; replayStep(); } }, 700);
}

/* ---------- live mode ---------- */
async function poll() {
  try {
    const [fleet, inc] = await Promise.all([fetch(`${api}/fleet`).then((r) => r.json()), fetch(`${api}/incidents?limit=100`).then((r) => r.json())]);
    incidents = inc.incidents;
    const now = fleet.generatedAt;
    const robots = fleet.robots.map((h) => [h.robotId, h.x, h.y, now - h.tsMillis > 30_000 ? "OFFLINE" : h.state, h.batteryPct, h.motorTempC]);
    const open = incidents.filter((i) => i.status === "OPEN");
    drawMap(robots, new Set(open.map((i) => i.robotId)));
    kpis(robots, open);
    renderFeed(incidents, now);
    $("#mode").textContent = `Live: ${new URL(api).host}`;
    $("#mode").classList.add("live");
  } catch (e) {
    $("#mode").textContent = "Live API unreachable. Check the ?api= URL and its CORS origins.";
  }
}

function startLive() {
  $("#replayCtl").hidden = true;
  $("#dAsk").addEventListener("click", async () => {
    $("#dTriage").textContent = "Asking the assistant…";
    const r = await fetch(`${api}/incidents/${selected.id}/triage`, { method: "POST" }).then((x) => x.json());
    $("#dTriage").textContent = `${r.text}\n\nSource: ${r.source}`;
  });
  poll();
  setInterval(poll, 5000);
}

async function loadEval() {
  try {
    const e = await (await fetch("data/detector_eval.json")).json();
    $("#evalNote").textContent = `${e.robots} simulated robots, ${e.simulated_minutes} minutes, ${e.heartbeats.toLocaleString()} heartbeats, ${e.injected_faults} labelled faults injected after a 5-minute warm-up.`;
    $("#evalTable tbody").innerHTML = Object.entries(e.recall_by_fault).map(([k, v]) => `<tr><td>${k.replaceAll("_", " ").toLowerCase()}</td><td>${v}</td></tr>`).join("");
    const facts = [
      ["Faults detected", `${Math.round(e.recall * 100)}%`],
      ["Median time to detect", `${e.detection_latency_s_median} s`],
      ["False pages", e.false_pages],
      ["False tickets per robot-hour", e.false_positives_per_robot_hour.toFixed(2)],
      ["Raw anomalies per incident", Math.round(e.alert_dedup_ratio)],
      ["Heartbeats per second, one core", e.processing_heartbeats_per_s_single_thread.toLocaleString()],
    ];
    $("#evalFacts").innerHTML = facts.map(([k, v]) => `<div><dt>${k}</dt><dd>${v}</dd></div>`).join("");
  } catch { $("#evalNote").textContent = "Evaluation results are not available in this build."; }
}

$("#incidents").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-id]");
  if (b) showDetail(incidents.find((i) => i.id === b.dataset.id));
});
$("#dClose").addEventListener("click", () => { $("#detail").hidden = true; });
loadEval();
if (api) startLive(); else startReplay();
