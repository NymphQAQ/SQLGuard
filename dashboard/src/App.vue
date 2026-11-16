<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  Activity, ArrowDownUp, ArrowRight, Bell, ChevronRight, Clock3, Database, Fingerprint,
  Gauge, LayoutDashboard, ListFilter, LockKeyhole, RefreshCw, Search, ShieldCheck, TriangleAlert, X,
} from '@lucide/vue'
import LatencyTrend from './LatencyTrend.vue'
import SqlTable from './SqlTable.vue'

const sections = [
  { id: 'overview', title: '查询概览', icon: LayoutDashboard },
  { id: 'fingerprints', title: 'SQL 指纹', icon: Fingerprint },
  { id: 'alerts', title: '风险告警', icon: Bell },
]
const section = ref(readSection())
const minutes = ref(import.meta.env.MODE === 'demo' ? 1 : 5)
const overview = ref(null)
const fingerprints = ref([])
const query = ref('')
const slowOnly = ref(false)
const alertFilter = ref('all')
const order = ref('recent')
const busy = ref(false)
const error = ref('')
const locked = ref(false)
const updated = ref(null)
const token = ref(sessionStorage.getItem('sqlguard-token') || (import.meta.env.MODE === 'demo' ? 'sqlguard-demo' : ''))
const enteredToken = ref('')
const drawer = ref()
const selectedId = ref('')
const selected = ref(null)
const detailError = ref('')
const highAlerts = computed(() => overview.value?.alerts.filter(item => item.risk.severity === 'HIGH').length ?? 0)
const alerts = computed(() => overview.value?.alerts.filter(item => alertFilter.value === 'all'
  || item.risk.severity === alertFilter.value) ?? [])
const activeTitle = computed(() => sections.find(item => item.id === section.value)?.title)
const integers = new Intl.NumberFormat('zh-CN')
const clock = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
const metrics = computed(() => {
  const data = overview.value?.metrics
  return [
    { label: 'SQL 执行次数', value: data ? integers.format(data.executions) : '—', icon: Database, color: 'blue', note: '本次启动以来' },
    { label: 'P95 执行耗时', value: data ? data.p95Ms.toFixed(2) : '—', unit: 'ms', icon: Activity, color: 'teal', note: '最近最多 512 次执行' },
    { label: '慢 SQL 次数', value: data ? integers.format(data.slowExecutions) : '—', icon: Clock3, color: 'amber', note: overview.value ? `耗时 ≥ ${overview.value.health.slowThresholdMs} ms` : '按耗时阈值统计' },
    { label: '累计风险告警', value: data ? integers.format(data.alerts) : '—', icon: TriangleAlert, color: 'red', note: '同指纹、同规则去重' },
  ]
})
const descriptions = {
  overview: '跟踪 MyBatis 执行耗时、慢查询与风险。',
  fingerprints: '按归一化 SQL 聚合执行次数与耗时，查看单条指纹的风险和执行计划。',
  alerts: '查看最近 100 条告警，以及每种风险的处理建议。',
}
const planMessages = {
  DISABLED: '当前实例尚未启用 EXPLAIN。',
  INELIGIBLE: '当前 SQL 样本超出执行计划采集范围。',
  PENDING: '正在等待执行计划。',
  UNSUPPORTED: '当前数据源不支持 MySQL 执行计划。',
  FAILED: '执行计划获取失败，可检查数据库权限和连接。',
  BUSY: '执行计划队列繁忙，等待后续样本。',
}
let poll
let refreshController
let detailController
let debounce

function readSection() {
  const id = location.hash.slice(1)
  return sections.some(item => item.id === id) ? id : 'overview'
}

function routeChanged() {
  section.value = readSection()
}

function navigate(id) {
  if (id === 'fingerprints') {
    slowOnly.value = false
    order.value = 'recent'
  }
  location.hash = id
}

function showSlowSql() {
  slowOnly.value = true
  order.value = 'latency'
  if (section.value === 'fingerprints') refresh()
  else location.hash = 'fingerprints'
}

