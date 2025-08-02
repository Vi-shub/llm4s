<template>
  <v-app>
    <v-app-bar app color="primary" dark>
      <v-app-bar-title>Szork - Text Adventure</v-app-bar-title>
    </v-app-bar>

    <v-main>
      <router-view />
    </v-main>
  </v-app>
</template>

<script lang="ts">
import { defineComponent } from "vue";

export default defineComponent({
  name: "App",
});</script>