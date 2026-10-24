// Files stored with the notes, which notes name as in Obsidian (`![[Pasted image
// 20261023211500.png]]`, `[[report.pdf]]`). The app serves them at /attachments/<name>
// (EditorWebView's AttachmentPathHandler), looking the name up ignoring case and any folder part.

/**
 * The largest picture a paste or drop sends to the app: it crosses the bridge as base64, held in
 * memory on both sides (the app stores larger attachments that come by import or sync).
 */
export const MAX_IMAGE_BYTES = 25_000_000

/** File types a link may name that are attachments, not notes (the app's WikiLinks.ATTACHMENTS). */
const ATTACHMENT_EXTENSIONS = new Set([
  'png', 'jpg', 'jpeg', 'gif', 'bmp', 'svg', 'webp', 'avif', 'heic', 'tif', 'tiff', 'ico',
  'mp3', 'wav', 'm4a', 'ogg', 'oga', 'flac', 'aac', 'opus', '3gp',
  'mp4', 'webm', 'ogv', 'mov', 'mkv', 'avi',
  'pdf', 'zip', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'odt', 'ods', 'odp', 'epub',
  'canvas', 'base', 'excalidraw',
])

/** File types shown as pictures (the app's AttachmentStore.IMAGE_EXTENSIONS). */
const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'avif'])

/** The file name in a reference: no folder part. */
function baseName(reference: string): string {
  return reference.slice(Math.max(reference.lastIndexOf('/'), reference.lastIndexOf('\\')) + 1).trim()
}

/** Whether a reference (`pic.png`, `assets/pic.png`) names an image. */
export function isImageName(reference: string): boolean {
  return IMAGE_EXTENSIONS.has(extension(reference))
}

function extension(reference: string): string {
  const name = baseName(reference)
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
}

/** Whether a link's note name (`report.pdf`, `files/talk.m4a`) names an attachment rather than a note. */
export function isAttachmentName(reference: string): boolean {
  return ATTACHMENT_EXTENSIONS.has(extension(reference))
}

/** How the app names a stored attachment in the list it sends (NoteNames.attachments): its file name, lower-cased. */
export function attachmentKey(reference: string): string {
  return baseName(reference).toLowerCase()
}

/** Where the page loads the attachment a note mentions as `reference`. */
export function attachmentUrl(reference: string): string {
  return '/attachments/' + encodeURIComponent(baseName(reference))
}

/**
 * Whether an image's `src` is a path to a stored attachment rather than an address: no scheme
 * (`https:`, `data:`), not protocol-relative. `![](attachments/pic.png)` from an imported vault
 * is shown from the store by its file name.
 */
export function isAttachmentSrc(src: string): boolean {
  return src !== '' && !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(src) && !src.startsWith('//') && !src.startsWith('#')
}

/** The decoded file path of a local `src` (`my%20pic.png` -> `my pic.png`). */
export function decodeSrc(src: string): string {
  try {
    return decodeURIComponent(src.split(/[?#]/)[0])
  } catch {
    return src
  }
}

/** Obsidian's size in an image embed's alias: `![[pic.png|300]]` (width) or `|300x200`. */
export function embedSize(alias: string | null): {width: number; height?: number} | null {
  const m = alias && /^\s*(\d{1,5})(?:\s*x\s*(\d{1,5}))?\s*$/.exec(alias)
  if (!m) return null
  return {width: Number(m[1]), height: m[2] ? Number(m[2]) : undefined}
}