function formatTime(value) {
  return value ? clock.format(new Date(value)) : '—'
}

function relative(value) {
  const seconds = Math.max(0, Math.floor(((updated.value || Date.now()) - new Date(value)) / 1000))
  if (seconds < 60) return seconds < 5 ? '刚刚' : `${seconds} 秒前`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  return formatTime(value)
}

async function request(path, signal) {
  const response = await fetch(`${import.meta.env.BASE_URL}api${path}`, {
    headers: token.value ? { Authorization: `Bearer ${token.value}` } : {},
    signal,
    cache: 'no-store',
  })
  if (!response.ok) throw Object.assign(new Error('SQLGuard API'), { status: response.status })
  return response.json()
}

async function refresh() {
  refreshController?.abort()
  const controller = new AbortController()
  refreshController = controller
  busy.value = true
  const filter = new URLSearchParams({ q: query.value, slow: slowOnly.value, order: order.value })
  try {
    const data = await request(`/overview?minutes=${minutes.value}`, controller.signal)
    if (controller.signal.aborted) return
    overview.value = data
    updated.value = Date.now()
    locked.value = false
    error.value = ''
    if (section.value === 'fingerprints') {
      const result = await request(`/fingerprints?${filter}`, controller.signal)
      if (!controller.signal.aborted) fingerprints.value = result
    }
    if (selectedId.value) loadDetail(selectedId.value, false)
  } catch (failure) {
    if (controller.signal.aborted) return
    if (failure.status === 401) {
      locked.value = true
      overview.value = null
      closeDrawer()
    } else {
      error.value = '暂时无法连接 SQLGuard，点击刷新后重试。'
    }
  } finally {
    if (controller === refreshController) busy.value = false
  }
}

async function unlock() {
  token.value = enteredToken.value.trim()
  if (!token.value) return
  await refresh()
  if (locked.value && !error.value) {
    error.value = '管理令牌无效，请重新输入。'
  } else if (!error.value) {
    sessionStorage.setItem('sqlguard-token', token.value)
    enteredToken.value = ''
  }
}

async function loadDetail(id, open = true) {
  detailController?.abort()
  const controller = new AbortController()
  detailController = controller
  selectedId.value = id
  if (open) {
    selected.value = null
    detailError.value = ''
    await nextTick()
    if (!drawer.value.open) drawer.value.showModal()
  }
  try {
    const detail = await request(`/fingerprints/${encodeURIComponent(id)}`, controller.signal)
    if (!controller.signal.aborted) {
      selected.value = detail
      detailError.value = ''
    }
  } catch (failure) {
    if (!controller.signal.aborted) {
      detailError.value = failure.status === 404 ? '该指纹已从本实例的缓存中移除。' : '暂时无法载入 SQL 详情。'
    }
  }
}

function closeDrawer() {
  drawer.value?.close()
  selectedId.value = ''
  detailController?.abort()
}

