import { boot } from './app.js'

boot().catch((error) => {
  const root = document.getElementById('app')
  if (root) root.textContent = `Could not start the editor: ${error.message}`
})
