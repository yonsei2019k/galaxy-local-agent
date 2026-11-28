import './fonts.css'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'
import './motion.css'
import './concept.css'

const root = document.getElementById('root')
if (root) createRoot(root).render(<App />)
