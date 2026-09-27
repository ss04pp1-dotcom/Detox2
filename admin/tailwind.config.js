/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Dark control-center palette (admin only — distinct from mobile app)
        page: '#060B16', // page background
        surface: '#0E1728', // cards / panels
        elevated: '#17233B', // elevated surfaces, inputs hover
        edge: '#243653', // borders
        ink: '#F1F5F9', // primary text
        ink2: '#94A3B8', // secondary text
        accent: '#6EA8FF', // indigo accent
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
        glow: '0 0 0 1px rgba(110, 168, 255, 0.24), 0 12px 36px -12px rgba(54, 150, 255, 0.38)',
      },
    },
  },
  plugins: [],
};
