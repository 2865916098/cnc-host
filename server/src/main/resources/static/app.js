/* CNC 服务端管理后台（无构建链，原生 JS） */
(() => {
  "use strict";

  const S = {
    token: localStorage.getItem("cnc_token") || "",
    username: localStorage.getItem("cnc_user") || "",
    config: null,
    tasks: [],
    devices: [],
    orgSummary: [],
    appVersion: null,
    apkCandidate: null,
    status: null,
    tab: "overview",
    ws: null,
    devicesTimer: null,
    user: null,
    users: [],
    usersLoaded: false
  };

  const $ = (id) => document.getElementById(id);
  const esc = (v) => String(v == null ? "" : v)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  const jsStr = (v) => String(v == null ? "" : v).replace(/\\/g, "\\\\").replace(/'/g, "\\'");

  // ===== API =====
  async function api(path, opts = {}) {
    const headers = Object.assign({ Authorization: "Bearer " + S.token }, opts.headers || {});
    let body = opts.body;
    if (body != null && typeof body !== "string") {
      body = JSON.stringify(body);
      headers["Content-Type"] = "application/json";
    }
    const res = await fetch(path, Object.assign({}, opts, { body, headers }));
    if (res.status === 401) {
      logout("管理口令无效或已变更，请重新登录");
      throw new Error("未授权");
    }
    const data = await res.json().catch(() => null);
    if (!res.ok) throw new Error((data && data.message) || ("HTTP " + res.status));
    return data;
  }

  function toast(msg, isError) {
    const t = $("toast");
    t.textContent = msg;
    t.className = "toast show" + (isError ? " error" : "");
    clearTimeout(t._timer);
    t._timer = setTimeout(() => { t.className = "toast"; }, 3200);
  }

  // ===== 登录 =====
  async function login() {
    const username = $("username").value.trim();
    const password = $("password").value;
    if (!username || !password) { $("login-error").textContent = "请输入账号和密码"; return; }
    try {
      const res = await fetch("/api/v1/admin/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password })
      });
      const data = await res.json().catch(() => null);
      if (!res.ok || !data || !data.ok) {
        $("login-error").textContent = (data && data.message) || "登录失败";
        return;
      }
      S.token = data.token;
      S.username = data.username || username;
      localStorage.setItem("cnc_token", S.token);
      localStorage.setItem("cnc_user", S.username);
      // 旧后端不返回角色/权限字段时保持 null，交给 loadMe 统一降级
      S.user = data.role ? {
        id: null,
        username: S.username,
        displayName: data.displayName || S.username,
        role: data.role,
        permissions: Array.isArray(data.permissions) ? data.permissions : [],
        superAdmin: data.role === "SUPER_ADMIN"
      } : null;
      await loadMe();
      if (!S.token) return;
      enterApp();
      await loadAll();
      connectWs();
    } catch (e) {
      $("login-error").textContent = "无法连接服务器：" + e.message;
    }
  }

  // 当前登录用户：/users/me 未就绪时降级，避免登录流程中断
  async function loadMe() {
    try {
      const me = await api("/api/v1/users/me");
      S.user = {
        id: me.id,
        username: me.username || S.username,
        displayName: me.displayName || me.username || S.username,
        role: me.role || "",
        permissions: Array.isArray(me.permissions) ? me.permissions : [],
        superAdmin: !!(me.superAdmin || me.role === "SUPER_ADMIN")
      };
    } catch (e) {
      if (!S.token) return; // 401 已被 logout 处理
      if (!S.user) {
        S.user = { superAdmin: true, role: "SUPER_ADMIN", permissions: [], username: S.username, displayName: S.username };
      }
    }
    renderNav();
  }

  function enterApp() {
    const u = S.user || {};
    const name = u.displayName || u.username || S.username || "-";
    const role = u.role ? "（" + (ROLE_LABELS[u.role] || u.role) + "）" : "";
    $("current-user").textContent = name + role;
    $("login").style.display = "none";
    $("app").style.display = "block";
  }

  async function logout(msg) {
    if (S.token) {
      try { await fetch("/api/v1/admin/logout", { method: "POST", headers: { Authorization: "Bearer " + S.token } }); } catch (_) {}
    }
    stopDevicesPolling();
    localStorage.removeItem("cnc_token");
    localStorage.removeItem("cnc_user");
    S.token = "";
    S.username = "";
    S.user = null;
    S.users = [];
    S.usersLoaded = false;
    S.tab = "overview";
    if (S.ws) { try { S.ws.close(); } catch (_) {} S.ws = null; }
    $("app").style.display = "none";
    $("login").style.display = "flex";
    $("login-error").textContent = msg || "";
    $("password").value = "";
  }

  // ===== 数据 =====
  async function loadAll() {
    S.config = await api("/api/v1/config");
    // R6：无「任务记录」模块权限的账号会拿到 403，容错为空列表避免登录后白屏
    S.tasks = await api("/api/v1/tasks").catch(() => []);
    S.devices = await api("/api/v1/devices").catch(() => []);
    S.orgSummary = await api("/api/v1/devices/org-summary").catch(() => []);
    S.appVersion = await api("/api/v1/app-version-info").catch(() => null);
    S.apkCandidate = await api("/api/v1/app-latest-candidate").catch(() => null);
    $("cfg-version").textContent = S.config.version;
    renderNav();
    render();
  }

  function findById(list, id) {
    return (list || []).find((x) => x.id === id);
  }
  function correctionOf(drawingId) {
    return (S.config.drawingCorrections || []).find((c) => c.drawingId === drawingId);
  }
  function optionList(list, valueKey, labelFn, selected) {
    return list.map((x) => {
      const v = x[valueKey];
      const sel = String(v) === String(selected) ? " selected" : "";
      return `<option value="${esc(v)}"${sel}>${esc(labelFn(x))}</option>`;
    }).join("");
  }

  // ===== 权限 =====
  const ROLE_LABELS = { SUPER_ADMIN: "超级管理员", ADMIN: "管理员", PROCESS_ENGINEER: "工艺员", OPERATOR: "操作员", QUALITY: "质检员" };
  const MODULE_LABELS = {
    overview: "概览", device: "设备与通讯", tools: "刀具库", materials: "材料库",
    parts: "零件与毛坯", plans: "工艺方案", drawings: "图纸任务", devices: "设备管理",
    tasks: "任务记录", users: "用户管理", app: "客户端发布", logs: "操作日志"
  };
  // 角色默认模块权限（ADR-0003），新建/切换角色时自动勾选，仍可手工调整
  const ROLE_DEFAULT_PERMS = {
    SUPER_ADMIN: [],
    ADMIN: Object.keys(MODULE_LABELS),
    PROCESS_ENGINEER: ["overview", "tools", "materials", "parts", "plans", "drawings"],
    OPERATOR: ["overview", "device", "tasks"],
    QUALITY: ["overview", "tasks", "logs"]
  };
  const USER_MODULES = Object.keys(MODULE_LABELS);

  function can(perm) {
    const u = S.user;
    if (!u) return true; // 未知时宽松，避免空白后台
    if (u.superAdmin || u.role === "SUPER_ADMIN") return true;
    return (u.permissions || []).includes(perm);
  }

  // ===== 导航 =====
  const TABS = [
    { id: "overview", label: "概览", sub: "配置版本与设备实时状态" },
    { id: "device", label: "设备与通讯", sub: "控制卡、行程与网络/串口参数" },
    { id: "tools", label: "刀具库", sub: "刀具编号、类型与磨损状态", count: (c) => c.tools.length },
    { id: "materials", label: "材料库", sub: "切削速度、进给与切深推荐值", count: (c) => c.materials.length },
    { id: "parts", label: "零件与毛坯", sub: "零件、毛坯尺寸与装夹方式", count: (c) => c.parts.length + c.blanks.length },
    { id: "plans", label: "工艺方案", sub: "工序步骤与切削参数", count: (c) => c.plans.length },
    { id: "drawings", label: "图纸任务", sub: "DXF 原文与尺寸校正，客户端据此生成刀路", count: (c) => c.drawings.length },
    { id: "devices", label: "设备管理", sub: "已激活终端、在线状态与功能权限", count: () => S.devices.length },
    { id: "tasks", label: "任务记录", sub: "平板端回传的执行记录", count: () => S.tasks.length },
    { id: "users", label: "用户管理", sub: "后台账号、角色与模块权限分配", count: () => S.users ? S.users.length : "" }
  ];

  function visibleTabs() {
    return TABS.filter((t) => t.id === "overview" || can(t.id));
  }

  function renderNav() {
    const tabs = visibleTabs();
    if (!tabs.some((t) => t.id === S.tab)) S.tab = "overview";
    $("nav").innerHTML = tabs.map((t) => {
      const n = t.count && S.config ? t.count(S.config) : "";
      return `<div class="nav-item ${S.tab === t.id ? "active" : ""}" onclick="switchTab('${t.id}')">
        <span>${esc(t.label)}</span><span class="count">${n}</span></div>`;
    }).join("");
  }

  window.switchTab = function (id) {
    if (id !== "overview" && !can(id)) { toast("没有该模块的访问权限", true); return; }
    S.tab = id;
    renderNav();
    render();
    if (id === "devices") startDevicesPolling(); else stopDevicesPolling();
    if (id === "users") loadUsers();
  };

  // ===== 设备页自动刷新：激活/心跳与单位统计变化 3 秒内可见 =====
  function startDevicesPolling() {
    stopDevicesPolling();
    S.devicesTimer = setInterval(async () => {
      if (S.tab !== "devices") { stopDevicesPolling(); return; }
      try {
        // 只拉设备与单位统计，避免每 3 秒全量拉取配置
        const [next, org] = await Promise.all([
          api("/api/v1/devices"),
          api("/api/v1/devices/org-summary").catch(() => null)
        ]);
        let changed = false;
        if (JSON.stringify(next) !== JSON.stringify(S.devices)) {
          S.devices = next;
          changed = true;
        }
        if (Array.isArray(org) && JSON.stringify(org) !== JSON.stringify(S.orgSummary)) {
          S.orgSummary = org;
          changed = true;
        }
        if (changed) {
          renderNav();
          render();
        }
      } catch (_) {}
    }, 3000);
  }

  function stopDevicesPolling() {
    if (S.devicesTimer) { clearInterval(S.devicesTimer); S.devicesTimer = null; }
  }

  function render() {
    if (!visibleTabs().some((t) => t.id === S.tab)) S.tab = "overview";
    const t = TABS.find((x) => x.id === S.tab);
    $("page-title").textContent = t.label;
    $("page-sub").textContent = t.sub;
    const c = S.config;
    const views = {
      overview: viewOverview,
      device: viewDevice,
      tools: viewTools,
      materials: viewMaterials,
      parts: viewParts,
      plans: viewPlans,
      drawings: viewDrawings,
      devices: viewDevices,
      tasks: viewTasks,
      users: viewUsers
    };
    const view = views[S.tab] || viewOverview;
    $("content").innerHTML = view(c);
  }

  // ===== 概览 =====
  function liveStatusHtml() {
    const s = S.status || {};
    const connected = !!s.connected;
    return `
      <div class="live-grid">
        <div class="live-item"><div class="k">平板连接</div><div class="v">${connected ? "在线" : "离线"}</div></div>
        <div class="live-item"><div class="k">运行模式</div><div class="v">${esc(s.mode || "-")}${s.isSimulated ? "（模拟）" : ""}</div></div>
        <div class="live-item"><div class="k">当前任务</div><div class="v">${esc(s.taskName || "无")}</div></div>
        <div class="live-item"><div class="k">任务进度</div><div class="v">${Math.round((s.progress || 0) * 100)}%</div></div>
        <div class="live-item"><div class="k">X / Z 位置</div><div class="v">${s.xMm == null ? "-" : Number(s.xMm).toFixed(2)} / ${s.zMm == null ? "-" : Number(s.zMm).toFixed(2)}</div></div>
        <div class="live-item"><div class="k">主轴</div><div class="v">${s.spindleRpm == null ? "-" : s.spindleRpm + " rpm"}</div></div>
      </div>
      <div class="desc" style="margin-top:12px">${esc(s.message || "暂无状态上报")} · ${s.timestamp ? new Date(s.timestamp).toLocaleString() : "-"}</div>`;
  }

  function viewOverview(c) {
    const active = (S.tasks || []).filter((t) => t.status === "SUCCESS").length;
    return `
      <div class="cards">
        <div class="card"><div class="k">配置版本</div><div class="v">v${c.version}</div><div class="hint">任何配置写入 +1</div></div>
        <div class="card"><div class="k">图纸任务</div><div class="v">${c.drawings.length}</div><div class="hint">可下发到平板</div></div>
        <div class="card"><div class="k">工艺方案</div><div class="v">${c.plans.length}</div><div class="hint">共 ${c.plans.reduce((n, p) => n + (p.steps || []).length, 0)} 道工序</div></div>
        <div class="card"><div class="k">累计完成</div><div class="v">${active}</div><div class="hint">成功任务记录</div></div>
      </div>
      <div class="hint-box">平板客户端只负责选任务、预览、下发和监控；参数全部在此维护。客户端离线时使用上次同步缓存，联网后以服务端版本为准覆盖。</div>
      <div class="panel"><h3>设备实时状态</h3><div id="live-area">${liveStatusHtml()}</div></div>
      <div class="panel"><h3>最近任务</h3>${taskTable(S.tasks.slice(0, 6))}</div>`;
  }

  // ===== 设备 =====
  function viewDevice(c) {
    const d = c.device;
    const comm = d.comm || {};
    return `
      <div class="hint-box">设备与通讯参数由服务端统一下发。修改后需在平板「高级 → 服务端同步」点一次同步；急停、限位与运动下发不依赖服务端在线。</div>
      <div class="panel">
        <h3>控制卡与行程
          <button class="primary small" onclick="saveDevice()">保存</button>
        </h3>
        <div class="form-row">
          <div class="field"><label>设备名称</label><input id="dev_name" value="${esc(d.name)}"></div>
          <div class="field"><label>控制卡</label><select id="dev_card">${optionList(
            ["FANUC_0I", "SIEMENS_840D", "MAZAK_SMART", "HASS_VF", "MODBUS_GENERIC"].map((v) => ({ v })), "v", (x) => x.v.replace(/_/g, " "), d.controlCard)}</select></div>
        </div>
        <div class="grid3">
          <div class="field"><label>X 最大行程 (mm)</label><input id="dev_tx" type="number" step="0.1" value="${esc(d.maxTravelXmm)}"></div>
          <div class="field"><label>Z 最大行程 (mm)</label><input id="dev_tz" type="number" step="0.1" value="${esc(d.maxTravelZmm)}"></div>
          <div class="field"><label>主轴最高转速 (rpm)</label><input id="dev_rpm" type="number" value="${esc(d.maxRpm)}"></div>
        </div>
        <div class="form-row" style="margin-top:12px">
          <div class="field"><label>最大进给 (mm/min)</label><input id="dev_feed" type="number" step="0.1" value="${esc(d.maxFeedMmPerMin)}"></div>
          <div class="field"><label class="check" style="margin-top:20px"><input type="checkbox" id="dev_sim" ${d.useSimulator ? "checked" : ""}> 使用模拟器（无真实控制卡时保持开启）</label></div>
        </div>
      </div>
      <div class="panel">
        <h3>驱动与通讯参数</h3>
        <div class="form-row">
          <div class="field"><label>驱动类型</label><select id="comm_driver">${optionList(
            [{v:"SIMULATOR",t:"SIMULATOR（内置模拟器）"},{v:"MODBUS_TCP",t:"MODBUS_TCP（通用网口控制卡）"}].map((x) => ({ v: x.v, t: x.t })), "v", (x) => x.t, comm.driverType || "SIMULATOR")}</select></div>
          <div class="field"><label>协议</label><select id="comm_proto">${optionList(
            ["MODBUS_TCP", "MODBUS_RTU", "SERIAL_TCP"].map((v) => ({ v })), "v", (x) => x.v.replace(/_/g, " "), comm.protocol)}</select></div>
          <div class="field"><label>设备站号</label><input id="comm_dev" type="number" value="${esc(comm.deviceId)}"></div>
        </div>
        <div class="grid3">
          <div class="field"><label>IP 地址</label><input id="comm_ip" value="${esc(comm.ipAddress)}"></div>
          <div class="field"><label>端口</label><input id="comm_port" type="number" value="${esc(comm.port)}"></div>
          <div class="field"><label>超时 (ms)</label><input id="comm_timeout" type="number" value="${esc(comm.timeoutMs)}"></div>
        </div>
        <div class="grid2" style="margin-top:12px">
          <div class="field"><label>串口设备</label><input id="comm_serial" value="${esc(comm.serialPort)}"></div>
          <div class="field"><label>波特率</label><select id="comm_baud">${optionList(
            [9600, 19200, 38400, 57600, 115200].map((v) => ({ v })), "v", (x) => String(x.v), comm.baudRate)}</select></div>
        </div>
        <div class="form-row" style="margin-top:12px">
          <div class="field"><label>驱动扩展参数（JSON，寄存器映射等；留空用默认布局）</label>
            <textarea id="comm_params" rows="4" placeholder='{"pos_scale":"100","status_word_addr":"6"}' style="width:100%">${esc(JSON.stringify(comm.params || {}))}</textarea>
            <div class="hint">换控制卡只需调整此处映射，客户端无需升级；非法 JSON 保存时会被拒绝。</div>
          </div>
        </div>
      </div>`;
  }

  window.saveDevice = async function () {
    const c = S.config;
    let params = {};
    const rawParams = ($("comm_params").value || "").trim();
    if (rawParams) {
      try {
        const parsed = JSON.parse(rawParams);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("参数必须是 JSON 对象");
        for (const k of Object.keys(parsed)) params[k] = String(parsed[k]);
      } catch (e) {
        toast("驱动扩展参数 JSON 非法：" + e.message, true);
        return;
      }
    }
    const d = {
      name: $("dev_name").value.trim(),
      controlCard: $("dev_card").value,
      useSimulator: $("dev_sim").checked,
      maxTravelXmm: Number($("dev_tx").value),
      maxTravelZmm: Number($("dev_tz").value),
      maxRpm: Number($("dev_rpm").value),
      maxFeedMmPerMin: Number($("dev_feed").value),
      comm: {
        protocol: $("comm_proto").value,
        ipAddress: $("comm_ip").value.trim(),
        port: Number($("comm_port").value),
        serialPort: $("comm_serial").value.trim(),
        baudRate: Number($("comm_baud").value),
        deviceId: Number($("comm_dev").value),
        timeoutMs: Number($("comm_timeout").value),
        driverType: $("comm_driver").value,
        params: params
      }
    };
    try {
      await api("/api/v1/device", { method: "PUT", body: d });
      toast("设备与通讯配置已保存");
      await loadAll();
    } catch (e) { toast(e.message, true); }
  };

  // ===== 通用表格 =====
  function table(headers, rows, emptyText) {
    if (!rows.length) return `<div class="empty">${esc(emptyText || "暂无数据")}</div>`;
    return `<table><thead><tr>${headers.map((h) => `<th>${esc(h)}</th>`).join("")}</tr></thead>
      <tbody>${rows.join("")}</tbody></table>`;
  }

  function tag(v, on) {
    return `<span class="tag ${on ? "on" : "off"}">${esc(v)}</span>`;
  }

  // ===== 刀具 =====
  function viewTools(c) {
    const rows = c.tools.map((t) => `<tr>
      <td>${t.toolNumber}</td>
      <td>${esc(t.name)}</td>
      <td class="mono">${esc(t.toolType)}</td>
      <td>${esc(t.material)}</td>
      <td>${t.maxRpm} rpm / ${t.maxFeedMmPerMin} mm/min</td>
      <td>${tag(t.wearStatus, t.wearStatus === "NEW")}</td>
      <td class="muted">${t.usageCount} 次</td>
      <td class="actions"><button class="small" onclick="openTool('${jsStr(t.id)}')">编辑</button>
        <button class="small danger" onclick="delEntity('tools','${jsStr(t.id)}','刀具')">删除</button></td>
    </tr>`);
    return `<div class="panel"><h3>刀具库 <button class="primary small" onclick="openTool('')">新增刀具</button></h3>
      ${table(["T#", "名称", "类型", "材质", "能力", "磨损", "使用", ""], rows, "尚未配置刀具")}</div>`;
  }

  const TOOL_TYPES = ["TURNING", "FINISH_TURNING", "GROOVING", "THREADING", "DRILLING", "CUT_OFF"];
  const WEAR = ["NEW", "LIGHT", "MODERATE", "CRITICAL"];

  window.openTool = function (id) {
    const t = findById(S.config.tools, id) || { toolNumber: 1, toolType: "TURNING", name: "", material: "CARBIDE", nominalDiameterMm: 0, lengthMm: 0, maxRpm: 3000, maxFeedMmPerMin: 500, wearStatus: "NEW", usageCount: 0, notes: "" };
    openModal(id ? "编辑刀具" : "新增刀具", `
      <div class="form-row">
        <div class="field"><label>刀具号 T</label><input id="f_toolNumber" type="number" value="${esc(t.toolNumber)}"></div>
        <div class="field"><label>名称</label><input id="f_name" value="${esc(t.name)}"></div>
      </div>
      <div class="form-row">
        <div class="field"><label>类型</label><select id="f_toolType">${optionList(TOOL_TYPES.map((v) => ({ v })), "v", (x) => x.v, t.toolType)}</select></div>
        <div class="field"><label>刀体材质</label><select id="f_material">${optionList(["HSS", "CARBIDE", "CERAMIC"].map((v) => ({ v })), "v", (x) => x.v, t.material)}</select></div>
        <div class="field"><label>磨损状态</label><select id="f_wearStatus">${optionList(WEAR.map((v) => ({ v })), "v", (x) => x.v, t.wearStatus)}</select></div>
      </div>
      <div class="grid3">
        <div class="field"><label>刀尖直径 (mm)</label><input id="f_nominalDiameterMm" type="number" step="0.1" value="${esc(t.nominalDiameterMm)}"></div>
        <div class="field"><label>有效长度 (mm)</label><input id="f_lengthMm" type="number" step="0.1" value="${esc(t.lengthMm)}"></div>
        <div class="field"><label>使用次数</label><input id="f_usageCount" type="number" value="${esc(t.usageCount)}"></div>
      </div>
      <div class="grid2" style="margin-top:12px">
        <div class="field"><label>最高转速 (rpm)</label><input id="f_maxRpm" type="number" value="${esc(t.maxRpm)}"></div>
        <div class="field"><label>最大进给 (mm/min)</label><input id="f_maxFeedMmPerMin" type="number" step="0.1" value="${esc(t.maxFeedMmPerMin)}"></div>
      </div>
      <div class="field" style="margin-top:12px"><label>备注</label><textarea id="f_notes" rows="2">${esc(t.notes)}</textarea></div>`,
      async () => {
        const body = {
          id: id || "",
          toolNumber: Number($("f_toolNumber").value),
          toolType: $("f_toolType").value,
          name: $("f_name").value.trim(),
          material: $("f_material").value,
          nominalDiameterMm: Number($("f_nominalDiameterMm").value),
          lengthMm: Number($("f_lengthMm").value),
          maxRpm: Number($("f_maxRpm").value),
          maxFeedMmPerMin: Number($("f_maxFeedMmPerMin").value),
          wearStatus: $("f_wearStatus").value,
          usageCount: Number($("f_usageCount").value),
          notes: $("f_notes").value
        };
        await api("/api/v1/tools", { method: "POST", body });
        toast("刀具已保存");
      });
  };

  // ===== 材料 =====
  function viewMaterials(c) {
    const rows = c.materials.map((m) => `<tr>
      <td>${esc(m.name)}</td><td class="mono">${esc(m.code)}</td>
      <td>${m.density}</td><td>${esc(m.hardness)}</td>
      <td>${m.recommendedCuttingSpeedMs} m/min</td>
      <td>${m.recommendedFeedPerRev} mm/r</td>
      <td>${m.recommendedDepthMm} mm</td>
      <td>${tag(m.enabled ? "启用" : "停用", m.enabled)}</td>
      <td class="actions"><button class="small" onclick="openMaterial('${jsStr(m.id)}')">编辑</button>
        <button class="small danger" onclick="delEntity('materials','${jsStr(m.id)}','材料')">删除</button></td>
    </tr>`);
    return `<div class="panel"><h3>材料库 <button class="primary small" onclick="openMaterial('')">新增材料</button></h3>
      ${table(["名称", "牌号", "密度", "硬度", "切削速度", "每转进给", "切深", "状态", ""], rows, "尚未配置材料")}</div>`;
  }

  window.openMaterial = function (id) {
    const m = findById(S.config.materials, id) || { name: "", code: "", density: 7.85, hardness: "", recommendedCuttingSpeedMs: 150, recommendedFeedPerRev: 0.2, recommendedDepthMm: 2, enabled: true, notes: "" };
    openModal(id ? "编辑材料" : "新增材料", `
      <div class="form-row">
        <div class="field"><label>名称</label><input id="f_name" value="${esc(m.name)}"></div>
        <div class="field"><label>牌号</label><input id="f_code" value="${esc(m.code)}"></div>
      </div>
      <div class="grid3">
        <div class="field"><label>密度 (g/cm³)</label><input id="f_density" type="number" step="0.01" value="${esc(m.density)}"></div>
        <div class="field"><label>硬度</label><input id="f_hardness" value="${esc(m.hardness)}"></div>
        <div class="field"><label>推荐切削速度 (m/min)</label><input id="f_cs" type="number" step="0.1" value="${esc(m.recommendedCuttingSpeedMs)}"></div>
      </div>
      <div class="grid3" style="margin-top:12px">
        <div class="field"><label>每转进给 (mm/r)</label><input id="f_fr" type="number" step="0.01" value="${esc(m.recommendedFeedPerRev)}"></div>
        <div class="field"><label>推荐切深 (mm)</label><input id="f_depth" type="number" step="0.1" value="${esc(m.recommendedDepthMm)}"></div>
        <div class="field"><label class="check" style="margin-top:20px"><input type="checkbox" id="f_enabled" ${m.enabled ? "checked" : ""}> 启用</label></div>
      </div>
      <div class="field" style="margin-top:12px"><label>备注</label><textarea id="f_notes" rows="2">${esc(m.notes)}</textarea></div>`,
      async () => {
        const body = {
          id: id || "",
          name: $("f_name").value.trim(),
          code: $("f_code").value.trim(),
          density: Number($("f_density").value),
          hardness: $("f_hardness").value.trim(),
          recommendedCuttingSpeedMs: Number($("f_cs").value),
          recommendedFeedPerRev: Number($("f_fr").value),
          recommendedDepthMm: Number($("f_depth").value),
          enabled: $("f_enabled").checked,
          notes: $("f_notes").value
        };
        await api("/api/v1/materials", { method: "POST", body });
        toast("材料已保存");
      });
  };

  // ===== 零件与毛坯 =====
  function viewParts(c) {
    const partRows = c.parts.map((p) => `<tr>
      <td>${esc(p.name)}</td>
      <td>${esc((findById(c.materials, p.materialId) || {}).name || p.materialId)}</td>
      <td class="muted">${esc(p.notes)}</td>
      <td class="actions"><button class="small" onclick="openPart('${jsStr(p.id)}')">编辑</button>
        <button class="small danger" onclick="delEntity('parts','${jsStr(p.id)}','零件')">删除</button></td>
    </tr>`);
    const blankRows = c.blanks.map((b) => `<tr>
      <td>${esc((findById(c.parts, b.partId) || {}).name || b.partId)}</td>
      <td>φ${b.diameterMm} × ${b.lengthMm} mm</td>
      <td>${esc((findById(c.materials, b.materialId) || {}).name || b.materialId)}</td>
      <td>${esc(b.clampType)}</td>
      <td class="actions"><button class="small" onclick="openBlank('${jsStr(b.id)}')">编辑</button>
        <button class="small danger" onclick="delEntity('blanks','${jsStr(b.id)}','毛坯')">删除</button></td>
    </tr>`);
    return `
      <div class="panel"><h3>零件 <button class="primary small" onclick="openPart('')">新增零件</button></h3>
        ${table(["名称", "材料", "备注", ""], partRows, "尚未配置零件")}</div>
      <div class="panel"><h3>毛坯 <button class="primary small" onclick="openBlank('')">新增毛坯</button></h3>
        ${table(["所属零件", "尺寸", "材料", "装夹", ""], blankRows, "尚未配置毛坯")}</div>`;
  }

  window.openPart = function (id) {
    const p = findById(S.config.parts, id) || { name: "", materialId: (S.config.materials[0] || {}).id || "", notes: "" };
    openModal(id ? "编辑零件" : "新增零件", `
      <div class="form-row">
        <div class="field"><label>零件名称</label><input id="f_name" value="${esc(p.name)}"></div>
        <div class="field"><label>材料</label><select id="f_materialId">${optionList(S.config.materials, "id", (m) => m.name, p.materialId)}</select></div>
      </div>
      <div class="field"><label>备注</label><textarea id="f_notes" rows="2">${esc(p.notes)}</textarea></div>`,
      async () => {
        await api("/api/v1/parts", { method: "POST", body: { id: id || "", name: $("f_name").value.trim(), materialId: $("f_materialId").value, notes: $("f_notes").value } });
        toast("零件已保存");
      });
  };

  window.openBlank = function (id) {
    const b = findById(S.config.blanks, id) || { partId: (S.config.parts[0] || {}).id || "", diameterMm: 80, lengthMm: 120, materialId: (S.config.materials[0] || {}).id || "", clampType: "CHUCK" };
    openModal(id ? "编辑毛坯" : "新增毛坯", `
      <div class="form-row">
        <div class="field"><label>所属零件</label><select id="f_partId">${optionList(S.config.parts, "id", (p) => p.name, b.partId)}</select></div>
        <div class="field"><label>材料</label><select id="f_materialId">${optionList(S.config.materials, "id", (m) => m.name, b.materialId)}</select></div>
      </div>
      <div class="grid3">
        <div class="field"><label>直径 (mm)</label><input id="f_diameterMm" type="number" step="0.1" value="${esc(b.diameterMm)}"></div>
        <div class="field"><label>长度 (mm)</label><input id="f_lengthMm" type="number" step="0.1" value="${esc(b.lengthMm)}"></div>
        <div class="field"><label>装夹方式</label><select id="f_clampType">${optionList(["CHUCK", "COLLET", "CENTER"].map((v) => ({ v })), "v", (x) => x.v, b.clampType)}</select></div>
      </div>`,
      async () => {
        await api("/api/v1/blanks", { method: "POST", body: {
          id: id || "", partId: $("f_partId").value, diameterMm: Number($("f_diameterMm").value),
          lengthMm: Number($("f_lengthMm").value), materialId: $("f_materialId").value, clampType: $("f_clampType").value
        } });
        toast("毛坯已保存");
      });
  };

  // ===== 工艺方案 =====
  const STEP_TYPES = ["ROUGH_TURNING", "FINISH_TURNING", "GROOVING", "THREADING", "DRILLING", "CUT_OFF"];

  function stepRow(s, tools, idx) {
    return `<tr data-step="${idx}">
      <td>${idx + 1}</td>
      <td><select data-k="type">${optionList(STEP_TYPES.map((v) => ({ v })), "v", (x) => x.v, s.type)}</select></td>
      <td><select data-k="toolId">${optionList(tools, "id", (t) => "T" + t.toolNumber, s.toolId)}</select></td>
      <td><input data-k="spindleRpm" type="number" value="${esc(s.spindleRpm)}"></td>
      <td><input data-k="feedMmPerRev" type="number" step="0.01" value="${esc(s.feedMmPerRev)}"></td>
      <td><input data-k="depthOfCutMm" type="number" step="0.1" value="${esc(s.depthOfCutMm)}"></td>
      <td><input data-k="cuttingSpeedMmPerMin" type="number" step="0.1" value="${esc(s.cuttingSpeedMmPerMin)}"></td>
      <td><input data-k="finishAllowanceMm" type="number" step="0.1" value="${esc(s.finishAllowanceMm)}"></td>
      <td><input type="checkbox" data-k="enabled" ${s.enabled !== false ? "checked" : ""}></td>
      <td><button class="small danger" onclick="removeStep(this)">×</button></td>
    </tr>`;
  }

  window.removeStep = function (btn) {
    const tr = btn.closest("tr");
    tr.parentNode.removeChild(tr);
    renumberSteps();
  };

  function renumberSteps() {
    document.querySelectorAll("#steps-body tr").forEach((tr, i) => {
      tr.children[0].textContent = i + 1;
    });
  }

  window.addStep = function () {
    const tools = S.config.tools;
    if (!tools.length) { toast("请先在刀具库添加刀具", true); return; }
    const s = { type: "ROUGH_TURNING", toolId: tools[0].id, spindleRpm: 1200, feedMmPerRev: 0.2, depthOfCutMm: 2, cuttingSpeedMmPerMin: 150, finishAllowanceMm: 0, enabled: true };
    const body = $("steps-body");
    const wrap = document.createElement("tbody");
    wrap.innerHTML = stepRow(s, tools, body.children.length);
    body.appendChild(wrap.firstElementChild);
    renumberSteps();
  };

  function viewPlans(c) {
    const rows = c.plans.map((p) => `<tr>
      <td>${esc(p.name)}</td>
      <td>${esc((findById(c.parts, p.partId) || {}).name || p.partId)}</td>
      <td>φ${p.stockDiameterMm} × ${p.stockLengthMm} mm</td>
      <td>${(p.steps || []).length} 道</td>
      <td class="muted">${esc(p.description)}</td>
      <td class="actions"><button class="small" onclick="openPlan('${jsStr(p.id)}')">编辑</button>
        <button class="small danger" onclick="delEntity('plans','${jsStr(p.id)}','工艺方案')">删除</button></td>
    </tr>`);
    return `<div class="panel"><h3>工艺方案 <button class="primary small" onclick="openPlan('')">新增方案</button></h3>
      ${table(["名称", "零件", "毛坯", "工序", "说明", ""], rows, "尚未配置工艺方案")}</div>`;
  }

  window.openPlan = function (id) {
    const p = findById(S.config.plans, id) || {
      name: "", partId: (S.config.parts[0] || {}).id || "", stockDiameterMm: 100, stockLengthMm: 50, description: "",
      steps: []
    };
    openModal(id ? "编辑工艺方案" : "新增工艺方案", `
      <div class="form-row">
        <div class="field"><label>方案名称</label><input id="f_name" value="${esc(p.name)}"></div>
        <div class="field"><label>零件</label><select id="f_partId">${optionList(S.config.parts, "id", (x) => x.name, p.partId)}</select></div>
      </div>
      <div class="grid3">
        <div class="field"><label>毛坯直径 (mm)</label><input id="f_stockDiameterMm" type="number" step="0.1" value="${esc(p.stockDiameterMm)}"></div>
        <div class="field"><label>毛坯长度 (mm)</label><input id="f_stockLengthMm" type="number" step="0.1" value="${esc(p.stockLengthMm)}"></div>
        <div class="field"><label>说明</label><input id="f_description" value="${esc(p.description)}"></div>
      </div>
      <div style="display:flex;justify-content:space-between;align-items:center;margin:16px 0 8px">
        <b style="font-size:13px">工序步骤</b><button class="small" onclick="addStep()">+ 添加工序</button>
      </div>
      <div class="steps-wrap"><table class="steps-table">
        <thead><tr><th>#</th><th>工序</th><th>刀具</th><th>转速</th><th>进给</th><th>切深</th><th>线速度</th><th>余量</th><th>启用</th><th></th></tr></thead>
        <tbody id="steps-body">${(p.steps || []).map((s, i) => stepRow(s, S.config.tools, i)).join("")}</tbody>
      </table></div>`,
      async () => {
        const steps = [];
        document.querySelectorAll("#steps-body tr").forEach((tr, i) => {
          const get = (k) => tr.querySelector(`[data-k="${k}"]`);
          steps.push({
            id: (p.steps && p.steps[i] && p.steps[i].id) || "",
            planId: id || "",
            orderIndex: i,
            type: get("type").value,
            toolId: get("toolId").value,
            cuttingSpeedMmPerMin: Number(get("cuttingSpeedMmPerMin").value),
            spindleRpm: Number(get("spindleRpm").value),
            feedMmPerRev: Number(get("feedMmPerRev").value),
            depthOfCutMm: Number(get("depthOfCutMm").value),
            finishAllowanceMm: Number(get("finishAllowanceMm").value),
            approachDistanceMm: 10, retractDistanceMm: 10, safetyDistanceMm: 10,
            enabled: get("enabled").checked, notes: ""
          });
        });
        await api("/api/v1/plans", { method: "POST", body: {
          id: id || "", name: $("f_name").value.trim(), partId: $("f_partId").value,
          stockDiameterMm: Number($("f_stockDiameterMm").value), stockLengthMm: Number($("f_stockLengthMm").value),
          description: $("f_description").value.trim(), steps
        } });
        toast("工艺方案已保存");
      });
  };

  // ===== 图纸 =====
  function viewDrawings(c) {
    const rows = c.drawings.map((d) => {
      const cor = correctionOf(d.id);
      return `<tr>
        <td>${esc(d.filename)}</td>
        <td>${d.layers} / ${d.entities}</td>
        <td class="muted">${d.dxfContent ? (d.dxfContent.length + " 字符") : "无内容（不可加工）"}</td>
        <td>${cor ? `×${cor.scale} / ${cor.unit}` : "未设置"}</td>
        <td class="actions">
          <button class="small" onclick="openDrawing('${jsStr(d.id)}')">编辑</button>
          <button class="small" onclick="openCorrection('${jsStr(d.id)}')">校正</button>
          <button class="small danger" onclick="delEntity('drawings','${jsStr(d.id)}','图纸')">删除</button>
        </td>
      </tr>`;
    });
    return `<div class="panel"><h3>图纸任务 <button class="primary small" onclick="openDrawing('')">新增图纸</button></h3>
      <div class="desc">图纸即平板端的“加工任务”。DXF 原文会同步到平板并缓存，离线也能继续加工。尺寸校正按图纸保存。</div>
      ${table(["文件名", "图层/实体", "DXF 内容", "校正", ""], rows, "尚未配置图纸")}</div>`;
  }

  window.openDrawing = function (id) {
    const d = findById(S.config.drawings, id) || { filename: "", layers: 0, entities: 0, source: "SERVER", dxfContent: "" };
    openModal(id ? "编辑图纸" : "新增图纸", `
      <div class="form-row">
        <div class="field"><label>文件名</label><input id="f_filename" value="${esc(d.filename)}"></div>
        <div class="field"><label>图层数</label><input id="f_layers" type="number" value="${esc(d.layers)}"></div>
        <div class="field"><label>实体数</label><input id="f_entities" type="number" value="${esc(d.entities)}"></div>
      </div>
      <div class="field" style="margin-bottom:10px"><label>从本机选择 .dxf 文件（会读取为文本）</label>
        <input id="f_file" type="file" accept=".dxf,text/plain"></div>
      <div class="field"><label>DXF 原文</label><textarea id="f_dxfContent" rows="12" placeholder="粘贴 DXF 文本，或选择文件自动填充">${esc(d.dxfContent)}</textarea></div>`,
      async () => {
        await api("/api/v1/drawings", { method: "POST", body: {
          id: id || "", filename: $("f_filename").value.trim(), layers: Number($("f_layers").value),
          entities: Number($("f_entities").value), source: d.source || "SERVER", dxfContent: $("f_dxfContent").value
        } });
        toast("图纸已保存");
      });
    $("f_file").addEventListener("change", (ev) => {
      const file = ev.target.files && ev.target.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        $("f_dxfContent").value = String(reader.result || "");
        if (!$("f_filename").value.trim()) $("f_filename").value = file.name;
        toast("已读取 " + file.name + "，请核对后保存");
      };
      reader.readAsText(file);
    });
  };

  window.openCorrection = function (drawingId) {
    const cor = correctionOf(drawingId) || { drawingId, scale: 1, offsetX: 0, offsetY: 0, rotationDeg: 0, mirrorX: false, mirrorY: false, unit: "MM" };
    openModal("图纸尺寸校正", `
      <div class="desc">平板端生成刀路前会自动套用此校正。适用于图纸单位/比例与实际工件不符的情况。</div>
      <div class="grid3">
        <div class="field"><label>缩放倍数</label><input id="f_scale" type="number" step="0.001" value="${esc(cor.scale)}"></div>
        <div class="field"><label>单位</label><select id="f_unit">${optionList(["MM", "INCH"].map((v) => ({ v })), "v", (x) => x.v, cor.unit)}</select></div>
        <div class="field"><label>旋转角度 (°)</label><input id="f_rotationDeg" type="number" step="0.1" value="${esc(cor.rotationDeg)}"></div>
      </div>
      <div class="grid2" style="margin-top:12px">
        <div class="field"><label>X 偏移 (mm)</label><input id="f_offsetX" type="number" step="0.1" value="${esc(cor.offsetX)}"></div>
        <div class="field"><label>Y 偏移 (mm)</label><input id="f_offsetY" type="number" step="0.1" value="${esc(cor.offsetY)}"></div>
      </div>
      <div class="form-row" style="margin-top:12px">
        <label class="check"><input type="checkbox" id="f_mirrorX" ${cor.mirrorX ? "checked" : ""}> X 镜像</label>
        <label class="check"><input type="checkbox" id="f_mirrorY" ${cor.mirrorY ? "checked" : ""}> Y 镜像</label>
      </div>`,
      async () => {
        await api("/api/v1/corrections", { method: "POST", body: {
          drawingId, scale: Number($("f_scale").value), offsetX: Number($("f_offsetX").value),
          offsetY: Number($("f_offsetY").value), rotationDeg: Number($("f_rotationDeg").value),
          mirrorX: $("f_mirrorX").checked, mirrorY: $("f_mirrorY").checked, unit: $("f_unit").value
        } });
        toast("尺寸校正已保存");
      });
  };

  // ===== 任务记录 =====
  function taskTable(list) {
    const rows = (list || []).map((t) => `<tr>
      <td>${esc(t.partName)}</td>
      <td class="mono">${esc(t.planId)}</td>
      <td>${tag(t.status, t.status === "SUCCESS")}</td>
      <td>${Number(t.durationSec).toFixed(1)} s</td>
      <td>${esc(t.operator)}</td>
      <td class="muted">${t.finishedAt ? new Date(t.finishedAt).toLocaleString() : "-"}</td>
    </tr>`);
    return table(["零件", "方案", "状态", "耗时", "操作员", "完成时间"], rows, "暂无任务记录");
  }

  function viewTasks() {
    return `<div class="panel"><h3>任务记录</h3>
      <div class="desc">由平板端在任务结束后自动上传；离线期间暂存本机，联网后补传。</div>
      ${taskTable(S.tasks)}</div>`;
  }

  // ===== 设备管理 =====
  const PERM_LABELS = [
    ["taskSelect", "任务选择"], ["previewStart", "预览下发"], ["monitor", "运行监控"],
    ["alarms", "报警状态"], ["advanced", "高级模式"], ["serverSync", "服务端同步"],
    ["drawings", "图纸管理"], ["cam", "CAM 刀路"], ["machining", "加工控制"],
    ["io", "IO 监控"], ["history", "任务历史"]
  ];

  function fmtTime(ts) {
    if (!ts) return "-";
    return new Date(ts).toLocaleString();
  }

  function onlineBadge(dev) {
    const online = !!dev.online;
    return `<span class="dot ${online ? "on" : "off"}" style="margin-right:6px"></span>${online ? "在线" : "离线"}`;
  }

  function permCheckboxes(id, perms) {
    return PERM_LABELS.map(([k, label]) => {
      const checked = perms[k] ? " checked" : "";
      return `<label class="perm-item"><input type="checkbox" data-perm="${k}"${checked}> ${esc(label)}</label>`;
    }).join("");
  }

  /** 设备/单位地区展示：省份与城市拼接，缺失时显示灰色「未填写」 */
  function regionText(province, city) {
    const p = String(province || "").trim();
    const c = String(city || "").trim();
    if (!p && !c) return `<span class="muted">未填写</span>`;
    return esc([p, c].filter(Boolean).join(" "));
  }

  /** 单位终端统计卡片：按单位汇总终端总数、在线数与最近活跃 */
  function orgSummaryHtml() {
    const list = (S.orgSummary || []).filter((g) => Number(g.deviceCount) >= 1);
    if (!list.length) {
      return `<div class="panel"><h3>单位终端统计</h3><div class="empty">暂无单位数据</div></div>`;
    }
    const rows = list.map((g) => {
      const total = Number(g.deviceCount) || 0;
      const online = Number(g.onlineCount) || 0;
      const offline = Math.max(0, total - online);
      return `<tr>
        <td>${esc(g.orgName)}</td>
        <td>${regionText(g.province, g.city)}</td>
        <td>${total}</td>
        <td><span class="org-badge on">在线 ${online}</span>${offline ? ` <span class="org-badge">离线 ${offline}</span>` : ""}</td>
        <td class="muted">${fmtTime(g.lastSeen)}</td>
      </tr>`;
    }).join("");
    return `<div class="panel"><h3>单位终端统计</h3>
      <div class="org-summary${list.length > 8 ? " scroll" : ""}">
        <table class="grid"><thead><tr><th>单位名称</th><th>地区</th><th>终端总数</th><th>在线</th><th>最近活跃</th></tr></thead>
        <tbody>${rows}</tbody></table>
      </div></div>`;
  }

  function viewDevices() {
    const rows = S.devices.map((d) => `
      <tr>
        <td><code>${esc(d.id)}</code>${d.serverAddress ? `<div class="muted mono">${esc(d.serverAddress)}</div>` : ""}</td>
        <td>${d.unitName ? esc(d.unitName) : `<span class="muted">未填写</span>`}</td>
        <td>${regionText(d.province, d.city)}</td>
        <td>${d.contactName ? esc(d.contactName) : `<span class="muted">未填写</span>`}${d.phone ? `<div class="muted mono">${esc(d.phone)}</div>` : ""}</td>
        <td>${esc(d.appVersion || "-")}</td>
        <td>${onlineBadge(d)}</td>
        <td>${fmtTime(d.lastSeen)}</td>
        <td>
          <button class="small" onclick='editDevice(${JSON.stringify(d)})'>修改</button>
          <button class="small" onclick='editPermissions(${JSON.stringify(d.id)}, ${JSON.stringify(d.permissions)})'>权限</button>
          <button class="small danger" onclick='removeDevice(${JSON.stringify(d.id)}, ${JSON.stringify(d.unitName)})'>注销</button>
        </td>
      </tr>`).join("");
    const table = rows
      ? `<table class="grid"><thead><tr><th>设备 / 服务器</th><th>单位</th><th>地区</th><th>联系人 / 电话</th><th>版本</th><th>状态</th><th>最近心跳</th><th>操作</th></tr></thead><tbody>${rows}</tbody></table>`
      : `<div class="desc">暂无已激活终端。客户端首次启动完成激活后会出现在这里。同一台设备只能激活一次；如需重新激活，请先「注销」。</div>`;
    const ver = S.appVersion;
    const verText = ver && ver.versionCode > 0
      ? `当前已发布：<b>v${esc(ver.versionName)}</b>（versionCode ${ver.versionCode}，${Math.round((ver.sizeBytes || 0) / 1024)} KB，${fmtTime(ver.publishedAt)}）${ver.changelog ? "<br>更新说明：" + esc(ver.changelog) : ""}`
      : "尚未发布客户端版本。发布后在线设备会收到「发现新版本」提示，确认后自动下载安装。";
    const cand = S.apkCandidate;
    const candHtml = cand && cand.found
      ? `<div class="hint-box" style="margin-bottom:10px">
           检测到最新构建：<b>v${esc(cand.versionName)}</b>（versionCode ${cand.versionCode}，${Math.round(cand.sizeBytes / 1024)} KB，${esc(cand.fileName)}，${fmtTime(cand.modifiedAt)}）
           <button class="primary" style="margin-left:12px" onclick="publishLatestApk()">一键发布此版本</button>
         </div>`
      : `<div class="desc" style="margin-bottom:10px">未检测到构建产物。请先在电脑上执行 gradlew assembleRelease（或 assembleDebug），服务端会自动识别 app/build/outputs/apk 下的最新 APK。</div>`;
    return `
      <div class="hint-box">已激活终端列表。设备首次启动需激活并填写单位与地区，便于按单位统计终端数量。</div>
      ${orgSummaryHtml()}
      <div class="panel"><h3>已激活终端</h3>${table}</div>
      <div class="panel"><h3>客户端版本发布（自动更新）</h3>
        <div class="desc" style="margin-bottom:10px">${verText}</div>
        ${candHtml}
        <div style="margin-top:8px"><label class="form-label">更新说明（可选）</label><input type="text" id="apk-changelog" class="input" placeholder="修复了…"></div>
        <details style="margin-top:10px">
          <summary class="desc" style="cursor:pointer">手动上传 APK（兜底，一般不用）</summary>
          <div class="row" style="align-items:flex-end; flex-wrap:wrap; gap:10px; margin-top:8px">
            <div><label class="form-label">APK 文件</label><input type="file" id="apk-file" accept=".apk"></div>
            <div style="width:120px"><label class="form-label">versionCode</label><input type="number" id="apk-code" class="input" value="${cand && cand.found ? cand.versionCode : ""}" placeholder="如 ${ver && ver.versionCode ? ver.versionCode + 1 : 2}"></div>
            <div style="width:160px"><label class="form-label">版本名</label><input type="text" id="apk-name" class="input" value="${cand && cand.found ? esc(cand.versionName) : ""}" placeholder="如 1.1.0"></div>
            <div><button onclick="publishApk()">上传并发布</button></div>
          </div>
        </details>
        <div class="desc" style="margin-top:8px">注意：发新版时需把 app/build.gradle.kts 里的 versionCode 加 1 再构建，客户端按 versionCode 判断是否为新版本。</div>
      </div>
      <div class="panel"><h3>管理员账号密码
        <button class="small" onclick="changeAdminPassword()">修改</button>
      </h3>
      <div class="desc">该账号密码同时用于：管理后台登录、客户端右上角「设置」入口。默认账号 <code>admin</code>，默认密码 <code>123456</code>，建议首次使用后修改。</div>
      </div>`;
  }

  window.editDevice = function (d) {
    openModal(`修改设备信息（${esc(d.id)}）`, `
      <label class="form-label">单位名称</label>
      <input type="text" id="edit-unit" class="input" value="${esc(d.unitName)}">
      <div class="grid2" style="margin-top:10px">
        <div class="field"><label>省份</label><input type="text" class="input" value="${esc(d.province)}" placeholder="未填写" readonly></div>
        <div class="field"><label>城市</label><input type="text" class="input" value="${esc(d.city)}" placeholder="未填写" readonly></div>
      </div>
      <label class="form-label" style="margin-top:10px">联系人</label>
      <input type="text" id="edit-contact" class="input" value="${esc(d.contactName)}">
      <label class="form-label" style="margin-top:10px">电话</label>
      <input type="text" id="edit-phone" class="input" value="${esc(d.phone)}">`, async () => {
      const unitName = $("edit-unit").value.trim();
      const contactName = $("edit-contact").value.trim();
      const phone = $("edit-phone").value.trim();
      if (!unitName || !contactName || !phone) throw new Error("单位、联系人、电话不能为空");
      await api(`/api/v1/devices/${encodeURIComponent(d.id)}`, {
        method: "PUT", body: { unitName, contactName, phone }
      });
      toast("设备信息已更新");
    });
  };

  window.removeDevice = async function (id, unitName) {
    if (!confirm(`确认注销设备 ${id}（${unitName}）？\n注销后该设备客户端将无法使用，需重新激活。`)) return;
    try {
      await api(`/api/v1/devices/${encodeURIComponent(id)}`, { method: "DELETE" });
      toast("设备已注销");
      await loadAll();
    } catch (e) { toast(e.message, true); }
  };

  /** 一键发布服务端扫描到的最新构建，无需手动选择 APK。 */
  window.publishLatestApk = async function () {
    const changelog = $("apk-changelog") ? $("apk-changelog").value.trim() : "";
    try {
      const data = await api("/api/v1/app-publish-latest", { method: "POST", body: { changelog } });
      toast(data.message || "发布成功");
      await loadAll();
    } catch (e) { toast(e.message, true); }
  };

  window.publishApk = async function () {
    const file = $("apk-file").files[0];
    const code = $("apk-code").value.trim();
    const name = $("apk-name").value.trim();
    const changelog = $("apk-changelog").value.trim();
    if (!file) { toast("请选择 APK 文件", true); return; }
    if (!code || !name) { toast("请填写 versionCode 与版本名", true); return; }
    const fd = new FormData();
    fd.append("apk", file);
    fd.append("versionCode", code);
    fd.append("versionName", name);
    fd.append("changelog", changelog);
    try {
      const res = await fetch("/api/v1/app-publish", {
        method: "POST",
        headers: { Authorization: "Bearer " + S.token },
        body: fd
      });
      const data = await res.json().catch(() => null);
      if (!res.ok || !data || !data.ok) throw new Error((data && data.message) || ("HTTP " + res.status));
      toast(data.message || "发布成功");
      await loadAll();
    } catch (e) { toast(e.message, true); }
  };

  window.editPermissions = function (id, perms) {
    openModal("功能权限", `<div class="perm-grid">${permCheckboxes(id, perms || {})}</div>`, async () => {
      const inputs = $("modal-body").querySelectorAll("input[data-perm]");
      const next = {};
      inputs.forEach((el) => { next[el.dataset.perm] = el.checked; });
      await api(`/api/v1/devices/${encodeURIComponent(id)}/permissions`, {
        method: "PUT", body: { permissions: next }
      });
      toast("权限已更新");
    });
  };

  window.changeAdminPassword = function () {
    openModal("修改管理员账号密码", `
      <label class="form-label">账号</label>
      <input type="text" id="new-admin-user" class="input" value="${esc(S.username || "admin")}">
      <label class="form-label" style="margin-top:10px">新密码</label>
      <input type="text" id="new-admin-pwd" class="input">
      <div class="desc">同时作用于管理后台登录与客户端「设置」入口。修改后需重新登录。</div>`, async () => {
      const pwd = $("new-admin-pwd").value.trim();
      const user = $("new-admin-user").value.trim();
      if (!pwd) throw new Error("密码不能为空");
      await api("/api/v1/admin-password", { method: "POST", body: { newPassword: pwd, newUsername: user } });
      toast("管理员账号密码已更新，请重新登录");
      setTimeout(() => logout("账号密码已修改，请重新登录"), 1200);
    });
  };

  // ===== 用户管理 =====
  const USER_ROLES = [
    { v: "SUPER_ADMIN", label: "超级管理员" },
    { v: "ADMIN", label: "管理员" },
    { v: "PROCESS_ENGINEER", label: "工艺员" },
    { v: "OPERATOR", label: "操作员" },
    { v: "QUALITY", label: "质检员" }
  ];

  function applyRoleDefaultPerms(role) {
    const set = new Set(ROLE_DEFAULT_PERMS[role] || []);
    $("modal-body").querySelectorAll("input[data-mperm]").forEach((el) => {
      el.checked = set.has(el.dataset.mperm);
    });
  }

  function findUser(id) {
    return (S.users || []).find((u) => String(u.id) === String(id));
  }

  function isSelfUser(u) {
    const me = S.user || {};
    if (!u) return false;
    if (me.id != null && u.id != null) return String(me.id) === String(u.id);
    return !!me.username && u.username === me.username;
  }

  function roleLabel(role) { return ROLE_LABELS[role] || role || "-"; }

  function userPermTags(u) {
    if (u.superAdmin || u.role === "SUPER_ADMIN") return `<span class="tag on">全部权限</span>`;
    const perms = u.permissions || [];
    if (!perms.length) return `<span class="muted">无</span>`;
    return `<div class="perm-tags">${perms.map((k) => `<span class="tag">${esc(MODULE_LABELS[k] || k)}</span>`).join("")}</div>`;
  }

  function viewUsers() {
    const rows = (S.users || []).map((u) => {
      const self = isSelfUser(u);
      const selfTip = self ? ` disabled title="不能操作当前登录账号"` : "";
      const actions =
        `<button class="small" onclick="openUserEdit('${jsStr(u.id)}')">编辑</button>
         <button class="small" onclick="resetUserPassword('${jsStr(u.id)}','${jsStr(u.username)}')">重置密码</button>
         <button class="small"${selfTip} onclick="toggleUserEnabled('${jsStr(u.id)}',${u.enabled ? "true" : "false"})">${u.enabled ? "禁用" : "启用"}</button>
         <button class="small danger"${selfTip} onclick="deleteUser('${jsStr(u.id)}','${jsStr(u.username)}')">删除</button>`;
      return `<tr>
        <td><code>${esc(u.username)}</code>${self ? ` <span class="user-flag">（当前）</span>` : ""}</td>
        <td>${esc(u.displayName || "-")}</td>
        <td>${esc(roleLabel(u.role))}</td>
        <td>${userPermTags(u)}</td>
        <td>${tag(u.enabled ? "启用" : "禁用", !!u.enabled)}</td>
        <td class="muted">${fmtTime(u.lastLoginAt)}</td>
        <td class="actions">${actions}</td>
      </tr>`;
    });
    return `
      <div class="hint-box">后台账号按模块授权，左侧菜单只显示已授权的模块。超级管理员拥有全部权限；不能禁用或删除当前登录账号，且需至少保留一个启用的超级管理员。</div>
      <div class="panel"><h3>后台用户 <button class="primary small" onclick="openUserCreate()">新建用户</button></h3>
        ${table(["用户名", "姓名", "角色", "权限", "状态", "最后登录", "操作"], rows,
          S.usersLoaded ? "暂无用户数据，点击「新建用户」添加。" : "正在加载用户列表…")}</div>`;
  }

  async function loadUsers() {
    try {
      const list = await api("/api/v1/users");
      S.users = Array.isArray(list) ? list : [];
      S.usersLoaded = true;
    } catch (e) {
      if (S.token) toast(e.message, true);
    }
    renderNav();
    if (S.tab === "users") render();
  }

  function userPermCheckboxes(perms) {
    const set = perms || [];
    return USER_MODULES.map((k) => {
      const checked = set.includes(k) ? " checked" : "";
      return `<label class="perm-item"><input type="checkbox" data-mperm="${k}"${checked}> ${esc(MODULE_LABELS[k])}</label>`;
    }).join("");
  }

  function collectUserPerms() {
    return Array.from($("modal-body").querySelectorAll("input[data-mperm]"))
      .filter((el) => el.checked)
      .map((el) => el.dataset.mperm);
  }

  window.openUserCreate = function () {
    openModal("新建用户", `
      <div class="form-row">
        <div class="field"><label>用户名（登录账号）</label><input id="u_username" autocomplete="off"></div>
        <div class="field"><label>姓名</label><input id="u_displayName"></div>
      </div>
      <div class="form-row">
        <div class="field"><label>初始密码</label><input id="u_password" type="password" autocomplete="new-password"></div>
        <div class="field"><label>角色</label><select id="u_role" onchange="applyRoleDefaultPerms(this.value)">${optionList(USER_ROLES, "v", (x) => x.label, "OPERATOR")}</select></div>
      </div>
      <div class="form-label" style="margin-top:4px">模块权限</div>
      <div class="perm-grid">${userPermCheckboxes(["overview", "device", "tasks"])}</div>
      <div class="desc" style="margin-top:10px">超级管理员自动拥有全部权限；切换角色会按岗位默认勾选模块，可手工微调。</div>`,
      async () => {
        const username = $("u_username").value.trim();
        const password = $("u_password").value;
        if (!username) throw new Error("用户名不能为空");
        if (!password) throw new Error("密码不能为空");
        await api("/api/v1/users", { method: "POST", body: {
          username,
          password,
          displayName: $("u_displayName").value.trim(),
          role: $("u_role").value,
          permissions: collectUserPerms()
        } });
        toast("用户已创建");
        await loadUsers();
      }, true);
  };

  window.openUserEdit = function (id) {
    const u = findUser(id);
    if (!u) { toast("用户不存在，请刷新列表", true); return; }
    const self = isSelfUser(u);
    openModal("编辑用户：" + u.username, `
      <div class="form-row">
        <div class="field"><label>用户名（不可修改）</label><input value="${esc(u.username)}" disabled></div>
        <div class="field"><label>姓名</label><input id="u_displayName" value="${esc(u.displayName || "")}"></div>
      </div>
      <div class="form-row">
        <div class="field"><label>角色${self ? "（不能修改自己的角色）" : ""}</label>
          <select id="u_role"${self ? " disabled" : ""} onchange="applyRoleDefaultPerms(this.value)">${optionList(USER_ROLES, "v", (x) => x.label, u.role)}</select></div>
        <div class="field"><label class="check" style="margin-top:20px">
          <input type="checkbox" id="u_enabled"${u.enabled ? " checked" : ""}${self ? " disabled" : ""}> 启用该账号</label></div>
      </div>
      <div class="form-label" style="margin-top:4px">模块权限</div>
      <div class="perm-grid">${userPermCheckboxes(u.permissions)}</div>`,
      async () => {
        await api("/api/v1/users/" + encodeURIComponent(id), { method: "PUT", body: {
          displayName: $("u_displayName").value.trim(),
          role: self ? u.role : $("u_role").value,
          permissions: collectUserPerms(),
          enabled: self ? !!u.enabled : $("u_enabled").checked
        } });
        toast("用户已更新");
        await loadUsers();
      }, true);
  };

  window.resetUserPassword = async function (id, username) {
    const pwd = prompt(`请输入「${username}」的新密码（至少 6 位）`);
    if (pwd == null) return;
    if (!pwd.trim()) { toast("密码不能为空", true); return; }
    try {
      const data = await api("/api/v1/users/" + encodeURIComponent(id) + "/reset-password", {
        method: "POST", body: { newPassword: pwd }
      });
      toast((data && data.message) || "密码已重置");
    } catch (e) { toast(e.message, true); }
  };

  window.toggleUserEnabled = async function (id, enabled) {
    const u = findUser(id);
    if (u && isSelfUser(u)) { toast("不能禁用当前登录账号", true); return; }
    try {
      await api("/api/v1/users/" + encodeURIComponent(id), { method: "PUT", body: { enabled: !enabled } });
      toast(enabled ? "用户已禁用" : "用户已启用");
      await loadUsers();
    } catch (e) { toast(e.message, true); }
  };

  window.deleteUser = async function (id, username) {
    const u = findUser(id);
    if (u && isSelfUser(u)) { toast("不能删除当前登录账号", true); return; }
    if (!confirm(`确认删除用户「${username}」？删除后该账号将无法登录。`)) return;
    try {
      const data = await api("/api/v1/users/" + encodeURIComponent(id), { method: "DELETE" });
      toast((data && data.message) || "用户已删除");
      await loadUsers();
    } catch (e) { toast(e.message, true); }
  };

  window.changeMyPassword = function () {
    openModal("修改密码", `
      <div class="field"><label>当前密码</label><input id="p_old" type="password" autocomplete="current-password"></div>
      <div class="field" style="margin-top:12px"><label>新密码</label><input id="p_new" type="password" autocomplete="new-password"></div>
      <div class="field" style="margin-top:12px"><label>确认新密码</label><input id="p_new2" type="password" autocomplete="new-password"></div>
      <div class="desc" style="margin-top:10px">修改成功后需要重新登录。</div>`,
      async () => {
        const oldPassword = $("p_old").value;
        const newPassword = $("p_new").value;
        if (!oldPassword || !newPassword) throw new Error("请填写当前密码与新密码");
        if (newPassword !== $("p_new2").value) throw new Error("两次输入的新密码不一致");
        await api("/api/v1/users/me/password", { method: "PUT", body: { oldPassword, newPassword } });
        toast("密码已修改，请重新登录");
        setTimeout(() => logout("密码已修改，请重新登录"), 900);
      }, true);
  };

  // ===== 删除 =====
  window.delEntity = async function (resource, id, label) {
    if (!confirm("确认删除该" + label + "？此操作会立即影响已同步的平板。")) return;
    try {
      await api("/api/v1/" + resource + "/" + encodeURIComponent(id), { method: "DELETE" });
      toast(label + "已删除");
      await loadAll();
    } catch (e) { toast(e.message, true); }
  };

  // ===== 弹窗 =====
  let modalSaveHandler = null;
  let modalReload = true;

  function openModal(title, bodyHtml, onSave, noReload) {
    $("modal-title").textContent = title;
    $("modal-body").innerHTML = bodyHtml;
    modalSaveHandler = onSave;
    modalReload = !noReload;
    $("modal-mask").classList.add("show");
  }

  function closeModal() {
    $("modal-mask").classList.remove("show");
    modalSaveHandler = null;
    modalReload = true;
  }

  $("modal-close").onclick = closeModal;
  $("modal-cancel").onclick = closeModal;
  $("modal-save").onclick = async () => {
    if (!modalSaveHandler) return;
    const btn = $("modal-save");
    btn.disabled = true;
    try {
      await modalSaveHandler();
      const reload = modalReload;
      closeModal();
      if (reload) await loadAll();
    } catch (e) {
      toast(e.message, true);
    } finally {
      btn.disabled = false;
    }
  };

  // ===== WebSocket =====
  function connectWs() {
    if (S.ws) { try { S.ws.close(); } catch (_) {} }
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${proto}://${location.host}/ws/status?token=${encodeURIComponent(S.token)}`);
    S.ws = ws;
    setWsBadge(false);
    ws.onopen = () => setWsBadge(true);
    ws.onclose = () => setWsBadge(false);
    ws.onerror = () => setWsBadge(false);
    ws.onmessage = (ev) => {
      try {
        S.status = JSON.parse(ev.data);
        if (S.tab === "overview") {
          const area = $("live-area");
          if (area) area.innerHTML = liveStatusHtml();
        }
      } catch (_) {}
    };
  }

  function setWsBadge(on) {
    $("ws-dot").className = "dot " + (on ? "on" : "off");
    $("ws-text").textContent = on ? "状态流已连接" : "状态流未连接";
  }

  // ===== 事件 =====
  $("login-btn").onclick = login;
  $("password").addEventListener("keydown", (e) => { if (e.key === "Enter") login(); });
  $("username").addEventListener("keydown", (e) => { if (e.key === "Enter") login(); });
  $("logout-btn").onclick = () => logout("已退出登录");
  $("logout-btn2").onclick = () => logout("已退出登录");
  if ($("change-pwd-btn")) $("change-pwd-btn").onclick = () => changeMyPassword();
  $("refresh-btn").onclick = async () => {
    try {
      await loadAll();
      if (S.tab === "users") await loadUsers();
      toast("已刷新");
    } catch (e) { toast(e.message, true); }
  };

  // 启动
  (async function boot() {
    try {
      const status = await api("/api/v1/status");
      S.status = status;
    } catch (_) {}
    if (S.token) {
      try {
        await api("/api/v1/auth/check");
        await loadMe();
        if (!S.token) return;
        enterApp();
        await loadAll();
        connectWs();
        if (S.tab === "devices") startDevicesPolling();
        return;
      } catch (_) {}
    }
    logout("");
  })();
})();
