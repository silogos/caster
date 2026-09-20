// Structured logging per AGENTS.md: level-tagged, timestamped lines.
// Never log microphone content, audio samples, screen frames, pairing
// credentials (session secret / HMAC), or unnecessary personal information.

type Level = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'

function emit(level: Level, scope: string, message: string, details?: Record<string, unknown>): void {
  const line = `${new Date().toISOString()} ${level} [${scope}] ${message}`
  const fn = level === 'ERROR' ? console.error : level === 'WARN' ? console.warn : console.log
  if (details === undefined) {
    fn(line)
  } else {
    fn(line, JSON.stringify(details))
  }
}

export const logger = {
  debug: (scope: string, message: string, details?: Record<string, unknown>) => emit('DEBUG', scope, message, details),
  info: (scope: string, message: string, details?: Record<string, unknown>) => emit('INFO', scope, message, details),
  warn: (scope: string, message: string, details?: Record<string, unknown>) => emit('WARN', scope, message, details),
  error: (scope: string, message: string, details?: Record<string, unknown>) => emit('ERROR', scope, message, details)
}
