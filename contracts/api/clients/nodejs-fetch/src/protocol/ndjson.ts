// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Splits a byte stream into lines, decoding UTF-8 across chunk boundaries so a multi-byte character
 * straddling two network reads is not mangled. Yields every line, blank ones included; the caller
 * skips what it does not want. A trailing `\r` is stripped so CRLF streams read the same as LF.
 */
export async function* lines(chunks: AsyncIterable<Uint8Array>): AsyncGenerator<string, void, undefined> {
  const decoder = new TextDecoder('utf-8')
  let pending = ''
  for await (const chunk of chunks) {
    pending += decoder.decode(chunk, { stream: true })
    let newline = pending.indexOf('\n')
    while (newline !== -1) {
      yield stripCarriageReturn(pending.slice(0, newline))
      pending = pending.slice(newline + 1)
      newline = pending.indexOf('\n')
    }
  }
  pending += decoder.decode()
  if (pending.length > 0) {
    yield stripCarriageReturn(pending)
  }
}

function stripCarriageReturn(line: string): string {
  return line.endsWith('\r') ? line.slice(0, -1) : line
}
