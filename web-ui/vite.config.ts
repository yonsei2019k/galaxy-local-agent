import { defineConfig } from 'vite'

export default defineConfig({
  base: './',
  build: { target: 'chrome100' },
  plugins: [{
    name: 'offline-content-policy',
    apply: 'build',
    transformIndexHtml() {
      return [{
        tag: 'meta',
        attrs: {
          'http-equiv': 'Content-Security-Policy',
          content: "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'none'; base-uri 'none'; form-action 'none'",
        },
        injectTo: 'head-prepend',
      }]
    },
  }],
})
