// Оформление в духе ГОСТ 7.32: Times New Roman 14pt, полуторный интервал,
// поля 30/15/20/20, нумерация страниц по центру снизу.

#let gost(title-page, body) = {
  set page(
    paper: "a4",
    margin: (left: 30mm, right: 15mm, top: 20mm, bottom: 20mm),
    number-align: center + bottom,
  )
  set text(font: ("Times New Roman", "Liberation Serif"), size: 14pt, lang: "ru")
  set par(leading: 1.0em, justify: true)

  set heading(numbering: "1.1")
  show heading.where(level: 1): it => {
    pagebreak(weak: true)
    set text(size: 16pt, weight: "bold")
    set par(first-line-indent: 0cm)
    upper(it)
    v(0.5em)
  }
  show heading.where(level: 2): it => {
    set text(size: 14pt, weight: "bold")
    set par(first-line-indent: 0cm)
    v(0.5em)
    it
    v(0.3em)
  }

  show figure.caption: set text(size: 12pt)
  // ГОСТ: «Рисунок N — …» под рисунком, «Таблица N — …» над таблицей
  show figure.where(kind: image): set figure(supplement: [Рисунок])
  show figure.where(kind: table): set figure.caption(position: top)
  set figure.caption(separator: [ — ])
  show raw: set text(font: ("DejaVu Sans Mono", "Liberation Mono"), size: 10pt)
  show table: set text(size: 12pt)

  title-page
  pagebreak()

  // Оглавление
  {
    set par(first-line-indent: 0cm)
    align(center)[#text(weight: "bold", size: 16pt)[СОДЕРЖАНИЕ]]
    v(1em)
    outline(title: none, indent: auto)
  }
  pagebreak()

  body
}
