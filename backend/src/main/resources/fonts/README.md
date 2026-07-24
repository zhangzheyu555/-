# Bundled PDF font

`NotoSansSC-Regular.ttf` is the 400-weight static instance generated from
Noto Sans CJK Simplified Chinese 2.004.

- Source: https://github.com/notofonts/noto-cjk/tree/Sans2.004
- Source file: `Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf`
- Instance axis: `wght=400`
- Removed table: unsupported Unicode variation-sequence `cmap` format 14
- Font SHA-256: `b9f82b75a09ef9b4d9cf4abb71d30f69f4928b4aef4ffcefad2ae739dedd9938`
- License: SIL Open Font License 1.1 (`OFL-1.1.txt`)

The warehouse PDF renderer embeds a subset of this font into each generated
document so Chinese output does not depend on fonts installed on the server.
