/**
 * The mobile's `hello.ua` follows the convention
 * "ZeroFrictionCast/0.1.0 (Android 15; Pixel 8)" — the model between the last
 * semicolon and the closing paren is what the desktop shows next to
 * "Connected to …" (pairing acceptance: the desktop recognizes the phone).
 * Anything unparsable falls back to a generic label; ua stays for logs.
 */
export function deviceNameFromUa(ua: string): string {
  const match = /\(([^()]*)\)\s*$/.exec(ua)
  if (match === null) return 'Android device'
  const parts = match[1].split(';')
  const name = parts[parts.length - 1].trim()
  return name.length > 0 ? name : 'Android device'
}