watch([section, minutes, slowOnly, order], () => { if (!locked.value) refresh() })
watch(query, () => {
  clearTimeout(debounce)
  debounce = setTimeout(refresh, 240)
})
onMounted(() => {
  window.addEventListener('hashchange', routeChanged)
  refresh()
  poll = setInterval(() => {
    if (!document.hidden && !locked.value && !busy.value) refresh()
  }, 5000)
})
onUnmounted(() => {
  window.removeEventListener('hashchange', routeChanged)
  clearInterval(poll)
  clearTimeout(debounce)
  refreshController?.abort()
  detailController?.abort()
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#overview" aria-label="SQLGuard 查询概览">
        <span class="brand-mark"><ShieldCheck :size="25" :stroke-width="1.9" /></span>
        <span><strong>SQL<span>Guard</span></strong><small>SQL 审计与性能诊断</small></span>
      </a>
      <nav aria-label="管理台导航">
        <h2>工作台</h2>
        <button v-for="item in sections" :key="item.id" class="nav-link"
          :class="{ active: section === item.id }" :aria-current="section === item.id ? 'page' : undefined"
          @click="navigate(item.id)">
          <component :is="item.icon" :size="18" :stroke-width="1.8" />
          <span>{{ item.title }}</span>
          <b v-if="item.id === 'alerts' && highAlerts">{{ highAlerts }}</b>
        </button>
      </nav>
      <div class="sidebar-footer">
        <div class="instance-icon"><Database :size="17" /></div>
        <div><strong>当前实例</strong><small><i :class="{ offline: !overview || error }"></i>{{ overview && !error ? '采集中' : '等待连接' }}</small></div>
      </div>
    </aside>

    <div class="work-area">
      <header class="topbar">
        <div class="breadcrumb"><LayoutDashboard :size="16" /> <span>工作台</span><ChevronRight :size="13" /> <strong>{{ activeTitle }}</strong></div>
        <div class="top-actions">
          <span class="live-status" :class="{ offline: !overview || error }"><i></i>{{ overview && !error ? '实时采集' : '待连接' }}</span>
          <span v-if="updated" class="last-sync" :title="`上次更新 ${formatTime(updated)}`">{{ formatTime(updated) }}</span>
          <button class="button refresh" :disabled="busy" @click="refresh">
            <RefreshCw :size="15" :class="{ spinning: busy }" />
            <span>刷新</span>
          </button>
        </div>
      </header>

      <main>
        <section v-if="locked" class="panel sign-in">
          <div class="metric-icon blue"><LockKeyhole :size="22" /></div>
          <h1>连接 SQLGuard</h1>
          <p>输入此实例的管理令牌，查看 SQL 审计数据。</p>
          <form @submit.prevent="unlock">
            <label for="token">管理令牌</label>
            <input id="token" v-model="enteredToken" type="password" required autocomplete="current-password" />
            <button class="button primary" :disabled="busy || !enteredToken.trim()">连接</button>
          </form>
          <p v-if="error" role="alert">{{ error }}</p>
        </section>

        <template v-else>
          <div class="page-heading">
            <div><h1>{{ activeTitle }}</h1><p>{{ descriptions[section] }}</p></div>
            <span v-if="section === 'overview' && overview" class="section-detail">
              <Fingerprint :size="16" /> {{ integers.format(overview.metrics.fingerprints) }} 个活跃指纹
            </span>
          </div>
          <p v-if="error" class="notice" role="alert"><TriangleAlert :size="17" /> {{ error }}</p>

          <template v-if="section === 'overview'">
            <section class="metric-grid" aria-label="运行指标">
              <article v-for="metric in metrics" :key="metric.label" class="panel metric">
                <div class="metric-head">
                  <span class="metric-icon" :class="metric.color"><component :is="metric.icon" :size="19" :stroke-width="1.9" /></span>
                  <h2>{{ metric.label }}</h2>
                </div>
                <div class="metric-value">{{ metric.value }} <span v-if="metric.unit">{{ metric.unit }}</span></div>
                <p>{{ metric.note }}</p>
              </article>
            </section>

            <div class="analysis-grid">
              <section class="panel trend-panel">
                <div class="panel-heading trend-heading">
                  <div><h2>执行耗时趋势</h2><p>每 10 秒汇总 · 毫秒</p></div>
                  <div class="segmented" role="group" aria-label="趋势时间范围">
                    <button v-for="span in [1, 5, 15, 30]" :key="span" :class="{ chosen: minutes === span }"
                      :aria-pressed="minutes === span" @click="minutes = span">{{ span }} 分钟</button>
                  </div>
                </div>
                <div class="legend"><span><i class="series-p95"></i>P95 耗时</span><span><i class="series-avg"></i>平均耗时</span></div>
                <LatencyTrend :points="overview?.trend ?? []" />
              </section>

              <section class="panel alert-panel">
                <div class="panel-heading"><div><h2>最近风险</h2><p>按触发时间排序</p></div><span class="panel-icon"><Bell :size="19" /></span></div>
                <div v-if="!overview?.alerts.length" class="empty-state alerts-empty"><ShieldCheck :size="25" /><strong>暂无风险告警</strong></div>
                <ul v-else class="recent-alerts">
                  <li v-for="item in overview.alerts.slice(0, 4)" :key="item.id">
                    <button @click="loadDetail(item.fingerprint)">
                      <span class="risk-icon" :class="{ high: item.risk.severity === 'HIGH' }"><TriangleAlert :size="16" :stroke-width="1.9" /></span>
                      <span class="recent-text"><strong>{{ item.risk.title }}</strong><small>{{ item.mapper?.split('.').at(-1) || item.fingerprint }}</small></span>
                      <time :datetime="item.time">{{ relative(item.time) }}</time>
                    </button>
                  </li>
                </ul>
                <button class="text-button alert-more" @click="navigate('alerts')">查看所有告警 <ArrowRight :size="15" /></button>
              </section>
            </div>

            <section class="panel sql-panel">
              <div class="panel-heading">
                <div><h2>SQL 耗时排行</h2><p>按单次执行峰值排序</p></div>
                <button class="button" @click="showSlowSql"><ListFilter :size="15" /> 慢 SQL</button>
              </div>
              <SqlTable :rows="overview?.fingerprints ?? []" @inspect="loadDetail" />
            </section>
          </template>

          <section v-else-if="section === 'fingerprints'" class="panel sql-panel">
            <div class="panel-heading list-toolbar">
              <label class="search"><Search :size="17" /><input v-model.trim="query" type="search" maxlength="200"
                aria-label="搜索 SQL 指纹" placeholder="搜索 SQL、Mapper 或指纹" /></label>
              <div class="list-actions">
                <button class="button" :class="{ selected: slowOnly }" :aria-pressed="slowOnly" @click="slowOnly = !slowOnly">
                  <ListFilter :size="15" /> 慢 SQL
                </button>
                <label class="sort"><ArrowDownUp :size="15" /><select v-model="order" aria-label="排序">
                  <option value="recent">最近执行</option><option value="latency">峰值耗时</option><option value="count">执行次数</option>
                </select></label>
              </div>
            </div>
            <SqlTable :rows="fingerprints" @inspect="loadDetail" />
          </section>

          <section v-else class="panel">
            <div class="panel-heading list-toolbar">
              <h2>告警记录 <span class="muted">({{ alerts.length }})</span></h2>
              <div class="segmented" role="group" aria-label="按风险等级筛选">
                <button v-for="level in [['all', '全部'], ['HIGH', '高风险'], ['MEDIUM', '中风险']]" :key="level[0]"
                  :class="{ chosen: alertFilter === level[0] }" :aria-pressed="alertFilter === level[0]"
                  @click="alertFilter = level[0]">{{ level[1] }}</button>
              </div>
            </div>
            <div v-if="!alerts.length" class="empty-state"><ShieldCheck :size="25" /><strong>暂无匹配的告警</strong></div>
            <ul v-else class="alert-list">
              <li v-for="item in alerts" :key="item.id">
                <span class="risk-icon" :class="{ high: item.risk.severity === 'HIGH' }"><TriangleAlert :size="18" /></span>
                <div class="alert-body">
                  <div><strong>{{ item.risk.title }}</strong><span class="severity" :class="{ high: item.risk.severity === 'HIGH' }">{{ item.risk.severity === 'HIGH' ? '高风险' : '中风险' }}</span></div>
                  <p>{{ item.risk.detail }} {{ item.risk.advice }}</p>
                  <code>{{ item.sql }}</code>
                </div>
                <button class="text-button" @click="loadDetail(item.fingerprint)"><time :datetime="item.time">{{ formatTime(item.time) }}</time><ChevronRight :size="16" /></button>
              </li>
            </ul>
          </section>

          <footer v-if="overview" class="footer">
            <span><Gauge :size="15" /> 队列 {{ overview.health.queueDepth }} / {{ overview.health.queueCapacity }}</span>
            <span>丢弃 {{ integers.format(overview.health.droppedSamples) }}</span>
            <span>解析失败 {{ integers.format(overview.health.parserFailures) }}</span>
            <span>上报失败 {{ integers.format(overview.health.deliveryFailures + overview.health.droppedReports) }}</span>
          </footer>
        </template>
      </main>
    </div>

    <dialog ref="drawer" class="detail-drawer" aria-labelledby="detail-title" @close="closeDrawer"
      @click.self="event => { if (event.clientX < drawer.getBoundingClientRect().left) closeDrawer() }">
      <div class="drawer-head"><div><h2 id="detail-title">SQL 指纹详情</h2><code>{{ selectedId }}</code></div>
        <button class="icon-button" aria-label="关闭详情" @click="closeDrawer"><X :size="21" /></button></div>
      <div v-if="detailError" class="notice" role="status">{{ detailError }}</div>
      <p v-else-if="!selected" class="drawer-loading" role="status">正在读取详情…</p>
      <template v-else>
        <section class="drawer-section">
          <div class="detail-heading"><h3>脱敏 SQL</h3><span class="operation" :class="{ write: selected.fingerprint.operation !== 'SELECT' }">{{ selected.fingerprint.operation }}</span></div>
          <pre><code>{{ selected.fingerprint.sql }}</code></pre>
          <p class="mapper-name">{{ selected.fingerprint.mapper }}</p>
          <p v-if="!selected.fingerprint.parsed" class="notice">该 SQL 语法未能解析，耗时统计仍可查看。</p>
        </section>
        <section class="detail-metrics" aria-label="指纹指标">
          <div><span>执行次数</span><b>{{ integers.format(selected.fingerprint.executions) }}</b></div>
          <div><span>P95 耗时</span><b>{{ selected.fingerprint.p95Ms.toFixed(2) }} <small>ms</small></b></div>
          <div><span>慢 SQL</span><b>{{ integers.format(selected.fingerprint.slowExecutions) }}</b></div>
        </section>
          <p v-if="selected.fingerprint.batchEnqueues" class="drawer-loading">
            批处理入队 {{ integers.format(selected.fingerprint.batchEnqueues) }} 次
          </p>
        <section class="drawer-section">
          <h3>已发现的风险</h3>
          <p v-if="!selected.fingerprint.risks.length" class="muted">暂无风险记录。</p>
          <article v-for="risk in selected.fingerprint.risks" :key="risk.code" class="detail-risk">
            <strong>{{ risk.title }}</strong><span class="severity" :class="{ high: risk.severity === 'HIGH' }">{{ risk.severity === 'HIGH' ? '高风险' : '中风险' }}</span>
            <p>{{ risk.detail }} {{ risk.advice }}</p>
          </article>
        </section>
        <section class="drawer-section">
          <h3>MySQL 执行计划</h3>
          <p v-if="selected.plan.status !== 'READY'" class="muted">{{ planMessages[selected.plan.status] }}</p>
          <template v-else>
            <div class="table-scroll plan-table">
              <table>
                <thead><tr><th>表</th><th>访问类型</th><th>候选索引</th><th>使用索引</th><th>预估行数</th><th>额外信息</th></tr></thead>
                <tbody><tr v-for="(step, index) in selected.plan.steps" :key="index">
                  <td>{{ step.table || '—' }}</td><td>{{ step.accessType || '—' }}</td>
                  <td>{{ step.possibleKeys || '—' }}</td><td>{{ step.selectedKey || '—' }}</td>
                  <td>{{ integers.format(step.examinedRows) }}</td><td>{{ step.extra || '—' }}</td>
                </tr></tbody>
              </table>
            </div>
            <h4>索引优化建议</h4>
            <ul v-if="selected.plan.advice.length" class="advice-list"><li v-for="tip in selected.plan.advice" :key="tip">{{ tip }}</li></ul>
            <p v-else class="muted">当前计划未触发索引建议。</p>
          </template>
        </section>
      </template>
    </dialog>
  </div>
</template>
