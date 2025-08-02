import { createRouter, createWebHistory } from "vue-router";
import GameView from "@/layouts/GameView.vue";

const routes = [
  {
    path: "/",
    name: "Game",
    component: GameView,
  },
];

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
});

export default router;