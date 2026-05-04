import { describe, it, expect } from 'vitest';
import { generateCode, formatBytes, chunkFile, reassembleChunks } from './utils.js';

describe('generateCode', () => {
  it('returns a 6-character alphanumeric string', () => {
    const code = generateCode();
    expect(code).toMatch(/^[a-z0-9]{6}$/);
  });

  it('returns different codes on repeated calls', () => {
    const codes = new Set(Array.from({ length: 20 }, () => generateCode()));
    expect(codes.size).toBeGreaterThan(1);
  });
});

describe('formatBytes', () => {
  it('formats bytes under 1 KB', () => {
    expect(formatBytes(500)).toBe('500 B');
  });

  it('formats kilobytes', () => {
    expect(formatBytes(2048)).toBe('2.0 KB');
  });

  it('formats megabytes', () => {
    expect(formatBytes(1.5 * 1024 * 1024)).toBe('1.5 MB');
  });

  it('formats gigabytes', () => {
    expect(formatBytes(2.25 * 1024 * 1024 * 1024)).toBe('2.3 GB');
  });
});

describe('chunkFile', () => {
  it('splits a file into chunks of the given size', async () => {
    const data = new Uint8Array(300).fill(1);
    const file = new File([data], 'test.bin', { type: 'application/octet-stream' });
    const chunks = await chunkFile(file, 100);
    expect(chunks).toHaveLength(3);
    expect(chunks[0].byteLength).toBe(100);
    expect(chunks[1].byteLength).toBe(100);
    expect(chunks[2].byteLength).toBe(100);
  });

  it('handles last chunk smaller than chunkSize', async () => {
    const data = new Uint8Array(250).fill(2);
    const file = new File([data], 'partial.bin');
    const chunks = await chunkFile(file, 100);
    expect(chunks).toHaveLength(3);
    expect(chunks[2].byteLength).toBe(50);
  });

  it('returns one chunk when file smaller than chunkSize', async () => {
    const data = new Uint8Array(50).fill(3);
    const file = new File([data], 'small.bin');
    const chunks = await chunkFile(file, 100);
    expect(chunks).toHaveLength(1);
    expect(chunks[0].byteLength).toBe(50);
  });
});

describe('reassembleChunks', () => {
  it('concatenates chunks into a Blob with correct size', () => {
    const a = new Uint8Array([1, 2, 3]).buffer;
    const b = new Uint8Array([4, 5]).buffer;
    const blob = reassembleChunks([a, b], { type: 'text/plain' });
    expect(blob).toBeInstanceOf(Blob);
    expect(blob.size).toBe(5);
    expect(blob.type).toBe('text/plain');
  });

  it('uses application/octet-stream when type is empty', () => {
    const chunk = new Uint8Array([0]).buffer;
    const blob = reassembleChunks([chunk], { type: '' });
    expect(blob.type).toBe('application/octet-stream');
  });
});
