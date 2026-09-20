import QRCode from 'qrcode'

/**
 * Render text as a QR PNG data URL.
 * pairing.md specifies QR type 2/byte, error correction M.
 */
export async function toQrDataUrl(text: string): Promise<string> {
  return QRCode.toDataURL(text, { errorCorrectionLevel: 'M' })
}
