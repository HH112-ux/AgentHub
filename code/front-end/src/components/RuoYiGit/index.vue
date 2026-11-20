<template>
  <div>
    <svg-icon icon-class="github" @click="goto" />
  </div>
</template>

<script setup lang="ts">
const url = ref('https://github.com/HH112-ux/AgentHub');

function goto() {
  window.open(url.value);
}
</script>
