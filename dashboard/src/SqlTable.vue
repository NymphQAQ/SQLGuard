<script setup>
import { ChevronRight, Code2 } from '@lucide/vue'

defineProps({ rows: { type: Array, required: true } })
defineEmits(['inspect'])
const ms = value => `${value.toFixed(2)} ms`
const count = new Intl.NumberFormat('zh-CN')
</script>

<template>
  <div v-if="!rows.length" class="empty-state">
    <Code2 :size="25" />
    <strong>暂无匹配的 SQL 指纹</strong>
  </div>
  <div v-else class="table-scroll">
    <table>
      <thead>
        <tr>
          <th scope="col" class="sql-column">SQL 指纹</th>
          <th scope="col">类型</th>
          <th scope="col" class="numeric">执行次数</th>
          <th scope="col" class="numeric">均值</th>
          <th scope="col" class="numeric">P95</th>
          <th scope="col" class="numeric">峰值</th>
          <th scope="col"><span class="sr-only">详情</span></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.id">
          <td class="sql-column">
            <button class="sql-link" :title="row.sql" @click="$emit('inspect', row.id)">
              <code>{{ row.sql || 'SQL 文本为空' }}</code>
              <small>{{ row.mapper?.split('.').at(-1) || row.id }}</small>
            </button>
          </td>
          <td><span class="operation" :class="{ write: row.operation !== 'SELECT' }">{{ row.operation }}</span></td>
          <td class="numeric">{{ count.format(row.executions) }}</td>
          <td class="numeric">{{ ms(row.averageMs) }}</td>
          <td class="numeric">{{ ms(row.p95Ms) }}</td>
          <td class="numeric" :class="{ slow: row.slowExecutions }">{{ ms(row.maxMs) }}</td>
          <td class="open-cell">
            <button class="icon-button" :aria-label="`查看指纹 ${row.id}`" @click="$emit('inspect', row.id)">
              <ChevronRight :size="16" />
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
