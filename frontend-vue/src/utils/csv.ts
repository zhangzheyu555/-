import Papa from 'papaparse'

export interface PreparedCsvFile {
  file: File
  encoding: 'UTF-8' | 'GBK'
  delimiter: ',' | '\t' | ';'
  headers: string[]
}

function decodeCsv(bytes: ArrayBuffer) {
  try {
    return {
      text: new TextDecoder('utf-8', { fatal: true }).decode(bytes).replace(/^\uFEFF/, ''),
      encoding: 'UTF-8' as const,
    }
  } catch {
    return {
      text: new TextDecoder('gb18030', { fatal: true }).decode(bytes).replace(/^\uFEFF/, ''),
      encoding: 'GBK' as const,
    }
  }
}

export async function prepareCsvFile(file: File): Promise<PreparedCsvFile> {
  const decoded = decodeCsv(await file.arrayBuffer())
  const result = Papa.parse<string[]>(decoded.text, {
    delimiter: '',
    delimitersToGuess: [',', '\t', ';'],
    skipEmptyLines: 'greedy',
  })
  const fatalError = result.errors.find((item) => item.type === 'Quotes' || item.type === 'Delimiter')
  if (fatalError) throw new Error(`CSV 格式错误：${fatalError.message}`)
  if (!result.data.length) throw new Error('CSV 文件没有可读取的内容。')

  const delimiter = result.meta.delimiter as ',' | '\t' | ';'
  if (![',', '\t', ';'].includes(delimiter)) throw new Error('CSV 分隔符无法识别，请使用逗号、制表符或分号。')
  const rows = result.data.map((row) => row.map((cell) => String(cell ?? '').trim()))
  const headers = (rows[0] || []).map((header) => header.replace(/^\uFEFF/, '').trim()).filter(Boolean)
  const normalized = Papa.unparse(rows, { delimiter: ',', newline: '\r\n', quotes: true })
  return {
    file: new File([`\uFEFF${normalized}`], file.name, { type: 'text/csv;charset=utf-8' }),
    encoding: decoded.encoding,
    delimiter,
    headers,
  }
}
