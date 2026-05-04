/**
 * Generates a random 6-character alphanumeric peer code.
 * @returns {string}
 */
export function generateCode() {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  return Array.from(bytes).map(b => chars[b % chars.length]).join('');
}

/**
 * Formats a byte count into a human-readable string.
 * @param {number} bytes
 * @returns {string}
 */
export function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

/**
 * Reads a File and splits it into ArrayBuffer chunks.
 * @param {File} file
 * @param {number} chunkSize  - bytes per chunk (default 65536 = 64 KB)
 * @returns {Promise<ArrayBuffer[]>}
 */
export async function chunkFile(file, chunkSize = 65536) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = (event) => {
      const buffer = event.target.result;
      const chunks = [];
      let offset = 0;
      while (offset < buffer.byteLength) {
        chunks.push(buffer.slice(offset, offset + chunkSize));
        offset += chunkSize;
      }
      resolve(chunks);
    };
    reader.onerror = () => reject(new Error('Failed to read file'));
    reader.readAsArrayBuffer(file);
  });
}

/**
 * Reassembles ArrayBuffer chunks into a single Blob.
 * @param {ArrayBuffer[]} chunks
 * @param {{ type: string }} metadata - `type` is the MIME type; falls back to 'application/octet-stream' if empty/falsy
 * @returns {Blob}
 */
export function reassembleChunks(chunks, { type }) {
  const mimeType = type || 'application/octet-stream';
  return new Blob(chunks, { type: mimeType });
}
