<template>
  <div>
    <svg-icon icon-class="question" @click="goto" />
  </div>
</template>

<script setup lang="ts">
const url = ref('https://github.com/HH112-ux/AgentHub/tree/main/docs');

function goto() {
  window.open(url.value);
}
</script>
