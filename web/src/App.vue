<script setup lang="ts">
// 应用根组件：只负责挂载路由出口。全局外壳（导航/页脚/语言切换）在 layouts 里，登录注册外壳在 AuthShell.vue。
import { RouterView } from 'vue-router'
</script>

<template>
  <RouterView />
</template>
