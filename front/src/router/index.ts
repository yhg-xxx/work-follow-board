import { createRouter, createWebHistory } from 'vue-router'
import TaskList from '../views/TaskList.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [{ path: '/', name: 'tasks', component: TaskList }],
})

export default router
