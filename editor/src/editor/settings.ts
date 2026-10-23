// Editor settings, set by the app through the bridge.

export interface Theme {
  background: string
  surface: string
  surfaceVariant: string
  onBackground: string
  onSurfaceVariant: string
  primary: string
  onPrimary: string
  outline: string
  highlight: string
  error: string
  dark: boolean
}

export interface EditorSettings {
  /** Typing markdown syntax formats text on the fly. When off, typed symbols are literal. */
  markdownShortcuts: boolean
  /** Body text size in CSS pixels. */
  fontSize: number
  spellcheck: boolean
  /** Limit the text column to a comfortable reading width on wide screens. */
  readableLineLength: boolean
  theme: Theme | null
}

export const settings: EditorSettings = {
  markdownShortcuts: true,
  fontSize: 17,
  spellcheck: true,
  readableLineLength: true,
  theme: null,
}

/** Applies settings to the page: CSS variables for size and colours. */
export function applySettings(next: Partial<EditorSettings>): void {
  Object.assign(settings, next)
  const root = document.documentElement
  root.style.setProperty('--font-size', `${settings.fontSize}px`)
  root.classList.toggle('readable', settings.readableLineLength)
  const t = settings.theme
  if (t) {
    root.style.setProperty('--bg', t.background)
    root.style.setProperty('--surface', t.surface)
    root.style.setProperty('--surface-variant', t.surfaceVariant)
    root.style.setProperty('--fg', t.onBackground)
    root.style.setProperty('--fg-muted', t.onSurfaceVariant)
    root.style.setProperty('--accent', t.primary)
    root.style.setProperty('--on-accent', t.onPrimary)
    root.style.setProperty('--outline', t.outline)
    root.style.setProperty('--highlight', t.highlight)
    root.style.setProperty('--error', t.error)
    root.classList.toggle('dark', t.dark)
    root.style.colorScheme = t.dark ? 'dark' : 'light'
  }
}
