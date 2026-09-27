/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Dark control-center palette (admin only — distinct from mobile app)
        page: '#0B1120', // page background
        surface: '#111A2E', // cards / panels
        elevated: '#1A2440', // elevated surfaces, inputs hover
        edge: '#1E293B', // borders
        ink: '#E2E8F0', // primary text
        ink2: '#94A3B8', // secondary text
        accent: '#6366F1', // indigo accent
        ok: '#10B981', // success
        warn: '#F59E0B', // warning
        bad: '#EF4444', // danger
        info: '#3B82F6', // info
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
      },
      boxShadow: {
        glow: '0 0 0 1px rgba(99, 102, 241, 0.25), 0 4px 24px -6px rgba(99, 102, 241, 0.35)',
      },
    },
  },
  plugins: [],
};
