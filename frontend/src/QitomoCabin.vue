<script setup lang="ts">
import type { Appearance } from './companion';
defineProps<{ appearance:Appearance; awake?:boolean; reaction?:boolean }>();
</script>
<template>
  <svg class="qitomo-cabin" :class="['cabin-theme-'+appearance.theme, { 'is-waving':reaction }]" viewBox="0 0 600 360" role="img" aria-label="栖团，瓷白外壳、薄荷眼睛的电子猫精灵，住在自己的小舱里">
    <rect class="cabin-wall" width="600" height="360" rx="24"/>
    <path class="cabin-lines" d="M0 278H600 M60 0V278 M540 0V278 M0 300H600"/>
    <rect class="cabin-window" x="361" y="35" width="143" height="137" rx="64"/>
    <circle class="cabin-moon" cx="441" cy="83" r="26"/>
    <path class="cabin-stars" d="M391 61v10m-5-5h10m69 59v12m-6-6h12M91 57v10m-5-5h10M509 210v10m-5-5h10"/>
    <path class="cabin-shelf" d="M86 251H214 M386 251H514 M245 324H355"/>
    <g v-for="(item,index) in appearance.slots" :key="index" :transform="`translate(${[112,440,286][index]} ${index===2?293:218})`" class="cabin-prop">
      <template v-if="item===7"><path d="M10 17v13m-13 0h26"/><circle class="cabin-moon" cx="10" cy="1" r="19"/></template>
      <template v-if="item===8"><path d="M0 12h28l-5 20H5z"/><path class="cabin-leaf" d="M14 12C-12 6-9-17 14-2c22-27 33 0 0 10z"/></template>
      <template v-if="item===9"><rect x="-13" y="-25" width="50" height="51" rx="4"/><path class="cabin-stars" d="M-3 9L7-10l16 9 7-14"/></template>
      <template v-if="item===10"><path d="M0 20V-5l12-23L24-5v25L12 12z"/><circle class="pet-eye" cx="12" cy="-3" r="5"/></template>
      <template v-if="item===11"><path class="pet-gold" d="M-3-14h30v12q0 17-15 17T-3-2zm15 29v14m-13 0h26M-3-8h-10q0 20 16 18m24-18h10q0 20-16 18"/></template>
      <template v-if="item===12"><path d="M-7-9l23-7 22 9v35l-22-7-23 7zM16-16v37"/></template>
    </g>
    <ellipse class="pet-shadow" cx="291" cy="291" rx="91" ry="13"/>
    <g class="pet-breathe">
      <path class="pet-tail" d="M350 254Q411 268 401 218"/><circle class="pet-eye" cx="401" cy="215" r="8"/>
      <ellipse class="pet-shell" cx="259" cy="282" rx="26" ry="12"/><ellipse class="pet-shell" cx="320" cy="282" rx="26" ry="12"/>
      <path class="pet-shell" d="M226 152L216 93q2-16 16-6l45 40 37 2 32-30q15-10 17 8l-4 53q25 29 22 64l-5 23q-9 37-84 37t-86-40q-10-52 20-82z"/>
      <path class="pet-ear" d="M230 137l-6-35 33 29M326 139l26-25-5 30"/>
      <rect class="pet-face" x="223" y="160" width="137" height="84" rx="35"/>
      <g class="pet-blink"><rect class="pet-eye" x="249" y="184" width="13" height="27" rx="6"/><rect class="pet-eye" x="319" y="184" width="13" height="27" rx="6"/></g>
      <path class="pet-smile" d="M279 215q12 12 24 0"/>
      <ellipse class="pet-shell pet-hand" cx="365" cy="247" rx="14" ry="22"/>
      <g v-if="appearance.outfit===1" class="pet-gold"><path d="M220 244q70 22 142 0l-5 17q-69 19-132-1zM329 261l-5 28 20-6 3-24z"/></g>
      <ellipse v-if="appearance.outfit===2" class="pet-ring" cx="292" cy="111" rx="65" ry="13"/>
      <g v-if="appearance.outfit===3"><path class="pet-strap" d="M242 154l98 104"/><rect class="pet-gold" x="318" y="238" width="42" height="35" rx="9"/></g>
      <g v-if="appearance.outfit===4" class="pet-glasses"><circle cx="256" cy="198" r="24"/><circle cx="326" cy="198" r="24"/><path d="M280 198h22"/></g>
      <g v-if="appearance.outfit===5" class="pet-headphones"><path d="M221 194v-31q0-42 71-42t71 42v31"/><rect x="209" y="180" width="18" height="39" rx="8"/><rect x="355" y="180" width="18" height="39" rx="8"/></g>
      <g v-if="appearance.outfit===6"><path class="pet-strap" d="M293 132V94"/><path class="pet-gold" d="M293 70l7 15 17 2-12 12 3 17-15-8-15 8 3-17-12-12 17-2z"/></g>
    </g>
  </svg>
</template>
<style scoped>
.qitomo-cabin{width:100%;display:block;max-height:460px}.cabin-wall{fill:var(--pet-wall)}.cabin-theme-1 .cabin-wall{fill:var(--pet-green)}.cabin-theme-2 .cabin-wall{fill:var(--pet-night)}.cabin-lines{fill:none;stroke:var(--pet-line);stroke-width:2}.cabin-window{fill:var(--pet-space);stroke:var(--pet-line);stroke-width:8}.cabin-moon,.pet-gold{fill:var(--pet-gold);stroke:var(--pet-gold);stroke-width:3}.cabin-stars{stroke:var(--pet-mint);stroke-width:2;fill:none}.cabin-shelf{stroke:var(--pet-line);stroke-width:8;stroke-linecap:round}.cabin-prop{fill:var(--pet-shell);stroke:var(--pet-ink);stroke-width:3;stroke-linejoin:round}.cabin-leaf{fill:var(--pet-mint)}.pet-shadow{fill:var(--pet-line);opacity:.45}.pet-shell{fill:var(--pet-shell);stroke:var(--pet-line);stroke-width:3}.pet-face,.pet-ear{fill:var(--pet-ink)}.pet-eye{fill:var(--pet-mint)}.pet-tail{stroke:var(--pet-shell);stroke-width:15;fill:none;stroke-linecap:round}.pet-smile{stroke:var(--pet-mint);stroke-width:3;fill:none;stroke-linecap:round}.pet-ring{fill:none;stroke:var(--pet-gold);stroke-width:7}.pet-strap{stroke:var(--pet-gold);stroke-width:7;fill:none}.pet-glasses{stroke:var(--pet-gold);stroke-width:4;fill:none}.pet-headphones{stroke:var(--pet-gold);stroke-width:9;fill:var(--pet-ink)}.pet-breathe{transform-origin:290px 286px;animation:qitomo-breathe 4s ease-in-out infinite}.pet-blink{transform-box:fill-box;transform-origin:center;animation:qitomo-blink 6s infinite}.pet-hand{transform-box:fill-box;transform-origin:50% 100%}.is-waving .pet-hand{animation:qitomo-wave .35s ease-in-out 4 alternate}.is-waving .pet-breathe{animation:none}
@keyframes qitomo-breathe{50%{transform:translateY(-3px) scaleY(1.015)}}@keyframes qitomo-blink{0%,43%,47%,100%{transform:scaleY(1)}45%{transform:scaleY(.1)}}@keyframes qitomo-wave{to{transform:rotate(-35deg)}}
@media(prefers-reduced-motion:reduce){.pet-breathe,.pet-blink,.is-waving .pet-hand{animation:none}}
</style>
